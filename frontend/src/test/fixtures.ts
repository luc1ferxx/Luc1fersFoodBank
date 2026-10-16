import {
  type CurrentUser,
  type MenuItem,
  type Order,
  ROLES,
  type Restaurant,
  type RiderProfile,
} from "../api/dto";

/* Shared fixtures of the fake backend and the UI tests. */

/** A customer who is also an admin, like the demo seed's demo@laifood.com. */
export const demoUser: CurrentUser = {
  id: 1,
  email: "demo@laifood.com",
  first_name: "Demo",
  last_name: "User",
  roles: [ROLES.user, ROLES.admin],
  merchant: null,
  rider: null,
};

/** A customer without other roles. */
export const customerUser: CurrentUser = {
  id: 2,
  email: "casey@example.com",
  first_name: "Casey",
  last_name: "Customer",
  roles: [ROLES.user],
  merchant: null,
  rider: null,
};

/** A merchant login bound to Burger Barn (no ROLE_USER, like the demo merchants). */
export const merchantUser: CurrentUser = {
  id: 21,
  email: "merchant.burgerbarn@laifood.com",
  first_name: "Burger Barn",
  last_name: "Merchant",
  roles: [ROLES.merchant],
  merchant: { merchant_id: 3, restaurant_id: 1, restaurant_name: "Burger Barn", active: true },
  rider: null,
};

export const riderProfileFixture: RiderProfile = {
  rider_id: 7,
  name: "Alice Rider",
  phone: "(408) 555-0101",
  active: true,
  on_duty: false,
  active_deliveries: 0,
};

/** A rider login. */
export const riderUser: CurrentUser = {
  id: 31,
  email: "rider.alice@laifood.com",
  first_name: "Alice",
  last_name: "Rider",
  roles: [ROLES.rider],
  merchant: null,
  rider: { rider_id: 7, name: "Alice Rider", on_duty: false, active: true },
};

/** Logins the fake's POST /login accepts. */
export const PASSWORDS: ReadonlyMap<string, [CurrentUser, string]> = new Map([
  [demoUser.email, [demoUser, "demo123"]],
  [merchantUser.email, [merchantUser, "merchant123"]],
  [riderUser.email, [riderUser, "rider123"]],
]);

export const restaurantsFixture: Restaurant[] = [
  {
    id: 1,
    name: "Burger Barn",
    address: "773 N Mathilda Ave",
    phone: "(408) 736-0101",
    image_url: "",
  },
  { id: 2, name: "Noodle Nook", address: "1 Market St", phone: "", image_url: "" },
  { id: 3, name: "Taco Town", address: "2 Mission St", phone: "", image_url: "" },
];

export const menusFixture: Readonly<Record<number, MenuItem[]>> = {
  1: [
    {
      id: 10,
      restaurant_id: 1,
      name: "Whopper",
      description: "Flame grilled burger",
      price: 6.39,
      image_url: "",
    },
  ],
  2: [
    {
      id: 20,
      restaurant_id: 2,
      name: "Dan Dan Noodles",
      description: "",
      price: 11,
      image_url: "",
    },
  ],
  3: [{ id: 30, restaurant_id: 3, name: "Fish Taco", description: "", price: 4.5, image_url: "" }],
};

/** A placed order with defaults for the fields a test does not care about. */
export const orderFixture = (overrides: Partial<Order> & Pick<Order, "id">): Order => ({
  status: "PAID",
  total_price: 9,
  created_at: null,
  items: [],
  restaurant_id: 1,
  cancel_reason: null,
  ...overrides,
});
