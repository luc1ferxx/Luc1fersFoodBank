import { useCallback, useEffect, useRef, useState } from "react";
import { createIdempotencyKey, isAbortError } from "../api/client";
import { cancelOrder, getOrders } from "../api/endpoints";
import { errorCopy, toArray } from "../lib/format";
import { useLatestRequest } from "./useLatestRequest";

export const CANCELLABLE_STATUSES = new Set(["PLACED", "PAID", "ACCEPTED"]);

export function useOrders(user, { onOrderChanged } = {}) {
  const [orders, setOrders] = useState([]);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState("");
  const [cancelingOrderId, setCancelingOrderId] = useState(null);
  // One Idempotency-Key per order until its cancel succeeds, so retries are deduplicated.
  const cancelKeysRef = useRef({});
  const nextOrdersSignal = useLatestRequest();

  const load = useCallback(() => {
    const signal = nextOrdersSignal();
    return getOrders({ signal })
      .then((data) => {
        if (!signal.aborted) {
          setOrders(toArray(data));
          setError("");
        }
      })
      .catch((requestError) => {
        if (!signal.aborted && !isAbortError(requestError)) {
          setError(errorCopy(requestError, "Unable to load orders."));
        }
      });
  }, [nextOrdersSignal]);

  useEffect(() => {
    if (user) {
      load();
    }
  }, [user, load]);

  const reload = useCallback(() => {
    setLoading(true);
    return load().finally(() => setLoading(false));
  }, [load]);

  const cancel = useCallback(
    (order) => {
      if (!CANCELLABLE_STATUSES.has(order.status)) {
        return Promise.resolve();
      }

      const key = cancelKeysRef.current[order.id] || createIdempotencyKey();
      cancelKeysRef.current[order.id] = key;
      setCancelingOrderId(order.id);
      setError("");

      return cancelOrder(order.id, key)
        .then((updatedOrder) => {
          delete cancelKeysRef.current[order.id];
          setOrders((current) =>
            current.map((item) => (item.id === updatedOrder.id ? updatedOrder : item)),
          );
          onOrderChanged?.();
        })
        .catch((requestError) => {
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
