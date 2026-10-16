import { useCallback, useEffect, useMemo, useState } from "react";
import { isAbortError } from "../api/client";
import { getMenus, getRestaurants } from "../api/endpoints";
import { errorCopy, toArray } from "../lib/format";
import { useLatestRequest } from "./useLatestRequest";

const idleMenu = { items: [], loading: false, error: "" };

/**
 * Public restaurant directory and the selected restaurant's menu. Switching
 * restaurants aborts the previous menu request, so a slow response for a restaurant
 * the user already left cannot replace the menu that is on screen.
 */
export function useMenu() {
  const [restaurants, setRestaurants] = useState([]);
  const [restaurantsLoading, setRestaurantsLoading] = useState(true);
  const [restaurantsError, setRestaurantsError] = useState("");
  const [selectedRestaurantId, setSelectedRestaurantId] = useState(null);
  const [menu, setMenu] = useState(idleMenu);
  const nextMenuSignal = useLatestRequest();

  const loadMenu = useCallback(
    (restaurantId) => {
      const signal = nextMenuSignal();

      if (!restaurantId) {
        setMenu(idleMenu);
        return;
      }

      setMenu((current) => ({ ...current, loading: true, error: "" }));
      getMenus(restaurantId, { signal })
        .then((data) => {
          if (!signal.aborted) {
            setMenu({ items: toArray(data), loading: false, error: "" });
          }
        })
        .catch((error) => {
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
    (restaurantId) => {
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
      .then((data) => {
        if (controller.signal.aborted) {
          return;
        }
        const nextRestaurants = toArray(data);
        setRestaurants(nextRestaurants);
        setRestaurantsLoading(false);
        const firstRestaurantId = nextRestaurants[0]?.id ?? null;
        setSelectedRestaurantId(firstRestaurantId);
        loadMenu(firstRestaurantId);
      })
      .catch((error) => {
        if (!controller.signal.aborted && !isAbortError(error)) {
          setRestaurantsError(errorCopy(error, "Unable to load restaurants."));
          setRestaurantsLoading(false);
        }
      });

    return () => controller.abort();
  }, [loadMenu]);

  const selectedRestaurant = useMemo(
    () => restaurants.find((restaurant) => restaurant.id === selectedRestaurantId),
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
