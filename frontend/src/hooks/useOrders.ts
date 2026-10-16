import { useCallback, useEffect, useRef, useState } from "react";
import { createIdempotencyKey, isAbortError } from "../api/client";
import type { CurrentUser, Order, OrderStatus } from "../api/dto";
import { cancelOrder, getOrders } from "../api/endpoints";
import { errorCopy } from "../lib/format";
import { useLatestRequest } from "./useLatestRequest";

export const CANCELLABLE_STATUSES: ReadonlySet<OrderStatus> = new Set<OrderStatus>([
  "PLACED",
  "PAID",
  "ACCEPTED",
]);

export function useOrders(
  user: CurrentUser | null,
  { onOrderChanged }: { onOrderChanged?: () => void } = {},
) {
  const [orders, setOrders] = useState<Order[]>([]);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState("");
  const [cancelingOrderId, setCancelingOrderId] = useState<number | null>(null);
  // One Idempotency-Key per order until its cancel succeeds, so retries are deduplicated.
  const cancelKeysRef = useRef(new Map<number, string>());
  const nextOrdersSignal = useLatestRequest();

  const load = useCallback(() => {
    const signal = nextOrdersSignal();
    return getOrders({ signal })
      .then((data) => {
        if (!signal.aborted) {
          setOrders(data);
          setError("");
        }
      })
      .catch((requestError: unknown) => {
        if (!signal.aborted && !isAbortError(requestError)) {
          setError(errorCopy(requestError, "Unable to load orders."));
        }
      });
  }, [nextOrdersSignal]);

  useEffect(() => {
    if (user) {
      void load();
    }
  }, [user, load]);

  const reload = useCallback(() => {
    setLoading(true);
    return load().finally(() => setLoading(false));
  }, [load]);

  const cancel = useCallback(
    (order: Order) => {
      if (!CANCELLABLE_STATUSES.has(order.status)) {
        return Promise.resolve();
      }

      const key = cancelKeysRef.current.get(order.id) ?? createIdempotencyKey();
      cancelKeysRef.current.set(order.id, key);
      setCancelingOrderId(order.id);
      setError("");

      return cancelOrder(order.id, key)
        .then((updatedOrder) => {
          cancelKeysRef.current.delete(order.id);
          setOrders((current) =>
            current.map((item) => (item.id === updatedOrder.id ? updatedOrder : item)),
          );
          onOrderChanged?.();
        })
        .catch((requestError: unknown) => {
          setError(errorCopy(requestError, "Unable to cancel order."));
        })
        .finally(() => {
          setCancelingOrderId(null);
        });
    },
    [onOrderChanged],
  );

  return { orders, loading, error, cancelingOrderId, load, reload, cancel };
}

export type OrdersState = ReturnType<typeof useOrders>;
