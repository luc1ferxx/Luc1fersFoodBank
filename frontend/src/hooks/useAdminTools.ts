import { useRef, useState } from "react";
import { createIdempotencyKey } from "../api/client";
import type { DeadLetterReplay, Order, OrderStatus } from "../api/dto";
import { replayDeadLetter, updateOrderStatus } from "../api/endpoints";
import { errorCopy } from "../lib/format";

export type AdminPending = "" | "status" | "replay";

/** A positive whole number typed into an ID field, or null. */
export const parseId = (value: string): number | null => {
  const trimmed = value.trim();
  return /^\d+$/.test(trimmed) && Number(trimmed) > 0 ? Number(trimmed) : null;
};

/**
 * Manual admin forms. The backend enforces ROLE_ADMIN; the UI only collects IDs.
 * Retrying the same order/status pair reuses its Idempotency-Key.
 */
export function useAdminTools({ onOrderChanged }: { onOrderChanged?: () => void } = {}) {
  const [pending, setPending] = useState<AdminPending>("");
  const [error, setError] = useState("");
  const [orderResult, setOrderResult] = useState<Order | null>(null);
  const [replayResult, setReplayResult] = useState<DeadLetterReplay | null>(null);
  const statusKeyRef = useRef<string | null>(null);
  const statusPayloadRef = useRef("");

  const updateStatus = (orderIdInput: string, status: OrderStatus | null): Promise<void> => {
    const orderId = parseId(orderIdInput);

    if (orderId === null || !status) {
      setError("Order ID and target status are required.");
      return Promise.resolve();
    }

    const payloadKey = `${orderId}:${status}`;

    if (statusPayloadRef.current && statusPayloadRef.current !== payloadKey) {
      statusKeyRef.current = null;
    }

    statusPayloadRef.current = payloadKey;
    const statusKey = statusKeyRef.current ?? createIdempotencyKey();
    statusKeyRef.current = statusKey;
    setPending("status");
    setError("");
    setOrderResult(null);

    return updateOrderStatus(orderId, status, statusKey)
      .then((order) => {
        setOrderResult(order);
        statusPayloadRef.current = "";
        statusKeyRef.current = null;
        onOrderChanged?.();
      })
      .catch((requestError: unknown) => {
        setError(errorCopy(requestError, "Unable to update order status."));
      })
      .finally(() => {
        setPending("");
      });
  };

  const replay = (deadLetterIdInput: string): Promise<void> => {
    const deadLetterEventId = parseId(deadLetterIdInput);

    if (deadLetterEventId === null) {
      setError("Dead-letter event ID is required.");
      return Promise.resolve();
    }

    setPending("replay");
    setError("");
    setReplayResult(null);

    return replayDeadLetter(deadLetterEventId)
      .then(setReplayResult)
      .catch((requestError: unknown) => {
        setError(errorCopy(requestError, "Unable to replay dead-letter event."));
      })
      .finally(() => {
        setPending("");
      });
  };

  return { pending, error, orderResult, replayResult, updateStatus, replay };
}

export type AdminTools = ReturnType<typeof useAdminTools>;
