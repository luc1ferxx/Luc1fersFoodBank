import { useCallback, useEffect, useMemo, useState } from "react";
import { hasErrorCode, isAbortError } from "../api/client";
import { type Cart, type CartItem, type CurrentUser, ERROR_CODES, type MenuItem } from "../api/dto";
import {
  addItemToCart,
  clearCart,
  getCart,
  removeCartItem,
  updateCartItem,
} from "../api/endpoints";
import { EMPTY_CART, buildCartItemMap, countCartItems, errorCopy } from "../lib/format";
import { useLatestRequest } from "./useLatestRequest";

/** An add the backend refused because the cart holds another restaurant's items. */
export interface CartConflict {
  item: MenuItem;
}

/**
 * The signed-in user's cart. Every mutation re-reads the cart from the server, and
 * only the newest read may update the screen.
 *
 * A cart holds one restaurant's items. When an add comes back 409
 * CART_RESTAURANT_CONFLICT the hook exposes `conflict` instead of an error, so the UI
 * can offer to clear the cart and add the item: `resolveConflict()` runs DELETE /cart
 * and then retries the add; `dismissConflict()` leaves the cart alone. While one
 * conflict is open, adds are refused and a second 409 never replaces it, so the
 * dialog's confirm always adds the item it names.
 */
export function useCart(user: CurrentUser | null, requireSession: (message: string) => void) {
  const [cart, setCart] = useState<Cart>(EMPTY_CART);
  const [error, setError] = useState("");
  const [pendingMenuItemId, setPendingMenuItemId] = useState<number | null>(null);
  const [pendingCartItemId, setPendingCartItemId] = useState<number | null>(null);
  const [clearing, setClearing] = useState(false);
  const [conflict, setConflict] = useState<CartConflict | null>(null);
  const [conflictPending, setConflictPending] = useState(false);
  const [conflictError, setConflictError] = useState("");
  const nextCartSignal = useLatestRequest();

  const refresh = useCallback(() => {
    const signal = nextCartSignal();
    return getCart({ signal })
      .then((data) => {
        if (!signal.aborted) {
          setCart(data);
          setError("");
        }
      })
      .catch((requestError: unknown) => {
        if (!signal.aborted && !isAbortError(requestError)) {
          setError(errorCopy(requestError, "Unable to load the cart."));
        }
      });
  }, [nextCartSignal]);

  useEffect(() => {
    if (user) {
      void refresh();
    }
  }, [user, refresh]);

  const itemsByMenuId = useMemo(() => buildCartItemMap(cart), [cart]);
  const itemCount = useMemo(() => countCartItems(cart), [cart]);
  // The restaurant whose items the cart holds, if any (a cart never mixes restaurants).
  const cartRestaurantId = cart.order_items[0]?.restaurant_id ?? null;

  const runMenuItemChange = useCallback(
    (menuItemId: number, change: () => Promise<void>, fallback: string) => {
      setPendingMenuItemId(menuItemId);
      return change()
        .then(refresh)
        .catch((requestError: unknown) => setError(errorCopy(requestError, fallback)))
        .finally(() => setPendingMenuItemId(null));
    },
    [refresh],
  );

  const addItem = useCallback(
    (item: MenuItem) => {
      if (!user) {
        requireSession("Sign in to add menu items to your cart.");
        return Promise.resolve();
      }
      if (conflict) {
        return Promise.resolve();
      }
      setPendingMenuItemId(item.id);
      return addItemToCart(item.id)
        .then(refresh)
        .catch((requestError: unknown) => {
          if (hasErrorCode(requestError, ERROR_CODES.cartRestaurantConflict)) {
            // Two adds in flight can both come back 409: the first one asks.
            setConflict((current) => current ?? { item });
            // The cart on screen may be stale (another tab); show the one the server has.
            return refresh();
          }
          setError(errorCopy(requestError, "Unable to add item."));
          return undefined;
        })
        .finally(() => setPendingMenuItemId(null));
    },
    [conflict, refresh, requireSession, user],
  );

  const dismissConflict = useCallback(() => {
    if (!conflictPending) {
      setConflict(null);
      setConflictError("");
    }
  }, [conflictPending]);

  const resolveConflict = useCallback(async () => {
    if (!conflict || conflictPending) {
      return;
    }
    const { item } = conflict;
    setConflictPending(true);
    setConflictError("");

    try {
      await clearCart();
    } catch (requestError) {
      // Nothing changed on the server: keep the dialog open so the user can retry or cancel.
      setConflictError(errorCopy(requestError, "Unable to clear the cart."));
      setConflictPending(false);
      return;
    }

    let addError: unknown = null;
    try {
      await addItemToCart(item.id);
    } catch (requestError) {
      addError = requestError;
    }
    // The cart is empty or holds the new item now; show it before reporting a failed add.
    await refresh();
    setConflict(null);
    setConflictPending(false);
    if (addError !== null) {
      setError(
        `Your cart was cleared, but ${item.name} was not added. ${errorCopy(addError, "Unable to add item.")}`,
      );
    }
  }, [conflict, conflictPending, refresh]);

  const decreaseItem = useCallback(
    (menuItemId: number) => {
      const cartItem = itemsByMenuId.get(menuItemId);

      if (!cartItem) {
        return Promise.resolve();
      }

      return runMenuItemChange(
        menuItemId,
        () => updateCartItem(cartItem.orderItemId, Math.max(cartItem.quantity - 1, 0)),
        "Unable to update item.",
      );
    },
    [itemsByMenuId, runMenuItemChange],
  );

  const setLineQuantity = useCallback(
    (item: CartItem, nextQuantity: number) => {
      setPendingCartItemId(item.order_item_id);
      const change =
        nextQuantity <= 0
          ? removeCartItem(item.order_item_id)
          : updateCartItem(item.order_item_id, nextQuantity);

      return change
        .then(refresh)
        .catch((requestError: unknown) =>
          setError(errorCopy(requestError, "Unable to update cart quantity.")),
        )
        .finally(() => setPendingCartItemId(null));
    },
    [refresh],
  );

  /** DELETE /cart, then re-read it. */
  const removeAll = useCallback(() => {
    setClearing(true);
    return clearCart()
      .then(refresh)
      .catch((requestError: unknown) =>
        setError(errorCopy(requestError, "Unable to clear the cart.")),
      )
      .finally(() => setClearing(false));
  }, [refresh]);

  // Checkout empties the cart on the server; drop any read that is still in flight.
  const clear = useCallback(() => {
    nextCartSignal();
    setCart(EMPTY_CART);
  }, [nextCartSignal]);

  return {
    cart,
    error,
    itemCount,
    itemsByMenuId,
    cartRestaurantId,
    pendingMenuItemId,
    pendingCartItemId,
    clearing,
    conflict,
    conflictPending,
    conflictError,
    addItem,
    decreaseItem,
    setLineQuantity,
    removeAll,
    resolveConflict,
    dismissConflict,
    clear,
    refresh,
  };
}

export type CartState = ReturnType<typeof useCart>;
