import { useCallback, useEffect, useMemo, useRef, useState } from "react";
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
import { withEntry, withoutEntry } from "../lib/maps";
import { useLatestRequest } from "./useLatestRequest";

/** An add the backend refused because the cart holds another restaurant's items. */
export interface CartConflict {
  item: MenuItem;
}

/** A menu card's error, numbered in the order the hook recorded it. */
interface MenuItemError {
  message: string;
  seq: number;
}

const NO_MENU_ITEM_ERRORS: ReadonlyMap<number, MenuItemError> = new Map();

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
 *
 * An error is reported where the change was asked for: `error` (the Cart tab) for
 * reading the cart, its line edits and Clear cart; `menuItemErrors` (the menu card,
 * keyed by menu item id) for a menu card's add or decrease and the dialog's add,
 * including a failed re-read after them. A card's error stays until that card's next
 * change, or until the hook learns the cart's contents after it: a cart read that
 * started after the error succeeds (the re-read of any change, Clear cart, coming back
 * to the workspace) or checkout empties the cart. A read already running when the
 * error came does not drop it, so a card refused during another card's re-read keeps
 * its message.
 */
export function useCart(user: CurrentUser | null, requireSession: (message: string) => void) {
  const [cart, setCart] = useState<Cart>(EMPTY_CART);
  const [error, setError] = useState("");
  const [menuItemErrorEntries, setMenuItemErrorEntries] = useState(NO_MENU_ITEM_ERRORS);
  // The seq of the newest card error recorded; a cart read notes it when it starts.
  const lastMenuItemErrorSeq = useRef(0);
  const [pendingMenuItemId, setPendingMenuItemId] = useState<number | null>(null);
  const [pendingCartItemId, setPendingCartItemId] = useState<number | null>(null);
  const [clearing, setClearing] = useState(false);
  const [conflict, setConflict] = useState<CartConflict | null>(null);
  const [conflictPending, setConflictPending] = useState(false);
  const [conflictError, setConflictError] = useState("");
  const nextCartSignal = useLatestRequest();

  /** Drops the card errors recorded up to `seq`; the later ones stay. */
  const dropMenuItemErrors = useCallback((seq: number) => {
    setMenuItemErrorEntries((current) => {
      const kept = [...current].filter(([, entry]) => entry.seq > seq);
      return kept.length === current.size ? current : new Map(kept);
    });
  }, []);

  /**
   * Re-reads the cart; a failed read goes to `reportError`. A successful one replaces
   * the card errors recorded before it started.
   */
  const load = useCallback(
    (reportError: (message: string) => void) => {
      const signal = nextCartSignal();
      const errorsBefore = lastMenuItemErrorSeq.current;
      return getCart({ signal })
        .then((data) => {
          if (!signal.aborted) {
            setCart(data);
            setError("");
            dropMenuItemErrors(errorsBefore);
          }
        })
        .catch((requestError: unknown) => {
          if (!signal.aborted && !isAbortError(requestError)) {
            reportError(errorCopy(requestError, "Unable to load the cart."));
          }
        });
    },
    [dropMenuItemErrors, nextCartSignal],
  );

  const refresh = useCallback(() => load(setError), [load]);

  /** Records `message` on the menu card of `menuItemId`, or clears it when empty. */
  const setMenuItemError = useCallback((menuItemId: number, message: string) => {
    if (!message) {
      setMenuItemErrorEntries((current) => withoutEntry(current, menuItemId));
      return;
    }
    lastMenuItemErrorSeq.current += 1;
    const entry = { message, seq: lastMenuItemErrorSeq.current };
    setMenuItemErrorEntries((current) => withEntry(current, menuItemId, entry));
  }, []);

  const menuItemErrors = useMemo<ReadonlyMap<number, string>>(
    () => new Map([...menuItemErrorEntries].map(([id, entry]) => [id, entry.message])),
    [menuItemErrorEntries],
  );

  /** Re-reads the cart after a change made on the menu card of `menuItemId`. */
  const refreshForMenuItem = useCallback(
    (menuItemId: number) => load((message) => setMenuItemError(menuItemId, message)),
    [load, setMenuItemError],
  );

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
      setMenuItemError(menuItemId, "");
      return change()
        .then(() => refreshForMenuItem(menuItemId))
        .catch((requestError: unknown) =>
          setMenuItemError(menuItemId, errorCopy(requestError, fallback)),
        )
        .finally(() => setPendingMenuItemId(null));
    },
    [refreshForMenuItem, setMenuItemError],
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
      setMenuItemError(item.id, "");
      return addItemToCart(item.id)
        .then(() => refreshForMenuItem(item.id))
        .catch((requestError: unknown) => {
          if (hasErrorCode(requestError, ERROR_CODES.cartRestaurantConflict)) {
            // Two adds in flight can both come back 409: the first one asks.
            setConflict((current) => current ?? { item });
            // The cart on screen may be stale (another tab); show the one the server has.
            return refreshForMenuItem(item.id);
          }
          setMenuItemError(item.id, errorCopy(requestError, "Unable to add item."));
          return undefined;
        })
        .finally(() => setPendingMenuItemId(null));
    },
    [conflict, refreshForMenuItem, requireSession, setMenuItemError, user],
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
    await refreshForMenuItem(item.id);
    setConflict(null);
    setConflictPending(false);
    if (addError !== null) {
      setMenuItemError(
        item.id,
        `Your cart was cleared, but ${item.name} was not added. ${errorCopy(addError, "Unable to add item.")}`,
      );
    }
  }, [conflict, conflictPending, refreshForMenuItem, setMenuItemError]);

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

  // Checkout empties the cart on the server; drop any read that is still in flight, and
  // the card errors of the changes before it.
  const clear = useCallback(() => {
    nextCartSignal();
    setCart(EMPTY_CART);
    dropMenuItemErrors(lastMenuItemErrorSeq.current);
  }, [dropMenuItemErrors, nextCartSignal]);

  return {
    cart,
    error,
    menuItemErrors,
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
