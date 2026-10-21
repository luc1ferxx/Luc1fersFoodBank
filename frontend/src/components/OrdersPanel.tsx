import { type ReactNode, useId, useState } from "react";
import type { AfterSalesCase, EligibleOrder, Order } from "../api/dto";
import { type AfterSalesState, caseTarget, orderTarget } from "../hooks/useAfterSales";
import { CANCELLABLE_STATUSES, type OrdersState } from "../hooks/useOrders";
import { formatDateTime, formatMoney } from "../lib/format";
import { orderLabel } from "../lib/labels";
import { AfterSalesCaseView, AutomaticRefund } from "./AfterSalesCaseView";
import { AfterSalesRequestForm } from "./AfterSalesRequestForm";
import { Alert } from "./Alert";
import { OrderHistory } from "./OrderHistory";

/**
 * The after-sales part of an order card: the automatic refund of a cancelled paid
 * order, the customer's refund requests (newest first), and "Request a refund" while
 * the order is open to one (`eligible`), the second time with "1 left".
 */
function OrderAfterSales({
  order,
  cases,
  eligible,
  afterSales,
}: {
  order: Order;
  cases: AfterSalesCase[];
  eligible: EligibleOrder | undefined;
  afterSales: AfterSalesState;
}) {
  const [requesting, setRequesting] = useState(false);
  const label = orderLabel(order);
  const target = orderTarget(order.id);

  return (
    <>
      {cases.map((afterSalesCase) => {
        if (afterSalesCase.kind === "ORDER_CANCELLED") {
          return <AutomaticRefund key={afterSalesCase.id} afterSalesCase={afterSalesCase} />;
        }
        const caseKey = caseTarget(afterSalesCase.id);
        return (
          <AfterSalesCaseView
            key={afterSalesCase.id}
            afterSalesCase={afterSalesCase}
            label={label}
            pending={afterSales.pending.get(caseKey)}
            error={afterSales.errors.get(caseKey) ?? ""}
            onAcceptOffer={() => void afterSales.acceptOffer(afterSalesCase)}
            onDeclineOffer={() => void afterSales.declineOffer(afterSalesCase)}
            onEscalate={(note) => afterSales.escalate(afterSalesCase, note)}
            onWithdraw={() => afterSales.withdraw(afterSalesCase)}
          />
        );
      })}
      {eligible && requesting ? (
        <AfterSalesRequestForm
          order={order}
          attemptNo={eligible.attempt_no}
          pending={afterSales.pending.has(target)}
          error={afterSales.errors.get(target) ?? ""}
          onSubmit={(draft) => {
            void afterSales.open(order, eligible.attempt_no, draft).then((done) => {
              if (done) {
                setRequesting(false);
              }
            });
          }}
          onCancel={() => setRequesting(false)}
        />
      ) : null}
      {eligible && !requesting ? (
        <div className="after-sales__actions">
          <button className="ghost-button" type="button" onClick={() => setRequesting(true)}>
            {eligible.attempt_no > 1 ? "Request a refund again (1 left)" : "Request a refund"}
          </button>
          <span className="quiet-copy">Until {formatDateTime(eligible.request_deadline)}</span>
        </div>
      ) : null}
    </>
  );
}

function OrderCard({
  order,
  canceling,
  onCancel,
  children,
}: {
  order: Order;
  canceling: boolean;
  onCancel: (order: Order) => Promise<void>;
  children: ReactNode;
}) {
  const cancellable = CANCELLABLE_STATUSES.has(order.status);
  const [showHistory, setShowHistory] = useState(false);
  const historyId = useId();

  return (
    <article className="order-card">
      <div className="order-card__top">
        <div>
          <p className="section-kicker">{orderLabel(order)}</p>
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
        <OrderHistory
          id={historyId}
          orderId={order.id}
          label={orderLabel(order)}
          status={order.status}
        />
      ) : null}
      {children}
    </article>
  );
}

export function OrdersPanel({
  signedIn,
  orders,
  afterSales,
}: {
  signedIn: boolean;
  orders: OrdersState;
  afterSales: AfterSalesState;
}) {
  return (
    <section className="panel-body" aria-label="Order history">
      <div className="panel-heading">
        <p className="section-kicker">Order history</p>
        <h2>Newest first from /orders</h2>
        <button
          className="ghost-button"
          type="button"
          disabled={!signedIn || orders.loading}
          onClick={() => {
            void orders.reload();
            afterSales.reload();
          }}
        >
          Refresh
        </button>
      </div>
      {!signedIn ? (
        <div className="empty-state">Sign in to view order history.</div>
      ) : (
        <>
          <Alert tone="danger">{orders.error}</Alert>
          <Alert tone="danger">{afterSales.error}</Alert>
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
                >
                  <OrderAfterSales
                    order={order}
                    cases={afterSales.cases.filter((item) => item.order_id === order.id)}
                    eligible={afterSales.eligibleOrders.find((item) => item.order_id === order.id)}
                    afterSales={afterSales}
                  />
                </OrderCard>
              ))}
            </div>
          )}
        </>
      )}
    </section>
  );
}
