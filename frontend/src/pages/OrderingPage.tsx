import { useCallback, useEffect, useRef } from "react";
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
  /** False while another workspace is shown; the page stays mounted, hidden. */
  active: boolean;
}

/**
 * Everything that belongs to one session. App renders it inside a shell keyed by
 * the user, so signing in, signing out, or an expired session remounts it with empty
 * cart, order, notification, and checkout state instead of resetting each piece by
 * hand. Switching to another workspace only hides it: the checkout and cancel
 * Idempotency-Keys of a failed attempt must survive for the retry.
 */
export function OrderingPage({
  session,
  menu,
  searchValue,
  onSearchChange,
  activePanel,
  onPanelChange,
  active,
}: OrderingPageProps) {
  const { user, requireSession } = session;
  const signedIn = Boolean(user);

  const cart = useCart(user, requireSession);
  const { refresh: refreshCart } = cart;
  const notifications = useNotifications(user, { shown: active });
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

  // Shown again after another workspace: the data may have moved on meanwhile (an
  // admin changed an order, say), so read it again. The retry keys are untouched.
  const wasActive = useRef(active);
  useEffect(() => {
    if (active && !wasActive.current && user) {
      void refreshCart();
      void loadOrders();
      void loadNotifications();
    }
    wasActive.current = active;
  }, [active, user, refreshCart, loadOrders, loadNotifications]);

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
        <CartConflictDialog
          cart={cart}
          restaurants={menu.restaurants}
          checkoutPending={checkout.pending}
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
        />
      </aside>
    </main>
  );
}
