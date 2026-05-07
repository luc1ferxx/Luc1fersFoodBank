import { render, screen, waitFor } from "@testing-library/react";
import App from "./App";
import {
  cancelOrder,
  checkoutWithPayment,
  createIdempotencyKey,
  getNotifications,
  replayDeadLetter,
  updateOrderStatus,
} from "./utils";

const jsonResponse = (body, init = {}) =>
  new Response(JSON.stringify(body), {
    status: 200,
    headers: { "Content-Type": "application/json" },
    ...init,
  });

afterEach(() => {
  if (global.fetch?.mockRestore) {
    global.fetch.mockRestore();
  }
});

test("API helpers use the backend-supported endpoints and idempotency headers", async () => {
  const fetchMock = jest
    .spyOn(global, "fetch")
    .mockImplementation(() => Promise.resolve(jsonResponse({ id: 101, status: "PAID" })));

  await checkoutWithPayment(
    {
      cardholder_name: "Demo User",
      card_number: "4242424242424242",
      expiry: "12/30",
      cvv: "123",
    },
    "pay-key-1"
  );
  await cancelOrder(101, "cancel-key-1");
  await updateOrderStatus(101, "ACCEPTED", "status-key-1");
  await getNotifications();
  await replayDeadLetter(7);

  expect(fetchMock).toHaveBeenNthCalledWith(
    1,
    "/payments/checkout",
    expect.objectContaining({
      method: "POST",
      credentials: "include",
      headers: expect.objectContaining({
        "Content-Type": "application/json",
        "Idempotency-Key": "pay-key-1",
      }),
    })
  );
  expect(fetchMock).toHaveBeenNthCalledWith(
    2,
    "/orders/101/cancel",
    expect.objectContaining({
      method: "POST",
      credentials: "include",
      headers: { "Idempotency-Key": "cancel-key-1" },
    })
  );
  expect(fetchMock).toHaveBeenNthCalledWith(
    3,
    "/orders/101/status",
    expect.objectContaining({
      method: "PATCH",
      credentials: "include",
      headers: expect.objectContaining({
        "Content-Type": "application/json",
        "Idempotency-Key": "status-key-1",
      }),
      body: JSON.stringify({ status: "ACCEPTED" }),
    })
  );
  expect(fetchMock).toHaveBeenNthCalledWith(
    4,
    "/notifications",
    expect.objectContaining({ credentials: "include" })
  );
  expect(fetchMock).toHaveBeenNthCalledWith(
    5,
    "/dead-letters/7/replay",
    expect.objectContaining({
      method: "POST",
      credentials: "include",
    })
  );
  expect(createIdempotencyKey()).toEqual(expect.any(String));
});

test("guests can browse restaurants and menu items before signing in", async () => {
  jest.spyOn(global, "fetch").mockImplementation((url) => {
    if (url === "/me") {
      return Promise.resolve(new Response("", { status: 401 }));
    }

    if (url === "/restaurants/menu") {
      return Promise.resolve(
        jsonResponse([
          {
            id: 1,
            name: "Burger King",
            address: "773 N Mathilda Ave",
            phone: "(408) 736-0101",
            image_url: "",
            menu_items: [
              {
                id: 10,
                restaurant_id: 1,
                name: "Whopper",
                description: "Flame grilled burger",
                price: 6.39,
                image_url: "",
              },
            ],
          },
        ])
      );
    }

    if (url === "/restaurant/1/menu") {
      return Promise.resolve(
        jsonResponse([
          {
            id: 10,
            restaurant_id: 1,
            name: "Whopper",
            description: "Flame grilled burger",
            price: 6.39,
            image_url: "",
          },
        ])
      );
    }

    return Promise.resolve(jsonResponse({ order_items: [], total_price: 0 }));
  });

  render(<App />);

  expect(await screen.findByText("Burger King")).toBeInTheDocument();
  expect(await screen.findByText("Whopper")).toBeInTheDocument();
  expect(screen.getByText(/sign in to add/i)).toBeInTheDocument();
  await waitFor(() => {
    expect(global.fetch).toHaveBeenCalledWith(
      "/restaurants/menu",
      expect.objectContaining({ credentials: "include" })
    );
  });
});
