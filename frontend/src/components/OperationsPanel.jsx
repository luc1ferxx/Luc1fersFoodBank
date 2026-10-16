import { AdminPanel } from "./AdminPanel";
import { CartPanel } from "./CartPanel";
import { ErrorBoundary } from "./ErrorBoundary";
import { NotificationsPanel } from "./NotificationsPanel";
import { OrdersPanel } from "./OrdersPanel";

export function OperationsPanel({
  activePanel,
  onPanelChange,
  signedIn,
  cart,
  checkout,
  orders,
  notifications,
  admin,
}) {
  const tabs = [
    ["cart", `Cart (${cart.itemCount})`],
    ["orders", "Orders"],
    ["notifications", `Notifications (${notifications.notifications.length})`],
    ["admin", "Admin tools"],
  ];

  return (
    <section className="operations-panel">
      <div className="panel-tabs" role="tablist" aria-label="Customer operations">
        {tabs.map(([key, label]) => (
          <button
            className={activePanel === key ? "active" : ""}
            key={key}
            type="button"
            onClick={() => onPanelChange(key)}
          >
            {label}
          </button>
        ))}
      </div>

      <ErrorBoundary key={activePanel} label="This panel">
        {activePanel === "cart" ? (
          <CartPanel signedIn={signedIn} cart={cart} checkout={checkout} />
        ) : null}
        {activePanel === "orders" ? <OrdersPanel signedIn={signedIn} orders={orders} /> : null}
        {activePanel === "notifications" ? (
          <NotificationsPanel signedIn={signedIn} notifications={notifications} />
        ) : null}
        {activePanel === "admin" ? <AdminPanel admin={admin} /> : null}
      </ErrorBoundary>
    </section>
  );
}
