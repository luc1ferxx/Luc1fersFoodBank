import type { Restaurant } from "../api/dto";
import type { CartState } from "../hooks/useCart";
import { ConfirmDialog } from "./ConfirmDialog";

/**
 * Shown when an add was refused with 409 CART_RESTAURANT_CONFLICT: offers to clear
 * the cart (DELETE /cart) and add the item again. Clearing waits while a checkout of
 * that cart is in flight, like the cart's own Clear cart button.
 */
export function CartConflictDialog({
  cart,
  restaurants,
  checkoutPending,
}: {
  cart: CartState;
  restaurants: readonly Restaurant[];
  checkoutPending: boolean;
}) {
  const { conflict } = cart;

  if (!conflict) {
    return null;
  }

  const cartRestaurant = restaurants.find((restaurant) => restaurant.id === cart.cartRestaurantId);

  return (
    <ConfirmDialog
      title="Start a new cart?"
      confirmLabel="Clear cart & add"
      pendingLabel="Clearing cart..."
      pending={cart.conflictPending}
      confirmDisabled={checkoutPending}
      error={cart.conflictError}
      onConfirm={() => {
        if (!checkoutPending) {
          void cart.resolveConflict();
        }
      }}
      onCancel={cart.dismissConflict}
    >
      <p>
        Your cart has items from <strong>{cartRestaurant?.name ?? "another restaurant"}</strong>. An
        order goes to one restaurant, so clear the cart and add{" "}
        <strong>{conflict.item.name}</strong>?
      </p>
      {checkoutPending ? <p>Your checkout is still running. Wait for it to finish.</p> : null}
    </ConfirmDialog>
  );
}
