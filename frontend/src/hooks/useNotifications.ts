import { useCallback, useEffect, useRef, useState } from "react";
import { isAbortError } from "../api/client";
import type { CurrentUser, OrderNotification } from "../api/dto";
import { getNotifications } from "../api/endpoints";
import { errorCopy } from "../lib/format";
import { useLatestRequest } from "./useLatestRequest";
import { usePolling } from "./usePolling";

export const NOTIFICATION_POLL_MS = 15000;

const newestIdOf = (notifications: readonly OrderNotification[]) =>
  notifications.reduce((newest, notification) => Math.max(newest, notification.id), 0);

/**
 * Order notifications for the signed-in user, read again on every `notifications`
 * signal while the user's stream is open (and every 60 seconds then, in case one was
 * lost), else polled every 15 seconds, while polling is enabled. Polling stops while
 * the tab is hidden and catches up with one immediate request when the tab becomes
 * visible again. It also stops while the ordering page is not the shown workspace
 * (`shown: false`).
 *
 * `onNewNotifications` is called when a read (on a signal, a poll, `load()` or
 * `reload()`) brings a notification newer than every one an earlier read brought: ids
 * only grow, so a higher id means the backend wrote something since (an order
 * completed, say), and the page reads the data it is about again. The first read only
 * sets that baseline, and a superseded or failed read changes nothing. That is how the
 * order list and the after-sales overview follow the `notifications` signal: every
 * change they show also writes a notification (ADR 0003), so they need no topic.
 */
export function useNotifications(
  user: CurrentUser | null,
  { shown = true, onNewNotifications }: { shown?: boolean; onNewNotifications?: () => void } = {},
) {
  const [notifications, setNotifications] = useState<OrderNotification[]>([]);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState("");
  const [polling, setPolling] = useState(true);
  const nextNotificationsSignal = useLatestRequest();
  // The highest id a read has brought; null until the first read answers.
  const newestIdRef = useRef<number | null>(null);
  // The latest callback, so a new one does not change `load` (and read again).
  const onNewNotificationsRef = useRef(onNewNotifications);
  useEffect(() => {
    onNewNotificationsRef.current = onNewNotifications;
  }, [onNewNotifications]);

  const load = useCallback(() => {
    const signal = nextNotificationsSignal();
    return getNotifications({ signal })
      .then((data) => {
        if (!signal.aborted) {
          setNotifications(data);
          setError("");
          const seen = newestIdRef.current;
          const newest = newestIdOf(data);
          newestIdRef.current = Math.max(seen ?? 0, newest);
          if (seen !== null && newest > seen) {
            onNewNotificationsRef.current?.();
          }
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
    signalTopic: "notifications",
  });

  return { notifications, loading, error, polling, setPolling, load, reload };
}

export type NotificationsState = ReturnType<typeof useNotifications>;
