import type { RiderDelivery } from "../api/dto";
import { Alert } from "../components/Alert";
import {
  type RiderCommand,
  type RiderDeliveryFilter,
  useRiderConsole,
} from "../hooks/useRiderConsole";
import { formatDateTime } from "../lib/format";
import { DELIVERY_STATUS_LABELS } from "../lib/labels";

const FILTERS: readonly { value: RiderDeliveryFilter; label: string }[] = [
  { value: "ACTIVE", label: "Active (assigned or picked up)" },
  { value: "ASSIGNED", label: "Assigned" },
  { value: "PICKED_UP", label: "Picked up" },
  { value: "DELIVERED", label: "Delivered" },
];

const parseFilter = (value: string): RiderDeliveryFilter =>
  FILTERS.find((filter) => filter.value === value)?.value ?? "ACTIVE";

interface DeliveryCardProps {
  delivery: RiderDelivery;
  pending: RiderCommand | undefined;
  error: string;
  onPickedUp: (delivery: RiderDelivery) => Promise<boolean>;
  onDelivered: (delivery: RiderDelivery) => Promise<boolean>;
}

function DeliveryCard({ delivery, pending, error, onPickedUp, onDelivered }: DeliveryCardProps) {
  const busy = pending !== undefined;

  return (
    <article className="order-card console-card" aria-label={`Delivery #${delivery.delivery_id}`}>
      <div className="order-card__top">
        <div>
          <p className="section-kicker">Delivery #{delivery.delivery_id}</p>
          <h3>Order #{delivery.order_id}</h3>
        </div>
        <span className={`status-badge status-badge--${delivery.status}`}>
          {DELIVERY_STATUS_LABELS[delivery.status]}
        </span>
      </div>
      <p>Pick up at {delivery.restaurant_name ?? `restaurant #${delivery.restaurant_id}`}</p>
      <ul className="timeline">
        <li>Assigned {formatDateTime(delivery.assigned_at)}</li>
        {delivery.picked_up_at ? <li>Picked up {formatDateTime(delivery.picked_up_at)}</li> : null}
        {delivery.delivered_at ? <li>Delivered {formatDateTime(delivery.delivered_at)}</li> : null}
      </ul>
      <Alert tone="danger">{error}</Alert>
      {delivery.status === "ASSIGNED" ? (
        <div className="order-card__actions">
          <button
            className="primary-button"
            type="button"
            disabled={busy}
            onClick={() => onPickedUp(delivery)}
          >
            {pending === "picked-up" ? "Updating..." : "Picked up"}
          </button>
        </div>
      ) : null}
      {delivery.status === "PICKED_UP" ? (
        <div className="order-card__actions">
          <button
            className="primary-button"
            type="button"
            disabled={busy}
            onClick={() => onDelivered(delivery)}
          >
            {pending === "delivered" ? "Updating..." : "Delivered"}
          </button>
        </div>
      ) : null}
    </article>
  );
}

/** ROLE_RIDER: the on-duty switch and the rider's own deliveries. */
export function RiderConsole() {
  const rider = useRiderConsole();
  const { profile } = rider;

  return (
    <main className="console console--split">
      <section className="auth-panel console__side" aria-label="Rider status">
        <div className="panel-heading panel-heading--compact">
          <p className="section-kicker">Rider console</p>
          <h2>{profile?.name ?? "Your shift"}</h2>
        </div>
        <Alert tone="danger">{rider.profileError}</Alert>
        {profile ? (
          <>
            {!profile.active ? (
              <Alert tone="neutral">
                This rider account is deactivated. Ask an admin to reactivate it.
              </Alert>
            ) : null}
            <label className="toggle-row duty-switch">
              <input
                type="checkbox"
                role="switch"
                checked={profile.on_duty}
                disabled={rider.availabilityPending || !profile.active}
                onChange={(event) => void rider.setOnDuty(event.target.checked)}
              />
              On duty
            </label>
            <p className="quiet-copy">
              {profile.on_duty
                ? "You can be assigned new deliveries."
                : "Go on duty to receive deliveries."}
            </p>
            <Alert tone="danger">{rider.availabilityError}</Alert>
            <div className="meta-row">
              <span>{profile.phone ?? "No phone on file"}</span>
              <span>{profile.active_deliveries} active deliveries</span>
            </div>
          </>
        ) : rider.profileError ? null : (
          <div className="loading-row">Loading your profile...</div>
        )}
      </section>

      <section className="primary-workspace console__panel" aria-label="My deliveries">
        <div className="panel-heading">
          <div>
            <p className="section-kicker">My deliveries</p>
            <h2>Pick up and deliver</h2>
          </div>
          <button
            className="ghost-button"
            type="button"
            disabled={rider.deliveriesLoading}
            onClick={rider.reload}
          >
            {rider.deliveriesLoading ? "Refreshing..." : "Refresh"}
          </button>
        </div>

        <div className="console__toolbar">
          <label>
            Show
            <select
              value={rider.filter}
              onChange={(event) => rider.setFilter(parseFilter(event.target.value))}
            >
              {FILTERS.map((filter) => (
                <option key={filter.value} value={filter.value}>
                  {filter.label}
                </option>
              ))}
            </select>
          </label>
          <label className="toggle-row">
            <input
              type="checkbox"
              checked={rider.polling}
              onChange={(event) => rider.setPolling(event.target.checked)}
            />
            Refresh every 15 seconds
          </label>
        </div>

        <Alert tone="danger">{rider.deliveriesError}</Alert>
        <Alert tone="success">{rider.notice}</Alert>

        {!rider.deliveriesLoaded ? (
          rider.deliveriesError ? null : (
            <div className="loading-row">Loading deliveries...</div>
          )
        ) : rider.deliveries.length === 0 ? (
          <div className="empty-state">
            {rider.filter === "ACTIVE"
              ? "No active deliveries. New assignments show up here."
              : "No deliveries with this status."}
          </div>
        ) : (
          <div className="order-list console__list">
            {rider.deliveries.map((delivery) => (
              <DeliveryCard
                key={delivery.delivery_id}
                delivery={delivery}
                pending={rider.pending.get(delivery.delivery_id)}
                error={rider.commandErrors.get(delivery.delivery_id) ?? ""}
                onPickedUp={rider.markPickedUp}
                onDelivered={rider.markDelivered}
              />
            ))}
          </div>
        )}
      </section>
    </main>
  );
}
