import { useCallback, useEffect, useState } from "react";
import { isAbortError } from "../api/client";
import { getNotifications } from "../api/endpoints";
import { errorCopy, toArray } from "../lib/format";
import { useLatestRequest } from "./useLatestRequest";

export const NOTIFICATION_POLL_MS = 15000;

const pageIsHidden = () => typeof document !== "undefined" && document.visibilityState === "hidden";

/**
 * Order notifications for the signed-in user, polled every 15 seconds while polling
 * is enabled. Polling stops while the tab is hidden and catches up with one
 * immediate request when the tab becomes visible again.
 */
export function useNotifications(user) {
  const [notifications, setNotifications] = useState([]);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState("");
  const [polling, setPolling] = useState(true);
  const nextNotificationsSignal = useLatestRequest();

  const load = useCallback(() => {
    const signal = nextNotificationsSignal();
    return getNotifications({ signal })
      .then((data) => {
        if (!signal.aborted) {
          setNotifications(toArray(data));
          setError("");
        }
      })
      .catch((requestError) => {
        if (!signal.aborted && !isAbortError(requestError)) {
          setError(errorCopy(requestError, "Unable to load notifications."));
        }
      });
  }, [nextNotificationsSignal]);

  useEffect(() => {
    if (user) {
      load();
    }
  }, [user, load]);

  const reload = useCallback(() => {
    setLoading(true);
    return load().finally(() => setLoading(false));
  }, [load]);

  useEffect(() => {
    if (!user || !polling) {
      return undefined;
    }

    let timer = null;
    const start = () => {
      if (timer === null) {
        timer = setInterval(() => load(), NOTIFICATION_POLL_MS);
      }
    };
    const stop = () => {
      clearInterval(timer);
      timer = null;
    };
    const handleVisibilityChange = () => {
      if (pageIsHidden()) {
        stop();
        return;
      }
      if (timer === null) {
        load();
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
  }, [user, polling, load]);

  return { notifications, loading, error, polling, setPolling, load, reload };
}
