import {
  createContext,
  useCallback,
  useContext,
  useEffect,
  useMemo,
  useRef,
  useState,
} from "react";
import { type PushTopic, decodePushSignal } from "../api/dto";
import { EVENT_STREAM_PATH } from "../api/endpoints";

/**
 * The poll interval of a screen while the stream that carries its topic is open. Signals
 * are best effort (at most once), so a slow poll still catches what one missed.
 */
export const PUSH_FALLBACK_POLL_MS = 60000;

/**
 * The shortest time between two reads a topic's signals can cause (usePolling). The first
 * signal of a quiet period reads at once; the signals of the next PUSH_SIGNAL_COALESCE_MS
 * fold into one read when that window ends. It bounds a burst (an inbox the backend
 * changes thousands of times in a few minutes) to one read per second per topic.
 */
export const PUSH_SIGNAL_COALESCE_MS = 1000;

/**
 * The wait before a new stream once the browser gave one up (readyState CLOSED: the
 * server refused it with 401, 403, 429 or 503, or a reconnect failed). It doubles with
 * every give-up in a row, up to STREAM_RETRY_MAX_MS, and starts over once a stream opens.
 */
export const STREAM_RETRY_BASE_MS = 30000;
export const STREAM_RETRY_MAX_MS = 300000;

/** EventSource.CLOSED: the browser does not reconnect this stream by itself. */
const CLOSED = 2;

export type SignalListener = () => void;

export interface ServerSignals {
  /** The stream is open: true after its `open` event, false after an `error` or a close. */
  connected: boolean;
  /**
   * Calls `listener` on every signal of `topic`. It is also called once whenever the
   * stream (re)opens, since signals sent while no stream was open are lost, and once
   * on `forbidden`, so the read surfaces the 403. Returns the unsubscribe.
   */
  subscribe: (topic: PushTopic, listener: SignalListener) => () => void;
}

/** Without a provider, or for a browser without EventSource: never connected, polling only. */
const POLLING_ONLY: ServerSignals = { connected: false, subscribe: () => () => undefined };

export const ServerSignalsContext = createContext<ServerSignals>(POLLING_ONLY);

/** The change signals of the signed-in user (ServerSignalsProvider in App's user-keyed shell). */
export const useServerSignals = (): ServerSignals => useContext(ServerSignalsContext);

/**
 * The topic of one `event: signal`, or null for data this client cannot read (a topic a
 * newer backend added, say), which is logged and ignored: polling still covers its list.
 */
const topicOf = (data: unknown): PushTopic | null => {
  try {
    const parsed: unknown = typeof data === "string" ? JSON.parse(data) : data;
    return decodePushSignal(parsed, "signal").topic;
  } catch (error) {
    console.warn("Ignored a change signal this client cannot read.", error);
    return null;
  }
};

const retryDelay = (givenUp: number) =>
  Math.min(STREAM_RETRY_BASE_MS * 2 ** givenUp, STREAM_RETRY_MAX_MS);

/**
 * Opens GET /events/stream with EventSource while `enabled` (a signed-in user) and hands
 * its signals to the subscribers of their topic (ADR 0003). The browser reconnects a
 * dropped stream by itself (`retry: 5000`); once it gives one up (CLOSED) the screens
 * poll at their own interval and a new stream is tried after a back-off. `forbidden` (a
 * deactivated merchant or rider) closes the stream for good. A browser without
 * EventSource gets no stream: every screen polls as before.
 */
export function useEventStream(enabled: boolean): ServerSignals {
  const [open, setOpen] = useState(false);
  const subscriptions = useRef(new Set<{ topic: PushTopic; listener: SignalListener }>());

  const subscribe = useCallback((topic: PushTopic, listener: SignalListener) => {
    const subscription = { topic, listener };
    subscriptions.current.add(subscription);
    return () => {
      subscriptions.current.delete(subscription);
    };
  }, []);

  useEffect(() => {
    const Source = typeof window.EventSource === "function" ? window.EventSource : null;
    if (!enabled || Source === null) {
      return undefined;
    }

    const notify = (topic: PushTopic | null) => {
      for (const subscription of [...subscriptions.current]) {
        if (topic === null || subscription.topic === topic) {
          subscription.listener();
        }
      }
    };

    let current: EventSource | null = null;
    let retryTimer: ReturnType<typeof setTimeout> | null = null;
    let givenUp = 0;

    const stop = () => {
      current?.close();
      current = null;
      if (retryTimer !== null) {
        clearTimeout(retryTimer);
        retryTimer = null;
      }
    };

    const connect = () => {
      retryTimer = null;
      const stream = new Source(EVENT_STREAM_PATH);
      current = stream;
      stream.addEventListener("open", () => {
        givenUp = 0;
        setOpen(true);
        // Signals sent while no stream was open are lost: every list reads once.
        notify(null);
      });
      stream.addEventListener("signal", (event) => {
        const topic = topicOf(event.data);
        if (topic !== null) {
          notify(topic);
        }
      });
      stream.addEventListener("forbidden", () => {
        // The login was deactivated: no reconnect. The reads surface the consoles' 403.
        stop();
        setOpen(false);
        notify(null);
      });
      stream.addEventListener("error", () => {
        setOpen(false);
        // CONNECTING: the browser retries by itself. CLOSED: it gave up; try again later.
        if (stream.readyState === CLOSED && current === stream) {
          stream.close();
          retryTimer = setTimeout(connect, retryDelay(givenUp));
          givenUp += 1;
        }
      });
    };

    connect();
    return () => {
      stop();
      setOpen(false);
    };
  }, [enabled]);

  const connected = enabled && open;
  return useMemo(() => ({ connected, subscribe }), [connected, subscribe]);
}
