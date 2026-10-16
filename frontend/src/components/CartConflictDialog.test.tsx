import { screen, waitFor, within } from "@testing-library/react";
import type { UserEvent } from "@testing-library/user-event";
import { describe, expect, it } from "vitest";
import type { CartItem } from "../api/dto";
import { apiError, demoUser, installFakeBackend, requestJson } from "../test/fakeBackend";
import { renderApp } from "../test/helpers";

const whopperLine: CartItem = {
  order_item_id: 501,
  menu_item_id: 10,
  restaurant_id: 1,
  menu_item_name: "Whopper",
  menu_item_description: "Flame grilled burger",
  menu_item_image_url: "",
  price: 6.39,
  quantity: 2,
};

/** Signs in with a Burger Barn cart and tries to add a Noodle Nook dish. */
const addFromAnotherRestaurant = async (user: UserEvent) => {
  await screen.findByRole("button", { name: "Pay $12.78" });
  await user.click(screen.getByRole("button", { name: /Noodle Nook/ }));
  await screen.findByText("Dan Dan Noodles");
  await user.click(screen.getByRole("button", { name: "Add to cart" }));
  return screen.findByRole("dialog", { name: "Start a new cart?" });
};

const methodsAndPaths = (backend: ReturnType<typeof installFakeBackend>) =>
  backend.calls
    .filter((call) => call.method !== "GET" || call.path === "/cart")
    .map((call) => `${call.method} ${call.path}`);

describe("adding an item from another restaurant", () => {
  it("asks before clearing the cart and leaves it alone on Cancel", async () => {
    const backend = installFakeBackend({ user: demoUser, cartItems: [whopperLine] });
    const user = renderApp();

    const dialog = await addFromAnotherRestaurant(user);
    expect(dialog).toHaveTextContent(
      "Your cart has items from Burger Barn. An order goes to one restaurant, so clear the cart and add Dan Dan Noodles?",
    );
    // Focus starts on the safe choice.
    expect(within(dialog).getByRole("button", { name: "Cancel" })).toHaveFocus();
    // The 409 is a question, not an error.
    expect(screen.queryByRole("alert")).not.toBeInTheDocument();

    await user.click(within(dialog).getByRole("button", { name: "Cancel" }));

    expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
    expect(backend.callsTo("DELETE", "/cart")).toHaveLength(0);
    expect(backend.state.cart.order_items).toEqual([whopperLine]);
    expect(screen.getByRole("button", { name: "Pay $12.78" })).toBeInTheDocument();
  });

  it("closes on Escape like Cancel", async () => {
    const backend = installFakeBackend({ user: demoUser, cartItems: [whopperLine] });
    const user = renderApp();

    await addFromAnotherRestaurant(user);
    await user.keyboard("{Escape}");

    expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
    expect(backend.callsTo("DELETE", "/cart")).toHaveLength(0);
  });

  it("clears the cart, retries the add, and shows the new cart on confirm", async () => {
    const backend = installFakeBackend({ user: demoUser, cartItems: [whopperLine] });
    const user = renderApp();

    const dialog = await addFromAnotherRestaurant(user);
    await user.click(within(dialog).getByRole("button", { name: "Clear cart & add" }));

    await waitFor(() => expect(screen.queryByRole("dialog")).not.toBeInTheDocument());
    expect(await screen.findByRole("button", { name: "Pay $11.00" })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Cart (1)" })).toBeInTheDocument();
    expect(screen.queryByText("Whopper")).not.toBeInTheDocument();

    expect(methodsAndPaths(backend)).toEqual([
      "GET /cart",
      "POST /cart",
      "GET /cart",
      "DELETE /cart",
      "POST /cart",
      "GET /cart",
    ]);
    expect(backend.callsTo("POST", "/cart").map(requestJson)).toEqual([
      { menu_id: 20 },
      { menu_id: 20 },
    ]);
  });

  it("keeps the dialog and the cart when clearing fails, and succeeds on retry", async () => {
    const backend = installFakeBackend({ user: demoUser, cartItems: [whopperLine] });
    backend.on("DELETE", "/cart", () =>
      apiError(503, "Cart service unavailable", { traceId: "trace-61" }),
    );
    const user = renderApp();

    const dialog = await addFromAnotherRestaurant(user);
    await user.click(within(dialog).getByRole("button", { name: "Clear cart & add" }));

    expect(await within(dialog).findByRole("alert")).toHaveTextContent(
      "503: Cart service unavailable Trace trace-61.",
    );
    expect(backend.state.cart.order_items).toEqual([whopperLine]);
    expect(backend.callsTo("POST", "/cart")).toHaveLength(1);

    await user.click(within(dialog).getByRole("button", { name: "Clear cart & add" }));
    expect(await screen.findByRole("button", { name: "Pay $11.00" })).toBeInTheDocument();
    expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
  });

  it("reports a failed add after the cart was cleared and shows the empty cart", async () => {
    const backend = installFakeBackend({ user: demoUser, cartItems: [whopperLine] });
    const user = renderApp();

    const dialog = await addFromAnotherRestaurant(user);
    backend.on("POST", "/cart", () => apiError(503, "Menu service unavailable"));
    await user.click(within(dialog).getByRole("button", { name: "Clear cart & add" }));

    expect(await screen.findByRole("alert")).toHaveTextContent(
      "Your cart was cleared, but Dan Dan Noodles was not added. 503: Menu service unavailable",
    );
    expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
    expect(screen.getByText("Your cart is empty. Checkout is disabled.")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Cart (0)" })).toBeInTheDocument();
  });

  it("shows other add failures as errors without the dialog", async () => {
    const backend = installFakeBackend({ user: demoUser });
    backend.on("POST", "/cart", () =>
      apiError(409, "Menu item is not available", { code: "MENU_ITEM_UNAVAILABLE" }),
    );
    const user = renderApp();

    await user.click(await screen.findByRole("button", { name: "Add to cart" }));

    expect(await screen.findByRole("alert")).toHaveTextContent("409: Menu item is not available");
    expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
  });
});

describe("the Clear cart button", () => {
  it("empties the cart on the server", async () => {
    const backend = installFakeBackend({ user: demoUser, cartItems: [whopperLine] });
    const user = renderApp();

    await screen.findByRole("button", { name: "Pay $12.78" });
    await user.click(screen.getByRole("button", { name: "Clear cart" }));

    expect(
      await screen.findByText("Your cart is empty. Checkout is disabled."),
    ).toBeInTheDocument();
    expect(backend.callsTo("DELETE", "/cart")).toHaveLength(1);
    expect(screen.getByRole("button", { name: "Cart (0)" })).toBeInTheDocument();
  });
});
