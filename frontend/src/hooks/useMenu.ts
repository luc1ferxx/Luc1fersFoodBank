import { useCallback, useEffect, useMemo, useState } from "react";
import { isAbortError } from "../api/client";
import type { MenuItem, Restaurant } from "../api/dto";
import { getMenus, getRestaurants } from "../api/endpoints";
import { errorCopy } from "../lib/format";
import { useLatestRequest } from "./useLatestRequest";

interface MenuState {
  items: MenuItem[];
  loading: boolean;
  error: string;
}

const idleMenu: MenuState = { items: [], loading: false, error: "" };

/**
 * Public restaurant directory and the selected restaurant's menu. Switching
 * restaurants aborts the previous menu request, so a slow response for a restaurant
 * the user already left cannot replace the menu that is on screen.
 */
export function useMenu() {
  const [restaurants, setRestaurants] = useState<Restaurant[]>([]);
  const [restaurantsLoading, setRestaurantsLoading] = useState(true);
  const [restaurantsError, setRestaurantsError] = useState("");
  const [selectedRestaurantId, setSelectedRestaurantId] = useState<number | null>(null);
  const [menu, setMenu] = useState<MenuState>(idleMenu);
  const nextMenuSignal = useLatestRequest();

  const loadMenu = useCallback(
    (restaurantId: number | null) => {
      const signal = nextMenuSignal();

      if (restaurantId === null) {
        setMenu(idleMenu);
        return;
      }

      setMenu((current) => ({ ...current, loading: true, error: "" }));
      getMenus(restaurantId, { signal })
        .then((items) => {
          if (!signal.aborted) {
            setMenu({ items, loading: false, error: "" });
          }
        })
        .catch((error: unknown) => {
          if (!signal.aborted && !isAbortError(error)) {
            setMenu({
              items: [],
              loading: false,
              error: errorCopy(error, "Unable to load the menu."),
            });
          }
        });
    },
    [nextMenuSignal],
  );

  const selectRestaurant = useCallback(
    (restaurantId: number) => {
      if (restaurantId === selectedRestaurantId) {
        return;
      }
      setSelectedRestaurantId(restaurantId);
      loadMenu(restaurantId);
    },
    [loadMenu, selectedRestaurantId],
  );

  useEffect(() => {
    const controller = new AbortController();

    getRestaurants({ signal: controller.signal })
      .then((nextRestaurants) => {
        if (controller.signal.aborted) {
          return;
        }
        setRestaurants(nextRestaurants);
        setRestaurantsLoading(false);
        const firstRestaurantId = nextRestaurants[0]?.id ?? null;
        setSelectedRestaurantId(firstRestaurantId);
        loadMenu(firstRestaurantId);
      })
      .catch((error: unknown) => {
        if (!controller.signal.aborted && !isAbortError(error)) {
          setRestaurantsError(errorCopy(error, "Unable to load restaurants."));
          setRestaurantsLoading(false);
        }
      });

    return () => controller.abort();
  }, [loadMenu]);

  const selectedRestaurant = useMemo(
    () => restaurants.find((restaurant) => restaurant.id === selectedRestaurantId) ?? null,
    [restaurants, selectedRestaurantId],
  );

  return {
    restaurants,
    restaurantsLoading,
    restaurantsError,
    selectedRestaurantId,
    selectedRestaurant,
    selectRestaurant,
    menuItems: menu.items,
    menuLoading: menu.loading,
    menuError: menu.error,
  };
}

export type MenuBrowser = ReturnType<typeof useMenu>;
