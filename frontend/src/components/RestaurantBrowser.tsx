import { useMemo } from "react";
import type { MenuItem } from "../api/dto";
import type { CartState } from "../hooks/useCart";
import type { MenuBrowser } from "../hooks/useMenu";
import { Alert } from "./Alert";
import { MediaImage } from "./MediaImage";
import { MenuCard } from "./MenuCard";

const matchesSearch = (item: MenuItem, keyword: string) =>
  `${item.name} ${item.description || ""}`.toLowerCase().includes(keyword);

interface RestaurantBrowserProps {
  menu: MenuBrowser;
  searchValue: string;
  onSearchChange: (value: string) => void;
  signedIn: boolean;
  cart: CartState;
}

export function RestaurantBrowser({
  menu,
  searchValue,
  onSearchChange,
  signedIn,
  cart,
}: RestaurantBrowserProps) {
  const {
    restaurants,
    restaurantsLoading,
    restaurantsError,
    selectedRestaurantId,
    selectedRestaurant,
    selectRestaurant,
    menuItems,
    menuLoading,
    menuError,
  } = menu;

  const filteredMenuItems = useMemo(() => {
    const keyword = searchValue.trim().toLowerCase();
    return keyword ? menuItems.filter((item) => matchesSearch(item, keyword)) : menuItems;
  }, [menuItems, searchValue]);

  return (
    <section className="primary-workspace" aria-label="Restaurant and menu browsing">
      <div className="workspace-header">
        <div>
          <p className="section-kicker">Restaurant directory</p>
          <h2>Browse menus, then build an authenticated cart.</h2>
        </div>
        <label className="search-box">
          <span>Search menu</span>
          <input
            type="search"
            value={searchValue}
            placeholder="Dish name or description"
            onChange={(event) => onSearchChange(event.target.value)}
          />
        </label>
      </div>

      <Alert tone="danger">{restaurantsError}</Alert>
      {restaurantsLoading ? (
        <div className="loading-row">Loading restaurants...</div>
      ) : restaurants.length === 0 ? (
        <div className="empty-state">No restaurants are available from the backend.</div>
      ) : (
        <div className="restaurant-rail" aria-label="Restaurants">
          {restaurants.map((restaurant) => (
            <button
              className={`restaurant-button ${restaurant.id === selectedRestaurantId ? "restaurant-button--active" : ""}`}
              key={restaurant.id}
              type="button"
              aria-pressed={restaurant.id === selectedRestaurantId}
              onClick={() => selectRestaurant(restaurant.id)}
            >
              <span>
                #{restaurant.id} {restaurant.name}
              </span>
              <small>{restaurant.phone || "No phone listed"}</small>
            </button>
          ))}
        </div>
      )}

      {selectedRestaurant ? (
        <article className="restaurant-summary">
          <div className="restaurant-summary__media">
            <MediaImage src={selectedRestaurant.image_url} alt={selectedRestaurant.name} />
          </div>
          <div>
            <p className="section-kicker">Selected restaurant</p>
            <h3>{selectedRestaurant.name}</h3>
            <p>{selectedRestaurant.address || "Address unavailable"}</p>
            <div className="meta-row">
              <span>{selectedRestaurant.phone || "No phone"}</span>
              <span>{menuItems.length} dishes loaded</span>
              {searchValue ? <span>{filteredMenuItems.length} matches</span> : null}
            </div>
          </div>
        </article>
      ) : null}

      <Alert tone="danger">{menuError}</Alert>
      {menuLoading ? (
        <div className="loading-row">Loading menu...</div>
      ) : filteredMenuItems.length === 0 ? (
        <div className="empty-state">
          {searchValue ? "No menu items match this search." : "No menu items are listed."}
        </div>
      ) : (
        <div className="menu-grid">
          {filteredMenuItems.map((item) => (
            <MenuCard
              key={item.id}
              item={item}
              signedIn={signedIn}
              quantity={cart.itemsByMenuId.get(item.id)?.quantity ?? 0}
              pending={cart.pendingMenuItemId === item.id}
              error={cart.menuItemErrors.get(item.id)}
              onAdd={cart.addItem}
              onDecrease={cart.decreaseItem}
            />
          ))}
        </div>
      )}
    </section>
  );
}
