import type { NotificationsState } from "../hooks/useNotifications";
import { formatDateTime } from "../lib/format";
import { orderLabel } from "../lib/labels";
import { Alert } from "./Alert";

export function NotificationsPanel({
  signedIn,
  notifications,
}: {
  signedIn: boolean;
  notifications: NotificationsState;
}) {
  return (
    <section className="panel-body" aria-label="Order notifications">
      <div className="panel-heading">
        <p className="section-kicker">Notifications</p>
        <h2>Async order lifecycle updates</h2>
        <button
          className="ghost-button"
          type="button"
          disabled={!signedIn || notifications.loading}
          onClick={notifications.reload}
        >
          Refresh
        </button>
      </div>
      {!signedIn ? (
        <div className="empty-state">Sign in to view notifications.</div>
      ) : (
        <>
          <label className="toggle-row">
            <input
              type="checkbox"
              checked={notifications.polling}
              onChange={(event) => notifications.setPolling(event.target.checked)}
            />
            Auto-refresh
          </label>
          <Alert tone="danger">{notifications.error}</Alert>
          {notifications.loading ? (
            <div className="loading-row">Loading notifications...</div>
          ) : null}
          {notifications.notifications.length === 0 ? (
            <div className="empty-state">
              No notifications yet. They may lag checkout or status updates.
            </div>
          ) : (
            <div className="notification-list">
              {notifications.notifications.map((notification) => (
                <article className="notification-card" key={notification.id}>
                  <span>{notification.event_type}</span>
                  <h3>{notification.title}</h3>
                  <p>{notification.message}</p>
                  <small>
                    {orderLabel({ id: notification.order_id, order_no: notification.order_no })} ·{" "}
                    {formatDateTime(notification.created_at)}
                  </small>
                </article>
              ))}
            </div>
          )}
        </>
      )}
    </section>
  );
}
