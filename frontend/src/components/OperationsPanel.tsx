import type { CartState } from "../hooks/useCart";
import type { CheckoutState } from "../hooks/useCheckout";
import type { NotificationsState } from "../hooks/useNotifications";
import type { OrdersState } from "../hooks/useOrders";
import { CartPanel } from "./CartPanel";
import { ErrorBoundary } from "./ErrorBoundary";
import { NotificationsPanel } from "./NotificationsPanel";
import { OrdersPanel } from "./OrdersPanel";

export type OperationsTab = "cart" | "orders" | "notifications";

interface OperationsPanelProps {
  activePanel: OperationsTab;
  onPanelChange: (panel: OperationsTab) => void;
  signedIn: boolean;
  cart: CartState;
  checkout: CheckoutState;
  orders: OrdersState;
  notifications: NotificationsState;
}

export function OperationsPanel({
  activePanel,
  onPanelChange,
  signedIn,
  cart,
  checkout,
  orders,
  notifications,
}: OperationsPanelProps) {
  const tabs: [OperationsTab, string][] = [
    ["cart", `Cart (${cart.itemCount})`],
    ["orders", "Orders"],
    ["notifications", `Notifications (${notifications.notifications.length})`],
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
      </ErrorBoundary>
    </section>
  );
}
