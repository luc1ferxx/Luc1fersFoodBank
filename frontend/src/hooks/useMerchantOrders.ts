import { useCallback, useState } from "react";
import { ApiError } from "../api/client";
import type { MerchantOrder, OrderStatus } from "../api/dto";
import {
  acceptMerchantOrder,
  getMerchantOrders,
  markMerchantOrderPreparing,
  rejectMerchantOrder,
} from "../api/endpoints";
import { errorCopy } from "../lib/format";
import { orderLabel } from "../lib/labels";
import { withEntry, withoutEntry } from "../lib/maps";
import { useLiveResource } from "./useLiveResource";
import { usePolling } from "./usePolling";

export const MERCHANT_POLL_MS = 15000;

const FORBIDDEN = 403;

export type MerchantStatusFilter = "ALL" | OrderStatus;
export type MerchantCommand = "accept" | "reject" | "preparing";

const COMMAND_COPY: Readonly<Record<MerchantCommand, { done: string; failed: string }>> = {
  accept: { done: "accepted", failed: "accept" },
  reject: { done: "rejected", failed: "reject" },
  preparing: { done: "marked as preparing", failed: "mark as preparing" },
};

const fetchMerchantOrders = (filter: MerchantStatusFilter, signal: AbortSignal) =>
  getMerchantOrders({ status: filter === "ALL" ? undefined : filter, signal });

/**
 * Where the count of orders waiting for acceptance comes from: the list itself when its
 * filter shows every PAID order, otherwise a read of the PAID orders of its own.
 */
type WaitingSource = "list" | "PAID";

const NO_ORDERS: readonly MerchantOrder[] = [];

const fetchWaiting = (
  source: WaitingSource,
  signal: AbortSignal,
): Promise<readonly MerchantOrder[]> =>
  source === "PAID" ? getMerchantOrders({ status: "PAID", signal }) : Promise.resolve(NO_ORDERS);

const isWaiting = (order: MerchantOrder) => order.status === "PAID";

const sendCommand = (orderId: number, command: MerchantCommand, reason: string) => {
  switch (command) {
    case "accept":
      return acceptMerchantOrder(orderId);
    case "reject":
      return rejectMerchantOrder(orderId, reason);
    case "preparing":
      return markMerchantOrderPreparing(orderId);
  }
};

/**
 * The merchant console's orders: GET /merchant/orders for the chosen status filter,
 * polled every 15 seconds while the tab is visible, plus accept / reject / preparing.
 * `waiting` counts the PAID orders, which the accept timeout cancels when nobody
 * answers: from the list while the filter shows them all (ALL or PAID), from a PAID
 * read polled with the list otherwise.
 *
 * The commands are idempotent on the server (a repeat returns the order unchanged),
 * so a retry needs no Idempotency-Key. A command's result replaces the order on
 * screen at once and drops any list read that started before it; a 404 or 409 (the
 * order moved on meanwhile, e.g. the customer cancelled) re-reads the list.
 *
 * The backend re-checks the merchant account on every /merchant/** request and
 * answers 403 when it is deactivated (or not bound to a restaurant). A list read
 * that comes back 403 sets `forbidden`: polling stops, the raw 403 gives way to the
 * console's notice, and Refresh checks again. A command's 403 re-reads the list.
 */
export function useMerchantOrders() {
  const [filter, setFilter] = useState<MerchantStatusFilter>("ALL");
  const [polling, setPolling] = useState(true);
  const [pending, setPending] = useState<ReadonlyMap<number, MerchantCommand>>(new Map());
  const [commandErrors, setCommandErrors] = useState<ReadonlyMap<number, string>>(new Map());
  const [notice, setNotice] = useState("");
  const orders = useLiveResource(filter, fetchMerchantOrders, "Unable to load the orders.");
  const { reload: reloadList, applyAndReload } = orders;
  const forbidden = orders.errorStatus === FORBIDDEN;
  const waitingSource: WaitingSource = filter === "ALL" || filter === "PAID" ? "list" : "PAID";
  const waitingOrders = useLiveResource(
    waitingSource,
    fetchWaiting,
    "Unable to count the new orders.",
  );
  const reloadWaiting = waitingOrders.reload;
  const reload = useCallback(() => {
    reloadList();
    if (waitingSource === "PAID") {
      reloadWaiting();
    }
  }, [reloadList, reloadWaiting, waitingSource]);

  // Polling a 403 would only repeat it; Refresh checks again.
  usePolling(reload, { enabled: polling && !forbidden, intervalMs: MERCHANT_POLL_MS });

  const waitingList = waitingSource === "list" ? orders.data : waitingOrders.data;
  const waiting = forbidden || waitingList === null ? null : waitingList.filter(isWaiting).length;

  const run = useCallback(
    (order: MerchantOrder, command: MerchantCommand, reason = ""): Promise<boolean> => {
      const orderId = order.order_id;
      const label = orderLabel({ id: orderId, order_no: order.order_no });
      setPending((current) => withEntry(current, orderId, command));
      setCommandErrors((current) => withoutEntry(current, orderId));
      setNotice("");

      return sendCommand(orderId, command, reason)
        .then((updated) => {
          applyAndReload((list) =>
            list.map((item) => (item.order_id === updated.order_id ? updated : item)),
          );
          setNotice(`${label} ${COMMAND_COPY[command].done}.`);
          return true;
        })
        .catch((requestError: unknown) => {
          setCommandErrors((current) =>
            withEntry(
              current,
              orderId,
              errorCopy(requestError, `Unable to ${COMMAND_COPY[command].failed} ${label}.`),
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
          setPending((current) => withoutEntry(current, orderId));
        });
    },
    [applyAndReload, reload],
  );

  const accept = useCallback((order: MerchantOrder) => run(order, "accept"), [run]);
  const markPreparing = useCallback((order: MerchantOrder) => run(order, "preparing"), [run]);
  const reject = useCallback(
    (order: MerchantOrder, reason: string) => run(order, "reject", reason),
    [run],
  );

  return {
    filter,
    setFilter,
    orders: orders.data ?? [],
    loaded: orders.data !== null,
    loading: orders.loading,
    /** The list read came back 403: the account is deactivated (or not bound). */
    forbidden,
    /** How many orders wait for acceptance (PAID); null before it is known and while forbidden. */
    waiting,
    error: forbidden ? "" : orders.error,
    reload,
    polling,
    setPolling,
    pending,
    commandErrors,
    notice,
    accept,
    reject,
    markPreparing,
  };
}

export type MerchantOrdersState = ReturnType<typeof useMerchantOrders>;
