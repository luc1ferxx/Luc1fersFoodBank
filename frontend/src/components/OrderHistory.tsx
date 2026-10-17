import type { OrderStatus } from "../api/dto";
import { useOrderHistory } from "../hooks/useOrderHistory";
import { formatDateTime } from "../lib/format";
import { ACTOR_LABELS } from "../lib/labels";
import { Alert } from "./Alert";

/**
 * The status changes of one order, oldest first. `orderId` is the internal id the API
 * takes; `label` names the order for people ("Order #<order number>").
 */
export function OrderHistory({
  id,
  orderId,
  label,
  status,
}: {
  id: string;
  orderId: number;
  label: string;
  status: OrderStatus;
}) {
  const history = useOrderHistory(orderId, status);

  return (
    <section className="order-history" id={id} aria-label={`Status history of ${label}`}>
      <div className="order-history__heading">
        <h4>Status history</h4>
        <button
          className="ghost-button"
          type="button"
          disabled={history.loading}
          onClick={history.reload}
        >
          {history.loading ? "Loading..." : "Refresh history"}
        </button>
      </div>
      <Alert tone="danger">{history.error}</Alert>
      {!history.loading && !history.error && history.entries.length === 0 ? (
        <p className="quiet-copy">No status changes recorded yet.</p>
      ) : null}
      {history.entries.length ? (
        <ol className="history-list">
          {history.entries.map((entry, index) => (
            <li key={`${entry.occurred_at}:${entry.to_status}:${index}`}>
              <strong>
                {entry.from_status ? `${entry.from_status} → ${entry.to_status}` : entry.to_status}
              </strong>
              <span>by {ACTOR_LABELS[entry.actor_type]}</span>
              {entry.reason ? <span>Reason: {entry.reason}</span> : null}
              <time dateTime={entry.occurred_at}>{formatDateTime(entry.occurred_at)}</time>
            </li>
          ))}
        </ol>
      ) : null}
    </section>
  );
}
