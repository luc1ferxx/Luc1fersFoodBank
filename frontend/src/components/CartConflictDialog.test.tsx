import { act, screen, waitFor, within } from "@testing-library/react";
import type { UserEvent } from "@testing-library/user-event";
import { describe, expect, it } from "vitest";
import type { CartItem } from "../api/dto";
import {
  USD,
  apiError,
  deferred,
  demoUser,
  installFakeBackend,
  requestJson,
} from "../test/fakeBackend";
import { flushPromises, menuCard, renderApp } from "../test/helpers";

const whopperLine: CartItem = {
  order_item_id: 501,
  menu_item_id: 10,
  restaurant_id: 1,
  menu_item_name: "Whopper",
  menu_item_description: "Flame grilled burger",
  menu_item_image_url: "",
  price: 6.39,
  currency: USD,
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

const cartConflict = () =>
  apiError(409, "Your cart already has items from another restaurant.", {
    code: "CART_RESTAURANT_CONFLICT",
  });

/** The element directly under <body> that holds `element` (the app's root). */
const pageRoot = (element: HTMLElement): HTMLElement => {
  let current = element;
  while (current.parentElement && current.parentElement !== document.body) {
    current = current.parentElement;
  }
  return current;
};

/** Like addFromAnotherRestaurant, but the Pay button reads "Paying..." meanwhile. */
const addFromAnotherRestaurantDuringCheckout = async (user: UserEvent) => {
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

    // The dialog's add was asked for on the Dan Dan Noodles card: it says so there only.
    expect(await within(menuCard("Dan Dan Noodles")).findByRole("alert")).toHaveTextContent(
      "Your cart was cleared, but Dan Dan Noodles was not added. 503: Menu service unavailable",
    );
    expect(screen.getAllByRole("alert")).toHaveLength(1);
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

    expect(await within(menuCard("Whopper")).findByRole("alert")).toHaveTextContent(
      "409: Menu item is not available",
    );
    expect(screen.getAllByRole("alert")).toHaveLength(1);
    expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
  });
});

describe("the clear-cart dialog as a modal", () => {
  it("keeps keyboard focus inside and makes the page behind it inert", async () => {
    installFakeBackend({ user: demoUser, cartItems: [whopperLine] });
    const user = renderApp();

    const dialog = await addFromAnotherRestaurant(user);
    const addButton = screen.getByRole("button", { name: "Add to cart", hidden: true });
    const page = pageRoot(addButton);
    expect(page).toHaveAttribute("inert");
    expect(dialog.closest("[inert]")).toBeNull();

    const cancel = within(dialog).getByRole("button", { name: "Cancel" });
    const confirm = within(dialog).getByRole("button", { name: "Clear cart & add" });
    expect(cancel).toHaveFocus();
    await user.tab();
    expect(confirm).toHaveFocus();
    await user.tab();
    expect(cancel).toHaveFocus();
    await user.tab({ shift: true });
    expect(confirm).toHaveFocus();

    await user.click(cancel);
    expect(page).not.toHaveAttribute("inert");
    expect(addButton).toHaveFocus();
  });

  it("closes on Escape even when focus left the dialog", async () => {
    const backend = installFakeBackend({ user: demoUser, cartItems: [whopperLine] });
    const user = renderApp();

    await addFromAnotherRestaurant(user);
    act(() => {
      if (document.activeElement instanceof HTMLElement) {
        document.activeElement.blur();
      }
    });
    expect(document.body).toHaveFocus();
    await user.keyboard("{Escape}");

    expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
    expect(backend.callsTo("DELETE", "/cart")).toHaveLength(0);
  });

  it("refuses another add while it is open and never swaps the item it names", async () => {
    const backend = installFakeBackend({ user: demoUser, cartItems: [whopperLine] });
    const firstAdd = deferred();
    const secondAdd = deferred();
    backend.on("POST", "/cart", () => firstAdd.promise);
    backend.on("POST", "/cart", () => secondAdd.promise);
    const user = renderApp();

    // Two adds from other restaurants are in flight at once.
    await screen.findByRole("button", { name: "Pay $12.78" });
    await user.click(screen.getByRole("button", { name: /Noodle Nook/ }));
    await screen.findByText("Dan Dan Noodles");
    await user.click(screen.getByRole("button", { name: "Add to cart" }));
    await user.click(screen.getByRole("button", { name: /Taco Town/ }));
    await screen.findByText("Fish Taco");
    await user.click(screen.getByRole("button", { name: "Add to cart" }));

    await act(async () => {
      firstAdd.resolve(cartConflict());
      await firstAdd.promise;
    });
    const dialog = await screen.findByRole("dialog", { name: "Start a new cart?" });
    expect(dialog).toHaveTextContent("clear the cart and add Dan Dan Noodles?");

    // The later 409 does not replace the open question.
    await act(async () => {
      secondAdd.resolve(cartConflict());
      await secondAdd.promise;
    });
    await flushPromises();
    expect(dialog).toHaveTextContent("clear the cart and add Dan Dan Noodles?");

    // inert keeps real browsers from reaching the page; jsdom does not honour it, so a
    // click still lands, and the add is refused without a request.
    await user.click(screen.getByRole("button", { name: "Add to cart", hidden: true }));
    expect(backend.callsTo("POST", "/cart")).toHaveLength(2);

    await user.click(within(dialog).getByRole("button", { name: "Clear cart & add" }));
    expect(await screen.findByRole("button", { name: "Pay $11.00" })).toBeInTheDocument();
    expect(backend.callsTo("POST", "/cart").map(requestJson)).toEqual([
      { menu_id: 20 },
      { menu_id: 30 },
      { menu_id: 20 },
    ]);
  });

  it("waits for a running checkout before clearing the cart", async () => {
    const backend = installFakeBackend({ user: demoUser, cartItems: [whopperLine] });
    const checkout = deferred();
    backend.on("POST", "/payments/checkout", () => checkout.promise);
    const user = renderApp();

    await screen.findByRole("button", { name: "Pay $12.78" });
    await user.type(screen.getByLabelText("Cardholder name"), "Demo User");
    await user.type(screen.getByLabelText("Card number"), "4242424242424242");
    await user.type(screen.getByLabelText("Expiry"), "1230");
    await user.type(screen.getByLabelText("CVV"), "123");
    await user.click(screen.getByRole("button", { name: "Pay $12.78" }));

    const dialog = await addFromAnotherRestaurantDuringCheckout(user);
    const confirm = within(dialog).getByRole("button", { name: "Clear cart & add" });
    expect(confirm).toBeDisabled();
    expect(dialog).toHaveTextContent("Your checkout is still running. Wait for it to finish.");
    await user.click(confirm);
    expect(backend.callsTo("DELETE", "/cart")).toHaveLength(0);

    await act(async () => {
      checkout.resolve(apiError(503, "Payment gateway timed out"));
      await checkout.promise;
    });
    await waitFor(() => expect(confirm).toBeEnabled());
    expect(dialog).not.toHaveTextContent("Your checkout is still running.");

    await user.click(confirm);
    expect(await screen.findByRole("button", { name: "Pay $11.00" })).toBeInTheDocument();
    expect(backend.callsTo("DELETE", "/cart")).toHaveLength(1);
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
