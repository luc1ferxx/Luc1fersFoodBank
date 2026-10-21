import { useCallback, useRef, useState } from "react";
import { createIdempotencyKey, hasErrorCode, isAbortError } from "../api/client";
import {
  type AfterSalesCase,
  type AfterSalesStatus,
  type ArbitrateAfterSalesRequest,
  type ArbitrationDecision,
  ERROR_CODES,
  type Refund,
} from "../api/dto";
import {
  arbitrateAfterSalesCase,
  getAdminAfterSalesCase,
  getAdminAfterSalesCases,
  getAdminRefunds,
  retryRefund as retryRefundRequest,
} from "../api/endpoints";
import { errorCopy, formatMoney } from "../lib/format";
import { isPartialAmount, parseAmountInput } from "../lib/forms";
import { useLatestRequest } from "./useLatestRequest";

/** The longest arbitration note the backend accepts. */
export const ARBITRATION_NOTE_MAX = 1000;

interface Loaded<T> {
  data: T | null;
  loading: boolean;
  error: string;
}

const idle = <T>(): Loaded<T> => ({ data: null, loading: false, error: "" });

/**
 * The admin console's after-sales section (ADR 0002 section 12.4): the cases of one
 * status (the arbitration queue, IN_ARBITRATION, by default), the details of the
 * selected case with its history and its refund's last_error, the refunds the
 * executor gave up (FAILED), arbitration, and retrying a refund.
 *
 * Created at the top of AdminConsole, which stays mounted while another workspace is
 * shown, so an arbitration that failed is retried with the same Idempotency-Key for as
 * long as its payload (case, decision, amount, note) is unchanged; a changed payload
 * gets a new key and a success drops it. Reads are started by the section (`loadQueue`,
 * `loadRefunds`, `select`) and each list keeps only its newest read: a superseded
 * response, or one that started before an arbitration's result, is dropped. A refund
 * retry sends no key (the backend makes it idempotent by state).
 */
export function useAfterSalesAdmin() {
  const [status, setStatus] = useState<AfterSalesStatus>("IN_ARBITRATION");
  const [queue, setQueue] = useState<Loaded<AfterSalesCase[]>>(idle);
  const [selectedId, setSelectedId] = useState<number | null>(null);
  const [detail, setDetail] = useState<Loaded<AfterSalesCase>>(idle);
  const [refunds, setRefunds] = useState<Loaded<Refund[]>>(idle);
  const [arbitrating, setArbitrating] = useState(false);
  const [arbitrateError, setArbitrateError] = useState("");
  const [notice, setNotice] = useState("");
  const [retrying, setRetrying] = useState<ReadonlySet<number>>(new Set());
  const [refundError, setRefundError] = useState("");
  // The status of the list on screen, for the reads that keep it (`loadQueue()`).
  const statusRef = useRef<AfterSalesStatus>("IN_ARBITRATION");
  // The case whose details are on screen or loading, for the answers that may replace them.
  const selectedIdRef = useRef<number | null>(null);
  const arbitrateKeyRef = useRef<string | null>(null);
  const arbitratePayloadRef = useRef("");
  const nextQueueSignal = useLatestRequest();
  const nextDetailSignal = useLatestRequest();
  const nextRefundsSignal = useLatestRequest();

  const loadQueue = useCallback(
    (nextStatus: AfterSalesStatus = statusRef.current) => {
      const signal = nextQueueSignal();
      // A list of another status is not kept on screen while the new one loads.
      const sameStatus = nextStatus === statusRef.current;
      statusRef.current = nextStatus;
      setStatus(nextStatus);
      setQueue((current) => ({
        data: sameStatus ? current.data : null,
        loading: true,
        error: "",
      }));
      return getAdminAfterSalesCases({ status: nextStatus, signal })
        .then((cases) => {
          if (!signal.aborted) {
            setQueue({ data: cases, loading: false, error: "" });
          }
        })
        .catch((requestError: unknown) => {
          if (!signal.aborted && !isAbortError(requestError)) {
            setQueue((current) => ({
              data: current.data,
              loading: false,
              error: errorCopy(requestError, "Unable to load the refund requests."),
            }));
          }
        });
    },
    [nextQueueSignal],
  );

  const select = useCallback(
    (caseId: number) => {
      const signal = nextDetailSignal();
      selectedIdRef.current = caseId;
      setSelectedId(caseId);
      setArbitrateError("");
      setDetail((current) => ({
        data: current.data?.id === caseId ? current.data : null,
        loading: true,
        error: "",
      }));
      return getAdminAfterSalesCase(caseId, { signal })
        .then((loaded) => {
          if (!signal.aborted) {
            setDetail({ data: loaded, loading: false, error: "" });
          }
        })
        .catch((requestError: unknown) => {
          if (!signal.aborted && !isAbortError(requestError)) {
            setDetail((current) => ({
              data: current.data,
              loading: false,
              error: errorCopy(requestError, "Unable to load the refund request."),
            }));
          }
        });
    },
    [nextDetailSignal],
  );

  const loadRefunds = useCallback(() => {
    const signal = nextRefundsSignal();
    setRefunds((current) => ({ ...current, loading: true, error: "" }));
    return getAdminRefunds({ status: "FAILED", signal })
      .then((failed) => {
        if (!signal.aborted) {
          setRefunds({ data: failed, loading: false, error: "" });
        }
      })
      .catch((requestError: unknown) => {
        if (!signal.aborted && !isAbortError(requestError)) {
          setRefunds((current) => ({
            data: current.data,
            loading: false,
            error: errorCopy(requestError, "Unable to load the failed refunds."),
          }));
        }
      });
  }, [nextRefundsSignal]);

  /**
   * Shows a case an arbitration returned: in the details when that case is still the
   * selected one (over its read in flight, which may predate the decision; another
   * case's read is left alone), and in the list, which is read again since a read in
   * flight may predate the decision too and a decided case leaves the queue.
   */
  const applyCase = useCallback(
    (updated: AfterSalesCase) => {
      if (selectedIdRef.current === updated.id) {
        nextDetailSignal();
        setDetail({ data: updated, loading: false, error: "" });
      }
      setQueue((current) => ({
        ...current,
        data: current.data?.map((item) => (item.id === updated.id ? updated : item)) ?? null,
      }));
      void loadQueue();
    },
    [nextDetailSignal, loadQueue],
  );

  /**
   * Arbitrates `current`: FULL and REJECT send no amount, PARTIAL the typed one, which
   * must be more than zero and less than the requested amount. A note is required.
   * Returns whether the decision was taken.
   */
  const arbitrate = useCallback(
    async (
      current: AfterSalesCase,
      decision: ArbitrationDecision,
      amountInput: string,
      noteInput: string,
    ): Promise<boolean> => {
      const note = noteInput.trim();
      if (!note) {
        setArbitrateError("Write a note for the customer and the restaurant.");
        return false;
      }
      if (note.length > ARBITRATION_NOTE_MAX) {
        setArbitrateError(`Keep the note under ${ARBITRATION_NOTE_MAX} characters.`);
        return false;
      }
      const body: ArbitrateAfterSalesRequest = { decision, note };
      if (decision === "PARTIAL") {
        const amount = parseAmountInput(amountInput);
        if (amount === null || !isPartialAmount(amount, current.requested_amount)) {
          setArbitrateError(
            `Enter an amount more than 0 and less than ${formatMoney(current.requested_amount, current.currency)}, with at most two decimals.`,
          );
          return false;
        }
        body.amount = amount;
      }

      const payload = `${current.id}:${decision}:${body.amount ?? ""}:${note}`;
      if (arbitratePayloadRef.current !== payload) {
        arbitrateKeyRef.current = null;
      }
      arbitratePayloadRef.current = payload;
      const key = arbitrateKeyRef.current ?? createIdempotencyKey();
      arbitrateKeyRef.current = key;
      setArbitrating(true);
      setArbitrateError("");
      setNotice("");

      try {
        const updated = await arbitrateAfterSalesCase(current.id, body, key);
        arbitratePayloadRef.current = "";
        arbitrateKeyRef.current = null;
        applyCase(updated);
        setNotice(`Case #${updated.id} arbitrated: it is ${updated.status} now.`);
        return true;
      } catch (requestError) {
        setArbitrateError(errorCopy(requestError, "Unable to arbitrate the refund request."));
        return false;
      } finally {
        setArbitrating(false);
      }
    },
    [applyCase],
  );

  /**
   * Moves a FAILED refund back to PENDING for the executor. A refund that is no longer
   * FAILED (409 REFUND_STATUS_CONFLICT) re-reads the list.
   */
  const retryRefund = useCallback(
    async (refund: Refund) => {
      setRetrying((current) => new Set(current).add(refund.id));
      setRefundError("");
      setNotice("");
      try {
        const retried = await retryRefundRequest(refund.id);
        // Off the FAILED list at once; the re-read replaces a read that predates the retry.
        setRefunds((current) => ({
          ...current,
          data: current.data?.filter((item) => item.id !== retried.id) ?? null,
        }));
        void loadRefunds();
        setDetail((current) =>
          current.data?.refund?.id === retried.id
            ? { ...current, data: { ...current.data, refund: retried } }
            : current,
        );
        setNotice(`Refund #${retried.id} is ${retried.status} again; the executor will retry it.`);
      } catch (requestError) {
        // Above the list: a refund that is no longer FAILED leaves it on the re-read.
        setRefundError(
          `Refund #${refund.id}: ${errorCopy(requestError, "Unable to retry the refund.")}`,
        );
        if (hasErrorCode(requestError, ERROR_CODES.refundStatusConflict)) {
          void loadRefunds();
        }
      } finally {
        setRetrying((current) => {
          const next = new Set(current);
          next.delete(refund.id);
          return next;
        });
      }
    },
    [loadRefunds],
  );

  return {
    status,
    queue,
    selectedId,
    detail: detail.data?.id === selectedId ? detail : { ...detail, data: null },
    refunds,
    arbitrating,
    arbitrateError,
    notice,
    retrying,
    refundError,
    loadQueue,
    select,
    loadRefunds,
    arbitrate,
    retryRefund,
  };
}

export type AfterSalesAdmin = ReturnType<typeof useAfterSalesAdmin>;
