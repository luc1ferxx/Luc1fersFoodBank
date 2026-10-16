import { useCallback, useEffect, useState } from "react";
import { isAbortError } from "../api/client";
import type { CurrentUser, OrderNotification } from "../api/dto";
import { getNotifications } from "../api/endpoints";
import { errorCopy } from "../lib/format";
import { useLatestRequest } from "./useLatestRequest";
import { usePolling } from "./usePolling";

export const NOTIFICATION_POLL_MS = 15000;

/**
 * Order notifications for the signed-in user, polled every 15 seconds while polling
 * is enabled. Polling stops while the tab is hidden and catches up with one
 * immediate request when the tab becomes visible again. It also stops while the
 * ordering page is not the shown workspace (`shown: false`).
 */
export function useNotifications(
  user: CurrentUser | null,
  { shown = true }: { shown?: boolean } = {},
) {
  const [notifications, setNotifications] = useState<OrderNotification[]>([]);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState("");
  const [polling, setPolling] = useState(true);
  const nextNotificationsSignal = useLatestRequest();

  const load = useCallback(() => {
    const signal = nextNotificationsSignal();
    return getNotifications({ signal })
      .then((data) => {
        if (!signal.aborted) {
          setNotifications(data);
          setError("");
        }
      })
      .catch((requestError: unknown) => {
        if (!signal.aborted && !isAbortError(requestError)) {
          setError(errorCopy(requestError, "Unable to load notifications."));
        }
      });
  }, [nextNotificationsSignal]);

  useEffect(() => {
    if (user) {
      void load();
    }
  }, [user, load]);

  const reload = useCallback(() => {
    setLoading(true);
    return load().finally(() => setLoading(false));
  }, [load]);

  usePolling(() => void load(), {
    enabled: Boolean(user) && polling && shown,
    intervalMs: NOTIFICATION_POLL_MS,
  });

  return { notifications, loading, error, polling, setPolling, load, reload };
}

export type NotificationsState = ReturnType<typeof useNotifications>;
