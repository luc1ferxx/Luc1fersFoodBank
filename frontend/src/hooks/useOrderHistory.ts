import { useCallback, useEffect, useState } from "react";
import { isAbortError } from "../api/client";
import type { OrderStatus, OrderStatusChange } from "../api/dto";
import { getOrderHistory } from "../api/endpoints";
import { errorCopy } from "../lib/format";
import { useLatestRequest } from "./useLatestRequest";

interface HistoryResult {
  key: string;
  entries: OrderStatusChange[];
  error: string;
}

/**
 * GET /orders/{id}/history for one order card. It loads on mount, again whenever the
 * order's status changes (a cancel, a merchant decision seen on refresh), and on
 * `reload()`. A response for a superseded request is dropped; earlier entries stay
 * on screen while a newer request runs.
 */
export function useOrderHistory(orderId: number, status: OrderStatus) {
  const [reloads, setReloads] = useState(0);
  const [result, setResult] = useState<HistoryResult | null>(null);
  const nextHistorySignal = useLatestRequest();
  const key = `${orderId}:${status}:${reloads}`;

  useEffect(() => {
    const signal = nextHistorySignal();
    getOrderHistory(orderId, { signal })
      .then((entries) => {
        if (!signal.aborted) {
          setResult({ key, entries, error: "" });
        }
      })
      .catch((requestError: unknown) => {
        if (!signal.aborted && !isAbortError(requestError)) {
          setResult((current) => ({
            key,
            entries: current?.entries ?? [],
            error: errorCopy(requestError, "Unable to load the status history."),
          }));
        }
      });
  }, [key, orderId, nextHistorySignal]);

  const reload = useCallback(() => setReloads((count) => count + 1), []);

  return {
    entries: result?.entries ?? [],
    error: result?.error ?? "",
    loading: result?.key !== key,
    reload,
  };
}
