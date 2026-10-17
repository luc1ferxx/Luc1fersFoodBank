import { useId, useState } from "react";
import type { Order } from "../api/dto";
import { CANCELLABLE_STATUSES, type OrdersState } from "../hooks/useOrders";
import { formatDateTime, formatMoney } from "../lib/format";
import { Alert } from "./Alert";
import { OrderHistory } from "./OrderHistory";

function OrderCard({
  order,
  canceling,
  onCancel,
}: {
  order: Order;
  canceling: boolean;
  onCancel: (order: Order) => Promise<void>;
}) {
  const cancellable = CANCELLABLE_STATUSES.has(order.status);
  const [showHistory, setShowHistory] = useState(false);
  const historyId = useId();

  return (
    <article className="order-card">
      <div className="order-card__top">
        <div>
          <p className="section-kicker">Order #{order.id}</p>
          <h3>{formatMoney(order.total_price, order.currency)}</h3>
        </div>
        <span className={`status-badge status-badge--${order.status}`}>{order.status}</span>
      </div>
      <p>{formatDateTime(order.created_at)}</p>
      <ul>
        {order.items.map((item) => (
          <li key={item.id}>
            {item.quantity} x {item.menu_item_name}
          </li>
        ))}
      </ul>
      {order.cancel_reason ? <p>Cancelled: {order.cancel_reason}</p> : null}
      <div className="order-card__actions">
        <button
          className="ghost-button"
          type="button"
          disabled={!cancellable || canceling}
          onClick={() => onCancel(order)}
        >
          {canceling ? "Cancelling..." : cancellable ? "Cancel order" : "Cancel unavailable"}
        </button>
        <button
          className="ghost-button"
          type="button"
          aria-expanded={showHistory}
          aria-controls={showHistory ? historyId : undefined}
          onClick={() => setShowHistory((shown) => !shown)}
        >
          {showHistory ? "Hide status history" : "Status history"}
        </button>
      </div>
      {showHistory ? (
        <OrderHistory id={historyId} orderId={order.id} status={order.status} />
      ) : null}
    </article>
  );
}

export function OrdersPanel({ signedIn, orders }: { signedIn: boolean; orders: OrdersState }) {
  return (
    <section className="panel-body" aria-label="Order history">
      <div className="panel-heading">
        <p className="section-kicker">Order history</p>
        <h2>Newest first from /orders</h2>
        <button
          className="ghost-button"
          type="button"
          disabled={!signedIn || orders.loading}
          onClick={orders.reload}
        >
          Refresh
        </button>
      </div>
      {!signedIn ? (
        <div className="empty-state">Sign in to view order history.</div>
      ) : (
        <>
          <Alert tone="danger">{orders.error}</Alert>
          {orders.loading ? <div className="loading-row">Loading orders...</div> : null}
          {orders.orders.length === 0 ? (
            <div className="empty-state">No orders have been placed yet.</div>
          ) : (
            <div className="order-list">
              {orders.orders.map((order) => (
                <OrderCard
                  key={order.id}
                  order={order}
                  canceling={orders.cancelingOrderId === order.id}
                  onCancel={orders.cancel}
                />
              ))}
            </div>
          )}
        </>
      )}
    </section>
  );
}
