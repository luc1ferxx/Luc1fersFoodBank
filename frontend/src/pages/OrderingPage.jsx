import { useCallback } from "react";
import { AuthPanel } from "../components/AuthPanel";
import { ErrorBoundary } from "../components/ErrorBoundary";
import { OperationsPanel } from "../components/OperationsPanel";
import { RestaurantBrowser } from "../components/RestaurantBrowser";
import { useAdminTools } from "../hooks/useAdminTools";
import { useCart } from "../hooks/useCart";
import { useCheckout } from "../hooks/useCheckout";
import { useNotifications } from "../hooks/useNotifications";
import { useOrders } from "../hooks/useOrders";

/**
 * Everything that belongs to one session. App renders it with key={user}, so
 * signing in, signing out, or an expired session remounts it with empty cart,
 * order, notification, and checkout state instead of resetting each piece by hand.
 */
export function OrderingPage({
  session,
  menu,
  searchValue,
  onSearchChange,
  activePanel,
  onPanelChange,
}) {
  const { user, requireSession } = session;
  const signedIn = Boolean(user);

  const cart = useCart(user, requireSession);
  const notifications = useNotifications(user);
  const { load: loadNotifications } = notifications;
  const orders = useOrders(user, { onOrderChanged: loadNotifications });
  const { load: loadOrders } = orders;

  const refreshOrderViews = useCallback(() => {
    loadOrders();
    loadNotifications();
  }, [loadOrders, loadNotifications]);

  const checkout = useCheckout({
    user,
    cart: cart.cart,
    clearCart: cart.clear,
    onCheckedOut: refreshOrderViews,
    requireSession,
  });
  const admin = useAdminTools({ onOrderChanged: refreshOrderViews });

  return (
    <main className="mvp-grid">
      <ErrorBoundary label="The menu">
        <RestaurantBrowser
          menu={menu}
          searchValue={searchValue}
          onSearchChange={onSearchChange}
          signedIn={signedIn}
          cart={cart}
        />
      </ErrorBoundary>

      <aside className="side-workbench" aria-label="Account and order tools">
        <AuthPanel session={session} />
        <OperationsPanel
          activePanel={activePanel}
          onPanelChange={onPanelChange}
          signedIn={signedIn}
          cart={cart}
          checkout={checkout}
          orders={orders}
          notifications={notifications}
          admin={admin}
        />
      </aside>
    </main>
  );
}
