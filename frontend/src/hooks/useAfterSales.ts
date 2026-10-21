import { useCallback, useEffect, useRef, useState } from "react";
import { ApiError, createIdempotencyKey, isAbortError } from "../api/client";
import {
  AFTER_SALES_OPEN_STATUSES,
  type AfterSalesCase,
  type AfterSalesOverview,
  type AfterSalesReason,
  type AfterSalesScope,
  type CurrentUser,
  type OpenAfterSalesRequest,
  type Order,
} from "../api/dto";
import {
  acceptAfterSalesOffer,
  declineAfterSalesOffer,
  escalateAfterSalesCase,
  getAfterSalesCase,
  getAfterSalesOverview,
  openAfterSalesCase,
  withdrawAfterSalesCase,
} from "../api/endpoints";
import { errorCopy } from "../lib/format";
import { orderLabel } from "../lib/labels";
import { withEntry, withoutEntry } from "../lib/maps";
import { useLatestRequest } from "./useLatestRequest";
import { useLiveResource } from "./useLiveResource";
import { usePolling } from "./usePolling";

export const AFTER_SALES_POLL_MS = 10000;

export type AfterSalesCommand = "open" | "accept-offer" | "decline-offer" | "escalate" | "withdraw";

/** What the request form collects; `quantities` (by order line id) only count for ITEMS. */
export interface AfterSalesDraft {
  scope: AfterSalesScope;
  quantities: ReadonlyMap<number, number>;
  reason: AfterSalesReason;
  description: string;
}

/** The request body of a draft, with its lines in order-line order so equal drafts serialize alike. */
export const openRequestOf = (draft: AfterSalesDraft): OpenAfterSalesRequest => ({
  scope: draft.scope,
  lines:
    draft.scope === "ITEMS"
      ? [...draft.quantities]
          .filter(([, quantity]) => quantity > 0)
          .sort(([a], [b]) => a - b)
          .map(([orderItemId, quantity]) => ({ order_item_id: orderItemId, quantity }))
      : [],
  reason_code: draft.reason,
  description: draft.description.trim(),
});

/** Whether the server may still change the case: an open step, or its refund on the way. */
export const isLiveCase = (afterSalesCase: AfterSalesCase): boolean =>
  AFTER_SALES_OPEN_STATUSES.has(afterSalesCase.status) || afterSalesCase.status === "REFUNDING";

/** The key of an order's request form, or of a case's buttons, in `pending` and `errors`. */
export const orderTarget = (orderId: number) => `order:${orderId}`;
export const caseTarget = (caseId: number) => `case:${caseId}`;

type OverviewKey = "customer" | "guest";

const EMPTY_OVERVIEW: AfterSalesOverview = { cases: [], eligible_orders: [] };

// A guest has no requests, and a 401 from /after-sales would read as an expired session.
const fetchOverview = (key: OverviewKey, signal: AbortSignal) =>
  key === "guest" ? Promise.resolve(EMPTY_OVERVIEW) : getAfterSalesOverview({ signal });

const withCase = (
  overview: AfterSalesOverview,
  updated: AfterSalesCase,
  opened: boolean,
): AfterSalesOverview => ({
  cases: overview.cases.some((item) => item.id === updated.id)
    ? overview.cases.map((item) => (item.id === updated.id ? updated : item))
    : [updated, ...overview.cases],
  // A new request takes its order off the eligible list; the reload says what is next.
  eligible_orders: opened
    ? overview.eligible_orders.filter((order) => order.order_id !== updated.order_id)
    : overview.eligible_orders,
});

/**
 * The customer's refund requests (GET /after-sales/cases) and the commands on them.
 *
 * The overview is read when the page mounts, on `reload()`, and every 10 seconds while
 * the page is shown and a case can still change (an open step, or a refund on its
 * way); polling stops once every case is final. Only the newest read may update the
 * screen, and a command's result drops a read that started before it.
 *
 * Every command sends an Idempotency-Key, kept per slot (`open:{order}:{attempt}` or
 * `{command}:{case}`) with the request body it was made for: a retry of the same body
 * reuses it, so a request that reached the server but lost its answer is replayed, not
 * repeated; another body gets a new key, and success forgets it. The second request
 * after a withdrawal is another attempt, so it never reuses the first one's key (that
 * would replay the withdrawn case). The hook lives in OrderingPage, which stays
 * mounted while another workspace is shown, so the keys survive a switch.
 */
export function useAfterSales(
  user: CurrentUser | null,
  { shown = true, onChanged }: { shown?: boolean; onChanged?: () => void } = {},
) {
  const overview = useLiveResource<OverviewKey, AfterSalesOverview>(
    user ? "customer" : "guest",
    fetchOverview,
    "Unable to load your refund requests.",
  );
  const { reload, applyAndReload } = overview;
  const data = overview.data ?? EMPTY_OVERVIEW;
  const [pending, setPending] = useState<ReadonlyMap<string, AfterSalesCommand>>(new Map());
  const [errors, setErrors] = useState<ReadonlyMap<string, string>>(new Map());
  const keysRef = useRef(new Map<string, { payload: string; key: string }>());

  usePolling(reload, {
    enabled: Boolean(user) && shown && data.cases.some(isLiveCase),
    intervalMs: AFTER_SALES_POLL_MS,
  });

  const run = useCallback(
    (
      target: string,
      command: AfterSalesCommand,
      slot: string,
      payload: string,
      send: (idempotencyKey: string) => Promise<AfterSalesCase>,
      failure: string,
    ): Promise<boolean> => {
      const stored = keysRef.current.get(slot);
      const key = stored?.payload === payload ? stored.key : createIdempotencyKey();
      keysRef.current.set(slot, { payload, key });
      setPending((current) => withEntry(current, target, command));
      setErrors((current) => withoutEntry(current, target));

      return send(key)
        .then((updated) => {
          keysRef.current.delete(slot);
          applyAndReload((current) => withCase(current, updated, command === "open"));
          onChanged?.();
          return true;
        })
        .catch((requestError: unknown) => {
          setErrors((current) => withEntry(current, target, errorCopy(requestError, failure)));
          // The case or the order moved on meanwhile (a deadline, the restaurant): show it.
          if (
            requestError instanceof ApiError &&
            (requestError.status === 404 || requestError.status === 409)
          ) {
            reload();
          }
          return false;
        })
        .finally(() => {
          setPending((current) => withoutEntry(current, target));
        });
    },
    [applyAndReload, reload, onChanged],
  );

  const open = useCallback(
    (order: Order, attemptNo: number, draft: AfterSalesDraft) => {
      const body = openRequestOf(draft);
      return run(
        orderTarget(order.id),
        "open",
        `open:${order.id}:${attemptNo}`,
        JSON.stringify(body),
        (key) => openAfterSalesCase(order.id, body, key),
        `Unable to request a refund for ${orderLabel(order)}.`,
      );
    },
    [run],
  );

  const caseCommand = useCallback(
    (
      afterSalesCase: AfterSalesCase,
      command: Exclude<AfterSalesCommand, "open">,
      payload: string,
      send: (idempotencyKey: string) => Promise<AfterSalesCase>,
      failure: string,
    ) =>
      run(
        caseTarget(afterSalesCase.id),
        command,
        `${command}:${afterSalesCase.id}`,
        payload,
        send,
        failure,
      ),
    [run],
  );

  const acceptOffer = useCallback(
    (afterSalesCase: AfterSalesCase) =>
      caseCommand(
        afterSalesCase,
        "accept-offer",
        "",
        (key) => acceptAfterSalesOffer(afterSalesCase.id, key),
        "Unable to accept the offer.",
      ),
    [caseCommand],
  );

  const declineOffer = useCallback(
    (afterSalesCase: AfterSalesCase) =>
      caseCommand(
        afterSalesCase,
        "decline-offer",
        "",
        (key) => declineAfterSalesOffer(afterSalesCase.id, key),
        "Unable to decline the offer.",
      ),
    [caseCommand],
  );

  const escalate = useCallback(
    (afterSalesCase: AfterSalesCase, note: string) => {
      const trimmed = note.trim() || null;
      return caseCommand(
        afterSalesCase,
        "escalate",
        JSON.stringify({ note: trimmed }),
        (key) => escalateAfterSalesCase(afterSalesCase.id, trimmed, key),
        "Unable to ask the platform to step in.",
      );
    },
    [caseCommand],
  );

  const withdraw = useCallback(
    (afterSalesCase: AfterSalesCase) =>
      caseCommand(
        afterSalesCase,
        "withdraw",
        "",
        (key) => withdrawAfterSalesCase(afterSalesCase.id, key),
        "Unable to withdraw the refund request.",
      ),
    [caseCommand],
  );

  return {
    cases: data.cases,
    eligibleOrders: data.eligible_orders,
    loaded: overview.data !== null,
    loading: overview.loading,
    error: overview.error,
    reload,
    /** The command running per target (`orderTarget` / `caseTarget`). */
    pending,
    /** The last command error per target, cleared when the next command starts. */
    errors,
    open,
    acceptOffer,
    declineOffer,
    escalate,
    withdraw,
  };
}

export type AfterSalesState = ReturnType<typeof useAfterSales>;

interface DetailResult {
  key: string;
  detail: AfterSalesCase | null;
  error: string;
}

/**
 * GET /after-sales/cases/{id}: one case with its history, for its history toggle. It
 * loads on mount, again when the case moves (`version` changes), and on `reload()`; a
 * superseded response is dropped, and the earlier history stays on screen meanwhile.
 */
export function useAfterSalesCaseDetail(caseId: number, version: string) {
  const [reloads, setReloads] = useState(0);
  const [result, setResult] = useState<DetailResult | null>(null);
  const nextDetailSignal = useLatestRequest();
  const key = `${caseId}:${version}:${reloads}`;

  useEffect(() => {
    const signal = nextDetailSignal();
    getAfterSalesCase(caseId, { signal })
      .then((detail) => {
        if (!signal.aborted) {
          setResult({ key, detail, error: "" });
        }
      })
      .catch((requestError: unknown) => {
        if (!signal.aborted && !isAbortError(requestError)) {
          setResult((current) => ({
            key,
            detail: current?.detail ?? null,
            error: errorCopy(requestError, "Unable to load the refund history."),
          }));
        }
      });
  }, [key, caseId, nextDetailSignal]);

  const reload = useCallback(() => setReloads((count) => count + 1), []);

  return {
    history: result?.detail?.history ?? [],
    error: result?.error ?? "",
    loading: result?.key !== key,
    reload,
  };
}
