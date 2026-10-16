import { type FormEvent, useState } from "react";
import type { MerchantAccount, Restaurant, RiderAccount } from "../api/dto";
import { merchantAccountsApi, riderAccountsApi, useAccounts } from "../hooks/useAccounts";
import { formatDateTime } from "../lib/format";
import { formText } from "../lib/forms";
import { Alert } from "./Alert";

const personName = (account: { first_name: string | null; last_name: string | null }) =>
  [account.first_name, account.last_name].filter(Boolean).join(" ");

/** Reads the shared login fields; null with a message when one is missing. */
const readLogin = (form: FormData) => {
  const login = {
    email: formText(form, "email").trim(),
    password: formText(form, "password"),
    first_name: formText(form, "first_name").trim(),
    last_name: formText(form, "last_name").trim(),
  };
  return login.email && login.password && login.first_name && login.last_name ? login : null;
};

function LoginFields() {
  return (
    <>
      <div className="two-fields">
        <label>
          Email
          <input name="email" type="email" autoComplete="off" required />
        </label>
        <label>
          Password
          <input name="password" type="password" autoComplete="new-password" required />
        </label>
      </div>
      <div className="two-fields">
        <label>
          First name
          <input name="first_name" required />
        </label>
        <label>
          Last name
          <input name="last_name" required />
        </label>
      </div>
    </>
  );
}

function ActiveToggle({
  label,
  active,
  pending,
  onChange,
}: {
  label: string;
  active: boolean;
  pending: boolean;
  onChange: (active: boolean) => void;
}) {
  return (
    <button
      className="ghost-button"
      type="button"
      disabled={pending}
      aria-label={`${active ? "Deactivate" : "Activate"} ${label}`}
      onClick={() => onChange(!active)}
    >
      {pending ? "Saving..." : active ? "Deactivate" : "Activate"}
    </button>
  );
}

const statusChip = (active: boolean) => (
  <span className={`account-status account-status--${active ? "active" : "inactive"}`}>
    {active ? "Active" : "Inactive"}
  </span>
);

interface MerchantAccountsProps {
  restaurants: readonly Restaurant[];
  restaurantsLoading: boolean;
  restaurantsError: string;
}

/** List, create, and (de)activate merchant logins; the restaurant comes from the catalog. */
export function MerchantAccounts({
  restaurants,
  restaurantsLoading,
  restaurantsError,
}: MerchantAccountsProps) {
  const merchants = useAccounts(merchantAccountsApi);
  const [formError, setFormError] = useState("");

  const handleSubmit = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    const formElement = event.currentTarget;
    const form = new FormData(formElement);
    const login = readLogin(form);
    const restaurantId = Number(formText(form, "restaurant_id"));
    if (!login || !restaurantId) {
      setFormError("Fill in every field and choose a restaurant.");
      return;
    }
    setFormError("");
    if (await merchants.create({ ...login, restaurant_id: restaurantId })) {
      formElement.reset();
    }
  };

  return (
    <section className="panel-body" aria-label="Merchant accounts">
      <div className="panel-heading">
        <div>
          <p className="section-kicker">Accounts</p>
          <h2>Merchants</h2>
        </div>
        <button
          className="ghost-button"
          type="button"
          disabled={merchants.loading}
          onClick={merchants.reload}
        >
          {merchants.loading ? "Loading..." : "Refresh"}
        </button>
      </div>

      <form
        className="stack-form admin-form"
        aria-label="Create a merchant account"
        noValidate
        onSubmit={handleSubmit}
      >
        <h3>New merchant login</h3>
        <LoginFields />
        <label>
          Restaurant
          <select name="restaurant_id" required defaultValue="" disabled={restaurantsLoading}>
            <option value="" disabled>
              {restaurantsLoading ? "Loading restaurants..." : "Choose a restaurant"}
            </option>
            {restaurants.map((restaurant) => (
              <option key={restaurant.id} value={restaurant.id}>
                #{restaurant.id} {restaurant.name}
              </option>
            ))}
          </select>
        </label>
        <Alert tone="danger">{restaurantsError}</Alert>
        <Alert tone="danger">{formError || merchants.createError}</Alert>
        <button className="primary-button" type="submit" disabled={merchants.creating}>
          {merchants.creating ? "Creating..." : "Create merchant"}
        </button>
      </form>

      <Alert tone="success">{merchants.notice}</Alert>
      <Alert tone="danger">{merchants.error}</Alert>
      {!merchants.loaded ? (
        merchants.error ? null : (
          <div className="loading-row">Loading merchants...</div>
        )
      ) : merchants.accounts.length === 0 ? (
        <div className="empty-state">No merchant accounts yet.</div>
      ) : (
        <div className="table-scroll">
          <table className="accounts-table">
            <caption className="visually-hidden">Merchant accounts</caption>
            <thead>
              <tr>
                <th scope="col">Merchant</th>
                <th scope="col">Restaurant</th>
                <th scope="col">Status</th>
                <th scope="col">Created</th>
                <th scope="col">
                  <span className="visually-hidden">Actions</span>
                </th>
              </tr>
            </thead>
            <tbody>
              {merchants.accounts.map((account: MerchantAccount) => (
                <tr key={account.merchant_id}>
                  <td>
                    <strong>{personName(account) || account.email}</strong>
                    <span>{account.email}</span>
                  </td>
                  <td>{account.restaurant_name ?? `#${account.restaurant_id}`}</td>
                  <td>{statusChip(account.active)}</td>
                  <td>{formatDateTime(account.created_at)}</td>
                  <td>
                    <ActiveToggle
                      label={account.email}
                      active={account.active}
                      pending={merchants.pending.has(account.merchant_id)}
                      onChange={(active) => void merchants.setActive(account, active)}
                    />
                    <Alert tone="danger">{merchants.rowErrors.get(account.merchant_id)}</Alert>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </section>
  );
}

/** List, create, and (de)activate rider logins. */
export function RiderAccounts() {
  const riders = useAccounts(riderAccountsApi);
  const [formError, setFormError] = useState("");

  const handleSubmit = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    const formElement = event.currentTarget;
    const form = new FormData(formElement);
    const login = readLogin(form);
    const phone = formText(form, "phone").trim();
    if (!login || !phone) {
      setFormError("Fill in every field.");
      return;
    }
    setFormError("");
    if (await riders.create({ ...login, phone })) {
      formElement.reset();
    }
  };

  return (
    <section className="panel-body" aria-label="Rider accounts">
      <div className="panel-heading">
        <div>
          <p className="section-kicker">Accounts</p>
          <h2>Riders</h2>
        </div>
        <button
          className="ghost-button"
          type="button"
          disabled={riders.loading}
          onClick={riders.reload}
        >
          {riders.loading ? "Loading..." : "Refresh"}
        </button>
      </div>

      <form
        className="stack-form admin-form"
        aria-label="Create a rider account"
        noValidate
        onSubmit={handleSubmit}
      >
        <h3>New rider login</h3>
        <LoginFields />
        <label>
          Phone
          <input name="phone" type="tel" required />
        </label>
        <Alert tone="danger">{formError || riders.createError}</Alert>
        <button className="primary-button" type="submit" disabled={riders.creating}>
          {riders.creating ? "Creating..." : "Create rider"}
        </button>
      </form>

      <Alert tone="success">{riders.notice}</Alert>
      <Alert tone="danger">{riders.error}</Alert>
      {!riders.loaded ? (
        riders.error ? null : (
          <div className="loading-row">Loading riders...</div>
        )
      ) : riders.accounts.length === 0 ? (
        <div className="empty-state">No riders yet.</div>
      ) : (
        <div className="table-scroll">
          <table className="accounts-table">
            <caption className="visually-hidden">Rider accounts</caption>
            <thead>
              <tr>
                <th scope="col">Rider</th>
                <th scope="col">Phone</th>
                <th scope="col">Status</th>
                <th scope="col">Deliveries</th>
                <th scope="col">Created</th>
                <th scope="col">
                  <span className="visually-hidden">Actions</span>
                </th>
              </tr>
            </thead>
            <tbody>
              {riders.accounts.map((account: RiderAccount) => (
                <tr key={account.rider_id}>
                  <td>
                    <strong>{account.name}</strong>
                    <span>{account.email ?? "No login"}</span>
                  </td>
                  <td>{account.phone ?? "-"}</td>
                  <td>
                    {statusChip(account.active)}
                    <span className="quiet-inline">{account.on_duty ? "On duty" : "Off duty"}</span>
                  </td>
                  <td>{account.active_deliveries} active</td>
                  <td>{formatDateTime(account.created_at)}</td>
                  <td>
                    <ActiveToggle
                      label={account.email ?? account.name}
                      active={account.active}
                      pending={riders.pending.has(account.rider_id)}
                      onChange={(active) => void riders.setActive(account, active)}
                    />
                    <Alert tone="danger">{riders.rowErrors.get(account.rider_id)}</Alert>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </section>
  );
}
