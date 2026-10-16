import type { Restaurant } from "../api/dto";
import type { CartState } from "../hooks/useCart";
import { ConfirmDialog } from "./ConfirmDialog";

/**
 * Shown when an add was refused with 409 CART_RESTAURANT_CONFLICT: offers to clear
 * the cart (DELETE /cart) and add the item again.
 */
export function CartConflictDialog({
  cart,
  restaurants,
}: {
  cart: CartState;
  restaurants: readonly Restaurant[];
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
      error={cart.conflictError}
      onConfirm={() => void cart.resolveConflict()}
      onCancel={cart.dismissConflict}
    >
      <p>
        Your cart has items from <strong>{cartRestaurant?.name ?? "another restaurant"}</strong>. An
        order goes to one restaurant, so clear the cart and add{" "}
        <strong>{conflict.item.name}</strong>?
      </p>
    </ConfirmDialog>
  );
}
