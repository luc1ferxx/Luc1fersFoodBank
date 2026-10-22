import { useCallback, useState } from "react";
import { ApiError } from "../api/client";
import type { AfterSalesStatus, MerchantAfterSalesCase } from "../api/dto";
import {
  approveMerchantAfterSales,
  getMerchantAfterSalesCases,
  offerMerchantAfterSales,
  rejectMerchantAfterSales,
} from "../api/endpoints";
import { errorCopy, formatMoney } from "../lib/format";
import { orderLabel } from "../lib/labels";
import { withEntry, withoutEntry } from "../lib/maps";
import { useLiveResource } from "./useLiveResource";
import { MERCHANT_POLL_MS } from "./useMerchantOrders";
import { usePolling } from "./usePolling";

const FORBIDDEN = 403;

export type MerchantAfterSalesFilter = "ALL" | AfterSalesStatus;

/** The merchant's one answer to a request: approve it, reject it with a reason, or offer part of it. */
export type MerchantAfterSalesAnswer =
  | { command: "approve" }
  | { command: "reject"; reason: string }
  | { command: "offer"; amount: number; note: string | null };

export type MerchantAfterSalesCommand = MerchantAfterSalesAnswer["command"];

const FAILED_COPY: Readonly<Record<MerchantAfterSalesCommand, string>> = {
  approve: "approve the refund",
  reject: "reject the refund request",
  offer: "offer a partial refund",
};

/** "Order #<order number>": the merchant's after-sales page shows the public number. */
export const caseOrderLabel = (current: MerchantAfterSalesCase): string =>
  orderLabel({ id: current.order_id, order_no: current.order_no });

const doneCopy = (current: MerchantAfterSalesCase, answer: MerchantAfterSalesAnswer): string => {
  const order = caseOrderLabel(current);
  switch (answer.command) {
    case "approve":
      return `${order}: refund of ${formatMoney(current.requested_amount, current.currency)} approved.`;
    case "reject":
      return `${order}: refund request rejected.`;
    case "offer":
      return `${order}: partial refund of ${formatMoney(answer.amount, current.currency)} offered.`;
  }
};

const fetchCases = (filter: MerchantAfterSalesFilter, signal: AbortSignal) =>
  getMerchantAfterSalesCases({ status: filter === "ALL" ? undefined : filter, signal });

const sendAnswer = (caseId: number, answer: MerchantAfterSalesAnswer) => {
  switch (answer.command) {
    case "approve":
      return approveMerchantAfterSales(caseId);
    case "reject":
      return rejectMerchantAfterSales(caseId, answer.reason);
    case "offer":
      return offerMerchantAfterSales(caseId, answer.amount, answer.note);
  }
};

/**
 * The merchant console's after-sales inbox: GET /merchant/after-sales for the chosen
 * status filter, plus the merchant's one answer to a request (approve, reject with a
 * reason, or a partial refund offer). Like the orders, the list is read again on every
 * `merchant.after_sales` signal while the user's stream is open (and every 60 seconds
 * then), else polled every 15 seconds, only while the tab is visible.
 *
 * Like the order commands, the answers are idempotent by state on the server (the same
 * answer again returns the case unchanged), so they carry no Idempotency-Key. An
 * answer's result replaces the case on screen at once and drops any list read that
 * started before it; a 404, 409 (the customer withdrew, or the deadline passed and the
 * refund was approved automatically) or 403 re-reads the list.
 *
 * A list read that comes back 403 (the account was deactivated) sets `forbidden`:
 * polling stops and the console shows its notice instead of the raw 403.
 */
export function useMerchantAfterSales() {
  const [filter, setFilter] = useState<MerchantAfterSalesFilter>("ALL");
  const [polling, setPolling] = useState(true);
  const [pending, setPending] = useState<ReadonlyMap<number, MerchantAfterSalesCommand>>(new Map());
  const [commandErrors, setCommandErrors] = useState<ReadonlyMap<number, string>>(new Map());
  const [notice, setNotice] = useState("");
  const cases = useLiveResource(filter, fetchCases, "Unable to load the refund requests.");
  const { reload, applyAndReload } = cases;
  const forbidden = cases.errorStatus === FORBIDDEN;

  usePolling(reload, {
    enabled: polling && !forbidden,
    intervalMs: MERCHANT_POLL_MS,
    signalTopic: "merchant.after_sales",
  });

  const answer = useCallback(
    (current: MerchantAfterSalesCase, given: MerchantAfterSalesAnswer): Promise<boolean> => {
      const caseId = current.case_id;
      setPending((map) => withEntry(map, caseId, given.command));
      setCommandErrors((map) => withoutEntry(map, caseId));
      setNotice("");

      return sendAnswer(caseId, given)
        .then((updated) => {
          applyAndReload((list) =>
            list.map((item) => (item.case_id === updated.case_id ? updated : item)),
          );
          setNotice(doneCopy(updated, given));
          return true;
        })
        .catch((requestError: unknown) => {
          setCommandErrors((map) =>
            withEntry(
              map,
              caseId,
              errorCopy(
                requestError,
                `Unable to ${FAILED_COPY[given.command]} of ${caseOrderLabel(current)}.`,
              ),
            ),
          );
          if (
            requestError instanceof ApiError &&
            (requestError.status === 404 ||
              requestError.status === 409 ||
              requestError.status === FORBIDDEN)
          ) {
            reload();
          }
          return false;
        })
        .finally(() => {
          setPending((map) => withoutEntry(map, caseId));
        });
    },
    [applyAndReload, reload],
  );

  return {
    filter,
    setFilter,
    cases: cases.data ?? [],
    loaded: cases.data !== null,
    loading: cases.loading,
    /** The list read came back 403: the account is deactivated (or not bound). */
    forbidden,
    error: forbidden ? "" : cases.error,
    reload,
    polling,
    setPolling,
    pending,
    commandErrors,
    notice,
    answer,
  };
}

export type MerchantAfterSalesState = ReturnType<typeof useMerchantAfterSales>;
