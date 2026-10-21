import { fieldsOf } from "../api/decode";
import { type MerchantAccount, ROLES, type RiderAccount } from "../api/dto";
import { restaurantsFixture } from "./fixtures";
import type { FakeContext } from "./fakeFulfilment";
import { type FakeRoute, apiError, idParam, json, requestJson } from "./fakeHttp";

const CREATED_AT = "2026-10-16T09:00:00";

export const merchantAccountFixture = (
  overrides: Partial<MerchantAccount> & Pick<MerchantAccount, "merchant_id" | "email">,
): MerchantAccount => ({
  customer_id: 100 + overrides.merchant_id,
  first_name: "Burger Barn",
  last_name: "Merchant",
  restaurant_id: 1,
  restaurant_name: "Burger Barn",
  active: true,
  created_at: CREATED_AT,
  ...overrides,
});

export const riderAccountFixture = (
  overrides: Partial<RiderAccount> & Pick<RiderAccount, "rider_id" | "name">,
): RiderAccount => ({
  customer_id: 200 + overrides.rider_id,
  email: null,
  first_name: null,
  last_name: null,
  phone: "(408) 555-0101",
  active: true,
  on_duty: false,
  active_deliveries: 0,
  created_at: CREATED_AT,
  ...overrides,
});

const emailTaken = () =>
  apiError(409, "Email is already registered", { code: "EMAIL_ALREADY_REGISTERED" });

/**
 * /admin/merchants and /admin/riders (ROLE_ADMIN) over `state.merchantAccounts` and
 * `state.riderAccounts`, with the contract's 409 EMAIL_ALREADY_REGISTERED and the 404
 * for an unknown restaurant.
 */
export function adminRoutes({ state, requireRole, emailRegistered }: FakeContext) {
  const routes: FakeRoute[] = [
    ["GET", /^\/admin\/merchants$/, requireRole(ROLES.admin, () => json(state.merchantAccounts))],
    [
      "POST",
      /^\/admin\/merchants$/,
      requireRole(ROLES.admin, (request) => {
        const body = fieldsOf(requestJson(request), "body");
        const email = body.string("email");
        if (emailRegistered(email)) {
          return emailTaken();
        }
        const restaurant = restaurantsFixture.find(
          (candidate) => candidate.id === body.number("restaurant_id"),
        );
        if (!restaurant) {
          return apiError(404, "Restaurant not found", { code: "NOT_FOUND" });
        }
        const account = merchantAccountFixture({
          merchant_id: state.nextAccountId++,
          email,
          first_name: body.string("first_name"),
          last_name: body.string("last_name"),
          restaurant_id: restaurant.id,
          restaurant_name: restaurant.name,
        });
        state.merchantAccounts.push(account);
        return json(account, { status: 201 });
      }),
    ],
    [
      "PATCH",
      /^\/admin\/merchants\/(\d+)$/,
      requireRole(ROLES.admin, (request, match) => {
        const account = state.merchantAccounts.find((item) => item.merchant_id === idParam(match));
        if (!account) {
          return apiError(404, "Merchant not found", { code: "NOT_FOUND" });
        }
        account.active = fieldsOf(requestJson(request), "body").boolean("active");
        return json({ ...account });
      }),
    ],
    ["GET", /^\/admin\/riders$/, requireRole(ROLES.admin, () => json(state.riderAccounts))],
    [
      "POST",
      /^\/admin\/riders$/,
      requireRole(ROLES.admin, (request) => {
        const body = fieldsOf(requestJson(request), "body");
        const email = body.string("email");
        if (emailRegistered(email)) {
          return emailTaken();
        }
        const firstName = body.string("first_name");
        const lastName = body.string("last_name");
        const account = riderAccountFixture({
          rider_id: state.nextAccountId++,
          name: `${firstName} ${lastName}`,
          email,
          first_name: firstName,
          last_name: lastName,
          phone: body.string("phone"),
        });
        state.riderAccounts.push(account);
        return json(account, { status: 201 });
      }),
    ],
    [
      "PATCH",
      /^\/admin\/riders\/(\d+)$/,
      requireRole(ROLES.admin, (request, match) => {
        const account = state.riderAccounts.find((item) => item.rider_id === idParam(match));
        if (!account) {
          return apiError(404, "Rider not found", { code: "NOT_FOUND" });
        }
        account.active = fieldsOf(requestJson(request), "body").boolean("active");
        return json({ ...account });
      }),
    ],
  ];
  return routes;
}
