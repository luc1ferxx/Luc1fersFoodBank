import { type FormEvent, useId, useState } from "react";
import { type CurrentUser, type MerchantOrder, ORDER_STATUSES } from "../api/dto";
import { Alert } from "../components/Alert";
import {
  type MerchantCommand,
  type MerchantStatusFilter,
  useMerchantOrders,
} from "../hooks/useMerchantOrders";
import { formatCurrency, formatDateTime } from "../lib/format";
import { ORDER_STATUS_LABELS } from "../lib/labels";

const REJECT_REASON_MAX = 200;

const FILTERS: readonly MerchantStatusFilter[] = ["ALL", ...ORDER_STATUSES];

const parseFilter = (value: string): MerchantStatusFilter =>
  FILTERS.find((filter) => filter === value) ?? "ALL";

const pendingCopy: Readonly<Record<MerchantCommand, string>> = {
  accept: "Accepting...",
  reject: "Rejecting...",
  preparing: "Updating...",
};

interface MerchantOrderCardProps {
  order: MerchantOrder;
  pending: MerchantCommand | undefined;
  /** The account cannot act (deactivated): the commands are shown but disabled. */
  locked: boolean;
  error: string;
  onAccept: (order: MerchantOrder) => Promise<boolean>;
  onReject: (order: MerchantOrder, reason: string) => Promise<boolean>;
  onPreparing: (order: MerchantOrder) => Promise<boolean>;
}

function MerchantOrderCard({
  order,
  pending,
  locked,
  error,
  onAccept,
  onReject,
  onPreparing,
}: MerchantOrderCardProps) {
  const [rejecting, setRejecting] = useState(false);
  const [reason, setReason] = useState("");
  const [reasonError, setReasonError] = useState("");
  const reasonId = useId();
  const busy = pending !== undefined || locked;

  const submitReject = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    const trimmed = reason.trim();
    if (!trimmed) {
      setReasonError("Give the customer a reason for the rejection.");
      return;
    }
    setReasonError("");
    if (await onReject(order, trimmed)) {
      setRejecting(false);
      setReason("");
    }
  };

  return (
    <article className="order-card console-card" aria-label={`Order #${order.order_id}`}>
      <div className="order-card__top">
        <div>
          <p className="section-kicker">Order #{order.order_id}</p>
          <h3>{formatCurrency(order.total_price)}</h3>
        </div>
        <span className={`status-badge status-badge--${order.status}`}>{order.status}</span>
      </div>
      <p>Paid {formatDateTime(order.paid_at)}</p>
      <ul>
        {order.items.map((item) => (
          <li key={item.menu_item_id}>
            {item.quantity} x {item.menu_item_name} · {formatCurrency(item.price * item.quantity)}
          </li>
        ))}
      </ul>
      {order.cancel_reason ? <p>Cancelled: {order.cancel_reason}</p> : null}
      <Alert tone="danger">{error}</Alert>

      {order.status === "PAID" && !rejecting ? (
        <div className="order-card__actions">
          <button
            className="primary-button"
            type="button"
            disabled={busy}
            onClick={() => onAccept(order)}
          >
            {pending === "accept" ? pendingCopy.accept : "Accept"}
          </button>
          <button
            className="ghost-button"
            type="button"
            disabled={busy}
            onClick={() => setRejecting(true)}
          >
            Reject
          </button>
        </div>
      ) : null}

      {order.status === "PAID" && rejecting ? (
        <form className="stack-form reject-form" onSubmit={submitReject} noValidate>
          <label htmlFor={reasonId}>Reason for rejecting order #{order.order_id}</label>
          <input
            id={reasonId}
            value={reason}
            maxLength={REJECT_REASON_MAX}
            placeholder="Out of stock, closing early..."
            disabled={busy}
            onChange={(event) => setReason(event.target.value)}
          />
          <Alert tone="danger">{reasonError}</Alert>
          <div className="order-card__actions">
            <button
              className="ghost-button"
              type="button"
              disabled={busy}
              onClick={() => {
                setRejecting(false);
                setReasonError("");
              }}
            >
              Back
            </button>
            <button className="primary-button" type="submit" disabled={busy}>
              {pending === "reject" ? pendingCopy.reject : "Reject order"}
            </button>
          </div>
        </form>
      ) : null}

      {order.status === "ACCEPTED" ? (
        <div className="order-card__actions">
          <button
            className="primary-button"
            type="button"
            disabled={busy}
            onClick={() => onPreparing(order)}
          >
            {pending === "preparing" ? pendingCopy.preparing : "Mark preparing"}
          </button>
        </div>
      ) : null}

      {order.status === "PREPARING" ? (
        <p className="quiet-copy">Waiting for a rider to pick it up.</p>
      ) : null}
    </article>
  );
}

/** ROLE_MERCHANT: the orders of the login's restaurant and the merchant decisions. */
export function MerchantConsole({ user }: { user: CurrentUser }) {
  const merchant = useMerchantOrders();
  const binding = user.merchant;
  const restaurantName = binding?.restaurant_name ?? "Your restaurant";
  // GET /me describes the account at sign-in; a 403 from /merchant/orders is newer, and
  // so is a list that loaded after an admin reactivated the account.
  const locked = merchant.forbidden || (!merchant.loaded && binding?.active === false);

  return (
    <main className="console">
      <section className="primary-workspace console__panel" aria-label="Merchant console">
        <div className="panel-heading">
          <div>
            <p className="section-kicker">Merchant console</p>
            <h2>{restaurantName} orders</h2>
          </div>
          <button
            className="ghost-button"
            type="button"
            disabled={merchant.loading}
            onClick={merchant.reload}
          >
            {merchant.loading ? "Refreshing..." : "Refresh"}
          </button>
        </div>

        {!binding ? (
          <Alert tone="neutral">
            This login is not bound to a restaurant yet. Ask an admin to provision it.
          </Alert>
        ) : null}
        {binding && locked ? (
          <Alert tone="neutral">
            This merchant account is deactivated. Ask an admin to reactivate it.
          </Alert>
        ) : null}

        <div className="console__toolbar">
          <label>
            Status
            <select
              value={merchant.filter}
              onChange={(event) => merchant.setFilter(parseFilter(event.target.value))}
            >
              {FILTERS.map((filter) => (
                <option key={filter} value={filter}>
                  {filter === "ALL" ? "All orders" : ORDER_STATUS_LABELS[filter]}
                </option>
              ))}
            </select>
          </label>
          <label className="toggle-row">
            <input
              type="checkbox"
              checked={merchant.polling && !merchant.forbidden}
              disabled={merchant.forbidden}
              onChange={(event) => merchant.setPolling(event.target.checked)}
            />
            Refresh every 15 seconds
          </label>
        </div>

        <Alert tone="danger">{merchant.error}</Alert>
        <Alert tone="success">{merchant.notice}</Alert>

        {!merchant.loaded ? (
          merchant.error || merchant.forbidden || locked ? null : (
            <div className="loading-row">Loading orders...</div>
          )
        ) : merchant.orders.length === 0 ? (
          <div className="empty-state">
            {merchant.filter === "ALL"
              ? "No orders yet. Paid orders show up here."
              : `No ${ORDER_STATUS_LABELS[merchant.filter].toLowerCase()} orders.`}
          </div>
        ) : (
          <div className="order-list console__list">
            {merchant.orders.map((order) => (
              <MerchantOrderCard
                key={order.order_id}
                order={order}
                pending={merchant.pending.get(order.order_id)}
                locked={locked}
                error={merchant.commandErrors.get(order.order_id) ?? ""}
                onAccept={merchant.accept}
                onReject={merchant.reject}
                onPreparing={merchant.markPreparing}
              />
            ))}
          </div>
        )}
      </section>
    </main>
  );
}
