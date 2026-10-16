import { useCallback, useEffect, useMemo, useState } from "react";
import { isAbortError } from "../api/client";
import { addItemToCart, getCart, removeCartItem, updateCartItem } from "../api/endpoints";
import { EMPTY_CART, buildCartItemMap, countCartItems, errorCopy } from "../lib/format";
import { useLatestRequest } from "./useLatestRequest";

/**
 * The signed-in user's cart. Every mutation re-reads the cart from the server, and
 * only the newest read may update the screen.
 */
export function useCart(user, requireSession) {
  const [cart, setCart] = useState(EMPTY_CART);
  const [error, setError] = useState("");
  const [pendingMenuItemId, setPendingMenuItemId] = useState(null);
  const [pendingCartItemId, setPendingCartItemId] = useState(null);
  const nextCartSignal = useLatestRequest();

  const refresh = useCallback(() => {
    const signal = nextCartSignal();
    return getCart({ signal })
      .then((data) => {
        if (!signal.aborted) {
          setCart(data || EMPTY_CART);
          setError("");
        }
      })
      .catch((requestError) => {
        if (!signal.aborted && !isAbortError(requestError)) {
          setError(errorCopy(requestError, "Unable to load the cart."));
        }
      });
  }, [nextCartSignal]);

  useEffect(() => {
    if (user) {
      refresh();
    }
  }, [user, refresh]);

  const itemsByMenuId = useMemo(() => buildCartItemMap(cart), [cart]);
  const itemCount = useMemo(() => countCartItems(cart), [cart]);

  const runMenuItemChange = useCallback(
    (menuItemId, change, fallback) => {
      setPendingMenuItemId(menuItemId);
      return change()
        .then(refresh)
        .catch((requestError) => setError(errorCopy(requestError, fallback)))
        .finally(() => setPendingMenuItemId(null));
    },
    [refresh],
  );

  const addItem = useCallback(
    (menuItemId) => {
      if (!user) {
        requireSession("Sign in to add menu items to your cart.");
        return Promise.resolve();
      }
      return runMenuItemChange(menuItemId, () => addItemToCart(menuItemId), "Unable to add item.");
    },
    [requireSession, runMenuItemChange, user],
  );

  const decreaseItem = useCallback(
    (menuItemId) => {
      const cartItem = itemsByMenuId[menuItemId];

      if (!cartItem?.orderItemId) {
        return Promise.resolve();
      }

      return runMenuItemChange(
        menuItemId,
        () => updateCartItem(cartItem.orderItemId, Math.max((cartItem.quantity || 1) - 1, 0)),
        "Unable to update item.",
      );
    },
    [itemsByMenuId, runMenuItemChange],
  );

  const setLineQuantity = useCallback(
    (item, nextQuantity) => {
      setPendingCartItemId(item.order_item_id);
      const change =
        nextQuantity <= 0
          ? removeCartItem(item.order_item_id)
          : updateCartItem(item.order_item_id, nextQuantity);

      return change
        .then(refresh)
        .catch((requestError) =>
          setError(errorCopy(requestError, "Unable to update cart quantity.")),
        )
        .finally(() => setPendingCartItemId(null));
    },
    [refresh],
  );

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
    pendingMenuItemId,
    pendingCartItemId,
    addItem,
    decreaseItem,
    setLineQuantity,
    clear,
  };
}
