import { useEffect, useEffectEvent } from "react";

const pageIsHidden = () => typeof document !== "undefined" && document.visibilityState === "hidden";

/**
 * Calls `onTick` every `intervalMs` while `enabled`. Polling stops while the tab is
 * hidden and catches up with one immediate call when it becomes visible again. The
 * latest `onTick` is always used, so a callback that changes (a new filter, say)
 * does not restart the interval.
 */
export function usePolling(
  onTick: () => void,
  { enabled, intervalMs }: { enabled: boolean; intervalMs: number },
): void {
  const tick = useEffectEvent(onTick);

  useEffect(() => {
    if (!enabled) {
      return undefined;
    }

    let timer: ReturnType<typeof setInterval> | null = null;
    const start = () => {
      if (timer === null) {
        timer = setInterval(() => tick(), intervalMs);
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
  }, [enabled, intervalMs]);
}
