import { screen, waitFor, within } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { ROLES } from "./api/dto";
import { SESSION_EXPIRED_MESSAGE } from "./hooks/useSession";
import {
  apiError,
  customerUser,
  demoUser,
  installFakeBackend,
  merchantOrderFixture,
  merchantUser,
  riderUser,
} from "./test/fakeBackend";
import { renderApp } from "./test/helpers";

const switcher = () => screen.queryByRole("navigation", { name: "Workspaces" });

describe("role-based workspaces", () => {
  it("keeps a plain customer on the ordering page without a switcher", async () => {
    const backend = installFakeBackend({ user: customerUser });
    renderApp();

    expect(await screen.findByText("Casey is signed in")).toBeInTheDocument();
    expect(await screen.findByRole("button", { name: "Cart (0)" })).toBeInTheDocument();
    expect(switcher()).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Admin tools" })).not.toBeInTheDocument();
    expect(backend.callsTo("GET", "/merchant/orders")).toHaveLength(0);
  });

  it("lets a customer who is an admin switch between ordering and the admin console", async () => {
    const backend = installFakeBackend({ user: demoUser });
    const user = renderApp();

    await screen.findByText("Demo is signed in");
    const nav = switcher();
    if (!nav) {
      throw new Error("No workspace switcher");
    }
    expect(
      within(nav)
        .getAllByRole("button")
        .map((button) => button.textContent),
    ).toEqual(["Order food", "Admin console"]);
    expect(within(nav).getByRole("button", { name: "Order food" })).toHaveAttribute(
      "aria-pressed",
      "true",
    );

    await user.click(within(nav).getByRole("button", { name: "Admin console" }));
    expect(await screen.findByRole("region", { name: "Admin console" })).toBeInTheDocument();
    expect(screen.queryByRole("region", { name: "Restaurant and menu browsing" })).toBeNull();

    // Back on the ordering page the session-scoped data is read again.
    await user.click(within(nav).getByRole("button", { name: "Order food" }));
    expect(await screen.findByRole("button", { name: "Cart (0)" })).toBeInTheDocument();
    await waitFor(() => expect(backend.callsTo("GET", "/cart")).toHaveLength(2));
  });

  it("takes a merchant from the login form to the merchant console and back out", async () => {
    const backend = installFakeBackend();
    backend.state.merchantOrders = [merchantOrderFixture({ order_id: 7 })];
    const user = renderApp();

    await user.type(await screen.findByLabelText("Email"), merchantUser.email);
    await user.type(screen.getByLabelText("Password"), "merchant123");
    await user.click(screen.getByRole("button", { name: "Sign in" }));

    expect(await screen.findByRole("heading", { name: "Burger Barn orders" })).toBeInTheDocument();
    expect(await screen.findByRole("article", { name: "Order #7" })).toBeInTheDocument();
    expect(switcher()).not.toBeInTheDocument();
    // A merchant-only login never loads a customer cart.
    expect(backend.callsTo("GET", "/cart")).toHaveLength(0);

    await user.click(screen.getByRole("button", { name: "Logout" }));
    expect(await screen.findByText("Signed out.")).toBeInTheDocument();
    expect(screen.queryByRole("region", { name: "Merchant console" })).toBeNull();
  });

  it("lands a rider on the rider console", async () => {
    installFakeBackend({ user: riderUser });
    renderApp();

    expect(await screen.findByRole("region", { name: "My deliveries" })).toBeInTheDocument();
    expect(await screen.findByRole("heading", { name: "Alice Rider" })).toBeInTheDocument();
    expect(switcher()).not.toBeInTheDocument();
  });

  it("opens the merchant console first for a user with several roles and switches views", async () => {
    installFakeBackend({
      user: {
        ...merchantUser,
        roles: [ROLES.user, ROLES.merchant, ROLES.rider],
        rider: { rider_id: 7, name: "Alice Rider", on_duty: false, active: true },
      },
    });
    const user = renderApp();

    expect(await screen.findByRole("region", { name: "Merchant console" })).toBeInTheDocument();
    const nav = switcher();
    if (!nav) {
      throw new Error("No workspace switcher");
    }
    expect(
      within(nav)
        .getAllByRole("button")
        .map((button) => button.textContent),
    ).toEqual(["Order food", "Merchant console", "Rider console"]);

    await user.click(within(nav).getByRole("button", { name: "Rider console" }));
    expect(await screen.findByRole("region", { name: "My deliveries" })).toBeInTheDocument();
    await user.click(within(nav).getByRole("button", { name: "Order food" }));
    expect(
      await screen.findByRole("region", { name: "Restaurant and menu browsing" }),
    ).toBeInTheDocument();
  });

  it("falls back to ordering when /me has no roles", async () => {
    const backend = installFakeBackend();
    backend.on(
      "GET",
      "/me",
      () =>
        new Response(JSON.stringify({ id: 1, email: "demo@laifood.com", first_name: "Demo" }), {
          headers: { "Content-Type": "application/json" },
        }),
    );
    backend.state.user = demoUser;
    renderApp();

    expect(await screen.findByText("Demo is signed in")).toBeInTheDocument();
    expect(await screen.findByRole("button", { name: "Cart (0)" })).toBeInTheDocument();
    expect(switcher()).not.toBeInTheDocument();
  });

  it("returns an expired merchant session to the sign-in form", async () => {
    const backend = installFakeBackend({ user: merchantUser });
    backend.on("GET", "/merchant/orders", () => apiError(401, "Authentication required"));
    renderApp();

    expect(await screen.findByRole("alert")).toHaveTextContent(SESSION_EXPIRED_MESSAGE);
    expect(screen.getByRole("button", { name: "Sign in" })).toBeInTheDocument();
    expect(screen.queryByRole("region", { name: "Merchant console" })).toBeNull();
  });
});
