import { useRef, useState } from "react";
import { createIdempotencyKey } from "../api/client";
import { replayDeadLetter, updateOrderStatus } from "../api/endpoints";
import { errorCopy } from "../lib/format";

/**
 * Manual admin forms. The backend enforces ROLE_ADMIN; the UI only collects IDs.
 * Retrying the same order/status pair reuses its Idempotency-Key.
 */
export function useAdminTools({ onOrderChanged } = {}) {
  const [pending, setPending] = useState("");
  const [error, setError] = useState("");
  const [orderResult, setOrderResult] = useState(null);
  const [replayResult, setReplayResult] = useState(null);
  const statusKeyRef = useRef(null);
  const statusPayloadRef = useRef("");

  const updateStatus = (orderId, status) => {
    if (!orderId || !status) {
      setError("Order ID and target status are required.");
      return Promise.resolve();
    }

    const payloadKey = `${orderId}:${status}`;

    if (statusPayloadRef.current && statusPayloadRef.current !== payloadKey) {
      statusKeyRef.current = null;
    }

    statusPayloadRef.current = payloadKey;
    statusKeyRef.current = statusKeyRef.current || createIdempotencyKey();
    setPending("status");
    setError("");
    setOrderResult(null);

    return updateOrderStatus(orderId, status, statusKeyRef.current)
      .then((order) => {
        setOrderResult(order);
        statusPayloadRef.current = "";
        statusKeyRef.current = null;
        onOrderChanged?.();
      })
      .catch((requestError) => {
        setError(errorCopy(requestError, "Unable to update order status."));
      })
      .finally(() => {
        setPending("");
      });
  };

  const replay = (deadLetterEventId) => {
    if (!deadLetterEventId) {
      setError("Dead-letter event ID is required.");
      return Promise.resolve();
    }

    setPending("replay");
    setError("");
    setReplayResult(null);

    return replayDeadLetter(deadLetterEventId)
      .then(setReplayResult)
      .catch((requestError) => {
        setError(errorCopy(requestError, "Unable to replay dead-letter event."));
      })
      .finally(() => {
        setPending("");
      });
  };

  return { pending, error, orderResult, replayResult, updateStatus, replay };
}
