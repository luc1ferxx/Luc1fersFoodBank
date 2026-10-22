import { useEffect, useEffectEvent } from "react";
import type { PushTopic } from "../api/dto";
import { PUSH_FALLBACK_POLL_MS, useServerSignals } from "./useServerSignals";

const pageIsHidden = () => typeof document !== "undefined" && document.visibilityState === "hidden";

export interface PollingOptions {
  enabled: boolean;
  intervalMs: number;
  /**
   * The change signal of the polled list (ADR 0003). While the user's stream is open,
   * every signal of this topic and every (re)connect call `onTick` at once, and the
   * interval stretches to PUSH_FALLBACK_POLL_MS; while it is closed, `intervalMs` holds.
   */
  signalTopic?: PushTopic;
}

/**
 * Calls `onTick` every `intervalMs` while `enabled`. Polling stops while the tab is
 * hidden and catches up with one immediate call when it becomes visible again. The
 * latest `onTick` is always used, so a callback that changes (a new filter, say)
 * does not restart the interval.
 *
 * With a `signalTopic`, a signal of it (or a stream that reopens) calls `onTick` at once
 * while the tab is visible; one that arrives while it is hidden needs no call, since
 * showing the tab again calls once anyway. The open stream stretches the interval to
 * PUSH_FALLBACK_POLL_MS, which only catches a lost signal.
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
    return subscribe(signalTopic, () => {
      if (!pageIsHidden()) {
        tick();
      }
    });
  }, [enabled, signalTopic, subscribe]);
}
