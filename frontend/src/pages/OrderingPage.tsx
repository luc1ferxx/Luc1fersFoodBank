import { useCallback } from "react";
import { AuthPanel } from "../components/AuthPanel";
import { CartConflictDialog } from "../components/CartConflictDialog";
import { ErrorBoundary } from "../components/ErrorBoundary";
import { OperationsPanel, type OperationsTab } from "../components/OperationsPanel";
import { RestaurantBrowser } from "../components/RestaurantBrowser";
import { useCart } from "../hooks/useCart";
import { useCheckout } from "../hooks/useCheckout";
import type { MenuBrowser } from "../hooks/useMenu";
import { useNotifications } from "../hooks/useNotifications";
import { useOrders } from "../hooks/useOrders";
import type { Session } from "../hooks/useSession";

interface OrderingPageProps {
  session: Session;
  menu: MenuBrowser;
  searchValue: string;
  onSearchChange: (value: string) => void;
  activePanel: OperationsTab;
  onPanelChange: (panel: OperationsTab) => void;
}

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
}: OrderingPageProps) {
  const { user, requireSession } = session;
  const signedIn = Boolean(user);

  const cart = useCart(user, requireSession);
  const notifications = useNotifications(user);
  const { load: loadNotifications } = notifications;
  const refreshNotifications = useCallback(() => {
    void loadNotifications();
  }, [loadNotifications]);
  const orders = useOrders(user, { onOrderChanged: refreshNotifications });
  const { load: loadOrders } = orders;

  const refreshOrderViews = useCallback(() => {
    void loadOrders();
    void loadNotifications();
  }, [loadOrders, loadNotifications]);

  const checkout = useCheckout({
    user,
    cart: cart.cart,
    clearCart: cart.clear,
    onCheckedOut: refreshOrderViews,
    requireSession,
  });

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
        <CartConflictDialog cart={cart} restaurants={menu.restaurants} />
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
        />
      </aside>
    </main>
  );
}
