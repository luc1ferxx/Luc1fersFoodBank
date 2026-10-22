import { useEffect, useEffectEvent } from "react";
import type { PushTopic } from "../api/dto";
import {
  PUSH_FALLBACK_POLL_MS,
  PUSH_SIGNAL_COALESCE_MS,
  useServerSignals,
} from "./useServerSignals";

const pageIsHidden = () => typeof document !== "undefined" && document.visibilityState === "hidden";

export interface PollingOptions {
  enabled: boolean;
  intervalMs: number;
  /**
   * The change signal of the polled list (ADR 0003). While the user's stream is open,
   * a signal of this topic or a (re)connect calls `onTick`, at most once per
   * PUSH_SIGNAL_COALESCE_MS, and the interval stretches to PUSH_FALLBACK_POLL_MS; while
   * it is closed, `intervalMs` holds.
   */
  signalTopic?: PushTopic;
}

/**
 * Calls `onTick` every `intervalMs` while `enabled`. Polling stops while the tab is
 * hidden and catches up with one immediate call when it becomes visible again. The
 * latest `onTick` is always used, so a callback that changes (a new filter, say)
 * does not restart the interval.
 *
 * With a `signalTopic`, the first signal of it (or a stream that reopens) after a quiet
 * period calls `onTick` at once and opens a window of PUSH_SIGNAL_COALESCE_MS; the
 * signals of that window fold into one more call when it ends, which opens the next
 * window, so a burst of signals (an inbox changing thousands of times in a few minutes)
 * reads once a second instead of once per signal. A signal that arrives while the tab is
 * hidden needs no call, since showing the tab again calls once anyway; a call the window
 * still owes when it ends on a hidden tab is dropped for the same reason. The open stream
 * stretches the interval to PUSH_FALLBACK_POLL_MS, which only catches a lost signal.
 *
 * A tick checks `enabled` as of the latest committed render, not only the effect that
 * armed the interval: React clears the interval in an effect cleanup, which can run a
 * task after the render that turned polling off (a 403, say) is already on screen,
 * and an interval that fires in between must not poll.
 */
export function usePolling(
  onTick: () => void,
  { enabled, intervalMs, signalTopic }: PollingOptions,
): void {
  const { connected, subscribe } = useServerSignals();
  const periodMs =
    signalTopic !== undefined && connected
      ? Math.max(intervalMs, PUSH_FALLBACK_POLL_MS)
      : intervalMs;

  const tick = useEffectEvent(() => {
    if (enabled) {
      onTick();
    }
  });

  useEffect(() => {
    if (!enabled) {
      return undefined;
    }

    let timer: ReturnType<typeof setInterval> | null = null;
    const start = () => {
      if (timer === null) {
        timer = setInterval(() => tick(), periodMs);
      }
    };
    const stop = () => {
      if (timer !== null) {
        clearInterval(timer);
        timer = null;
      }
    };
    const handleVisibilityChange = () => {
      if (pageIsHidden()) {
        stop();
        return;
      }
      if (timer === null) {
        tick();
        start();
      }
    };

    if (!pageIsHidden()) {
      start();
    }
    document.addEventListener("visibilitychange", handleVisibilityChange);

    return () => {
      stop();
      document.removeEventListener("visibilitychange", handleVisibilityChange);
    };
  }, [enabled, periodMs]);

  useEffect(() => {
    if (!enabled || signalTopic === undefined) {
      return undefined;
    }

    // The coalescing window: while it is open, signals only mark one read as pending.
    let coalesceTimer: ReturnType<typeof setTimeout> | null = null;
    let pending = false;
    const readAndOpenWindow = () => {
      tick();
      coalesceTimer = setTimeout(() => {
        coalesceTimer = null;
        if (!pending) {
          return;
        }
        pending = false;
        // On a hidden tab the owed read is dropped: showing the tab reads once anyway.
        if (!pageIsHidden()) {
          readAndOpenWindow();
        }
      }, PUSH_SIGNAL_COALESCE_MS);
    };

    const unsubscribe = subscribe(signalTopic, () => {
      if (pageIsHidden()) {
        return;
      }
      if (coalesceTimer === null) {
        readAndOpenWindow();
      } else {
        pending = true;
      }
    });

    return () => {
      unsubscribe();
      pending = false;
      if (coalesceTimer !== null) {
        clearTimeout(coalesceTimer);
        coalesceTimer = null;
      }
    };
  }, [enabled, signalTopic, subscribe]);
}
