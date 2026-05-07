import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import "./App.css";
import {
  addItemToCart,
  cancelOrder,
  checkoutWithPayment,
  createIdempotencyKey,
  getCart,
  getCurrentUser,
  getMenus,
  getNotifications,
  getOrders,
  getRestaurants,
  login,
  logout,
  removeCartItem,
  replayDeadLetter,
  signup,
  updateCartItem,
  updateOrderStatus,
} from "./utils";

const emptyCart = {
  order_items: [],
  total_price: 0,
};

const orderStatuses = [
  "PLACED",
  "PAID",
  "ACCEPTED",
  "PREPARING",
  "COMPLETED",
  "CANCELLED",
];

const cancellableStatuses = new Set(["PLACED", "PAID", "ACCEPTED"]);

const initialPaymentForm = {
  cardholder_name: "",
  card_number: "",
  expiry: "",
  cvv: "",
};

const formatCurrency = (value) => `$${Number(value || 0).toFixed(2)}`;

const formatDateTime = (value) => {
  if (!value) {
    return "Pending timestamp";
  }

  const date = new Date(value);

  if (Number.isNaN(date.getTime())) {
    return value;
  }

  return date.toLocaleString();
};

const formatCardNumberInput = (value = "") =>
  value
    .replace(/\D/g, "")
    .slice(0, 16)
    .replace(/(\d{4})(?=\d)/g, "$1 ");

const formatExpiryInput = (value = "") => {
  const digits = value.replace(/\D/g, "").slice(0, 4);

  if (digits.length <= 2) {
    return digits;
  }

  return `${digits.slice(0, 2)}/${digits.slice(2)}`;
};

const formatSecurityCode = (value = "") => value.replace(/\D/g, "").slice(0, 4);

const itemCount = (cartData) =>
  (cartData?.order_items || []).reduce((sum, item) => sum + (item.quantity || 0), 0);

const buildCartItemMap = (cartData) =>
  (cartData?.order_items || []).reduce((cartMap, item) => {
    cartMap[item.menu_item_id] = {
      orderItemId: item.order_item_id,
      quantity: item.quantity,
    };
    return cartMap;
  }, {});

const errorCopy = (error, fallback) => {
  if (!error) {
    return fallback;
  }

  const prefix = error.status ? `${error.status}: ` : "";
  const trace = error.traceId ? ` Trace ${error.traceId}.` : "";
  return `${prefix}${error.message || fallback}${trace}`;
};

const fallbackImage = (label) => (
  <div className="image-fallback" aria-hidden="true">
    <span>{label?.slice(0, 2).toUpperCase() || "LF"}</span>
  </div>
);

const Alert = ({ tone = "neutral", children }) => {
  if (!children) {
    return null;
  }

  return (
    <div className={`alert alert--${tone}`} role={tone === "danger" ? "alert" : "status"}>
      {children}
    </div>
  );
};

function App() {
  const [currentUser, setCurrentUser] = useState(null);
  const [sessionStatus, setSessionStatus] = useState("loading");
  const [authMode, setAuthMode] = useState("login");
  const [authPending, setAuthPending] = useState(false);
  const [authMessage, setAuthMessage] = useState("");
  const [authError, setAuthError] = useState("");

  const [restaurants, setRestaurants] = useState([]);
  const [selectedRestaurantId, setSelectedRestaurantId] = useState(null);
  const [restaurantLoading, setRestaurantLoading] = useState(true);
  const [restaurantError, setRestaurantError] = useState("");
  const [menuItems, setMenuItems] = useState([]);
  const [menuLoading, setMenuLoading] = useState(false);
  const [menuError, setMenuError] = useState("");
  const [searchValue, setSearchValue] = useState("");

  const [cartData, setCartData] = useState(emptyCart);
  const [cartLoading, setCartLoading] = useState(false);
  const [cartError, setCartError] = useState("");
  const [pendingMenuItemId, setPendingMenuItemId] = useState(null);
  const [pendingCartItemId, setPendingCartItemId] = useState(null);

  const [paymentForm, setPaymentForm] = useState(initialPaymentForm);
  const [paymentPending, setPaymentPending] = useState(false);
  const [paymentError, setPaymentError] = useState("");
  const [confirmation, setConfirmation] = useState(null);
  const paymentAttemptKeyRef = useRef(null);
  const paymentPayloadRef = useRef("");

  const [orders, setOrders] = useState([]);
  const [ordersLoading, setOrdersLoading] = useState(false);
  const [ordersError, setOrdersError] = useState("");
  const [cancelingOrderId, setCancelingOrderId] = useState(null);
  const cancelAttemptKeysRef = useRef({});

  const [notifications, setNotifications] = useState([]);
  const [notificationsLoading, setNotificationsLoading] = useState(false);
  const [notificationsError, setNotificationsError] = useState("");
  const [notificationsPolling, setNotificationsPolling] = useState(true);

  const [activePanel, setActivePanel] = useState("cart");
  const [adminOrderResult, setAdminOrderResult] = useState(null);
  const [adminReplayResult, setAdminReplayResult] = useState(null);
  const [adminError, setAdminError] = useState("");
  const [adminPending, setAdminPending] = useState("");
  const adminStatusAttemptKeyRef = useRef(null);
  const adminStatusPayloadRef = useRef("");

  const selectedRestaurant = useMemo(
    () => restaurants.find((restaurant) => restaurant.id === selectedRestaurantId),
    [restaurants, selectedRestaurantId]
  );

  const cartItemsByMenuId = useMemo(() => buildCartItemMap(cartData), [cartData]);

  const filteredMenuItems = useMemo(() => {
    const keyword = searchValue.trim().toLowerCase();

    if (!keyword) {
      return menuItems;
    }

    return menuItems.filter((item) =>
      `${item.name} ${item.description || ""}`.toLowerCase().includes(keyword)
    );
  }, [menuItems, searchValue]);

  const cartItemTotal = useMemo(() => itemCount(cartData), [cartData]);

  const sessionLabel = currentUser
    ? `${currentUser.first_name || currentUser.email} is signed in`
    : sessionStatus === "loading"
      ? "Restoring session"
      : "Guest browsing";

  const refreshSession = () => {
    setSessionStatus("loading");
    return getCurrentUser()
      .then((user) => {
        setCurrentUser(user);
        setSessionStatus("signed-in");
        return user;
      })
      .catch(() => {
        setCurrentUser(null);
        setSessionStatus("guest");
        return null;
      });
  };

  const refreshRestaurants = () => {
    setRestaurantLoading(true);
    setRestaurantError("");
    return getRestaurants()
      .then((data) => {
        const nextRestaurants = Array.isArray(data) ? data : [];
        setRestaurants(nextRestaurants);
        setSelectedRestaurantId((currentId) => {
          if (currentId && nextRestaurants.some((restaurant) => restaurant.id === currentId)) {
            return currentId;
          }
          return nextRestaurants[0]?.id || null;
        });
      })
      .catch((error) => {
        setRestaurantError(errorCopy(error, "Unable to load restaurants."));
      })
      .finally(() => {
        setRestaurantLoading(false);
      });
  };

  const refreshMenu = (restaurantId) => {
    if (!restaurantId) {
      setMenuItems([]);
      return Promise.resolve();
    }

    setMenuLoading(true);
    setMenuError("");
    return getMenus(restaurantId)
      .then((data) => {
        setMenuItems(Array.isArray(data) ? data : []);
      })
      .catch((error) => {
        setMenuItems([]);
        setMenuError(errorCopy(error, "Unable to load the menu."));
      })
      .finally(() => {
        setMenuLoading(false);
      });
  };

  const refreshCart = useCallback((showSpinner = true) => {
    if (!currentUser) {
      setCartData(emptyCart);
      return Promise.resolve(emptyCart);
    }

    setCartLoading(showSpinner);
    setCartError("");
    return getCart()
      .then((data) => {
        const nextCart = data || emptyCart;
        setCartData(nextCart);
        return nextCart;
      })
      .catch((error) => {
        setCartError(errorCopy(error, "Unable to load the cart."));
        throw error;
      })
      .finally(() => {
        setCartLoading(false);
      });
  }, [currentUser]);

  const refreshOrders = useCallback((showSpinner = true) => {
    if (!currentUser) {
      setOrders([]);
      return Promise.resolve([]);
    }

    setOrdersLoading(showSpinner);
    setOrdersError("");
    return getOrders()
      .then((data) => {
        const nextOrders = Array.isArray(data) ? data : [];
        setOrders(nextOrders);
        return nextOrders;
      })
      .catch((error) => {
        setOrdersError(errorCopy(error, "Unable to load orders."));
        throw error;
      })
      .finally(() => {
        setOrdersLoading(false);
      });
  }, [currentUser]);

  const refreshNotifications = useCallback((showSpinner = true) => {
    if (!currentUser) {
      setNotifications([]);
      return Promise.resolve([]);
    }

    setNotificationsLoading(showSpinner);
    setNotificationsError("");
    return getNotifications()
      .then((data) => {
        const nextNotifications = Array.isArray(data) ? data : [];
        setNotifications(nextNotifications);
        return nextNotifications;
      })
      .catch((error) => {
        setNotificationsError(errorCopy(error, "Unable to load notifications."));
        throw error;
      })
      .finally(() => {
        setNotificationsLoading(false);
      });
  }, [currentUser]);

  useEffect(() => {
    refreshSession();
    refreshRestaurants();
  }, []);

  useEffect(() => {
    refreshMenu(selectedRestaurantId);
  }, [selectedRestaurantId]);

  useEffect(() => {
    if (!currentUser) {
      setCartData(emptyCart);
      setOrders([]);
      setNotifications([]);
      return;
    }

    refreshCart(false).catch(() => {});
    refreshOrders(false).catch(() => {});
    refreshNotifications(false).catch(() => {});
  }, [currentUser, refreshCart, refreshNotifications, refreshOrders]);

  useEffect(() => {
    if (!currentUser || !notificationsPolling) {
      return undefined;
    }

    const poll = window.setInterval(() => {
      refreshNotifications(false).catch(() => {});
    }, 15000);

    return () => window.clearInterval(poll);
  }, [currentUser, notificationsPolling, refreshNotifications]);

  const requireSession = (message) => {
    setAuthMode("login");
    setAuthMessage("");
    setAuthError(message);
    const authPanel = document.getElementById("auth-panel");

    if (authPanel) {
      authPanel.scrollIntoView({ behavior: "smooth", block: "center" });
    }
  };

  const handleLogin = (event) => {
    event.preventDefault();
    const form = new FormData(event.currentTarget);
    const username = String(form.get("username") || "").trim();
    const password = String(form.get("password") || "");

    if (!username || !password) {
      setAuthError("Email and password are required.");
      return;
    }

    setAuthPending(true);
    setAuthError("");
    setAuthMessage("");
    login({ username, password })
      .then(() => refreshSession())
      .then((user) => {
        if (user) {
          setAuthMessage("Signed in. Cart, orders, and notifications are now active.");
        }
      })
      .catch((error) => {
        setAuthError(errorCopy(error, "Login failed."));
      })
      .finally(() => {
        setAuthPending(false);
      });
  };

  const handleSignup = (event) => {
    event.preventDefault();
    const form = new FormData(event.currentTarget);
    const data = {
      email: String(form.get("email") || "").trim(),
      password: String(form.get("password") || ""),
      first_name: String(form.get("first_name") || "").trim(),
      last_name: String(form.get("last_name") || "").trim(),
    };

    if (!data.email || !data.password || !data.first_name || !data.last_name) {
      setAuthError("Email, password, first name, and last name are required.");
      return;
    }

    setAuthPending(true);
    setAuthError("");
    setAuthMessage("");
    signup(data)
      .then(() => {
        event.currentTarget.reset();
        setAuthMode("login");
        setAuthMessage("Account created. Sign in with the new credentials.");
      })
      .catch((error) => {
        setAuthError(errorCopy(error, "Signup failed."));
      })
      .finally(() => {
        setAuthPending(false);
      });
  };

  const handleLogout = () => {
    setAuthError("");
    setAuthMessage("");
    logout()
      .catch(() => {})
      .finally(() => {
        setCurrentUser(null);
        setSessionStatus("guest");
        setCartData(emptyCart);
        setConfirmation(null);
        setAuthMode("login");
        setAuthMessage("Signed out.");
      });
  };

  const handleAddMenuItem = (menuItemId) => {
    if (!currentUser) {
      requireSession("Sign in to add menu items to your cart.");
      return;
    }

    setPendingMenuItemId(menuItemId);
    addItemToCart(menuItemId)
      .then(() => refreshCart(false))
      .catch((error) => {
        setCartError(errorCopy(error, "Unable to add item."));
      })
      .finally(() => {
        setPendingMenuItemId(null);
      });
  };

  const handleDecreaseMenuItem = (menuItemId) => {
    const cartItem = cartItemsByMenuId[menuItemId];

    if (!cartItem?.orderItemId) {
      return;
    }

    setPendingMenuItemId(menuItemId);
    updateCartItem(cartItem.orderItemId, Math.max((cartItem.quantity || 1) - 1, 0))
      .then(() => refreshCart(false))
      .catch((error) => {
        setCartError(errorCopy(error, "Unable to update item."));
      })
      .finally(() => {
        setPendingMenuItemId(null);
      });
  };

  const handleSetCartQuantity = (item, nextQuantity) => {
    setPendingCartItemId(item.order_item_id);
    const request =
      nextQuantity <= 0
        ? removeCartItem(item.order_item_id)
        : updateCartItem(item.order_item_id, nextQuantity);

    request
      .then(() => refreshCart(false))
      .catch((error) => {
        setCartError(errorCopy(error, "Unable to update cart quantity."));
      })
      .finally(() => {
        setPendingCartItemId(null);
      });
  };

  const updatePaymentForm = (field, value) => {
    let nextValue = value;

    if (field === "card_number") {
      nextValue = formatCardNumberInput(value);
    }

    if (field === "expiry") {
      nextValue = formatExpiryInput(value);
    }

    if (field === "cvv") {
      nextValue = formatSecurityCode(value);
    }

    paymentAttemptKeyRef.current = null;
    paymentPayloadRef.current = "";
    setPaymentForm((current) => ({ ...current, [field]: nextValue }));
  };

  const validatePaymentForm = () => {
    const cardNumber = paymentForm.card_number.replace(/\s+/g, "");

    if (!paymentForm.cardholder_name.trim()) {
      return "Cardholder name is required.";
    }

    if (!/^\d{16}$/.test(cardNumber)) {
      return "Card number must contain 16 digits.";
    }

    if (!/^(0[1-9]|1[0-2])\/\d{2}$/.test(paymentForm.expiry)) {
      return "Expiry must use MM/YY.";
    }

    if (!/^\d{3,4}$/.test(paymentForm.cvv)) {
      return "CVV must contain 3 or 4 digits.";
    }

    return "";
  };

  const handlePaymentSubmit = (event) => {
    event.preventDefault();

    if (!currentUser) {
      requireSession("Sign in before checkout.");
      return;
    }

    if (!cartData?.order_items?.length) {
      setPaymentError("Your cart is empty.");
      return;
    }

    const validationError = validatePaymentForm();

    if (validationError) {
      setPaymentError(validationError);
      return;
    }

    const payload = {
      cardholder_name: paymentForm.cardholder_name.trim(),
      card_number: paymentForm.card_number.replace(/\s+/g, ""),
      expiry: paymentForm.expiry,
      cvv: paymentForm.cvv,
    };
    const serializedPayload = JSON.stringify(payload);

    if (paymentPayloadRef.current && paymentPayloadRef.current !== serializedPayload) {
      paymentAttemptKeyRef.current = null;
    }

    paymentPayloadRef.current = serializedPayload;
    paymentAttemptKeyRef.current = paymentAttemptKeyRef.current || createIdempotencyKey();
    setPaymentPending(true);
    setPaymentError("");

    checkoutWithPayment(payload, paymentAttemptKeyRef.current)
      .then((order) => {
        setConfirmation(order);
        setCartData(emptyCart);
        setPaymentForm(initialPaymentForm);
        paymentAttemptKeyRef.current = null;
        paymentPayloadRef.current = "";
        refreshOrders(false).catch(() => {});
        refreshNotifications(false).catch(() => {});
      })
      .catch((error) => {
        setPaymentError(errorCopy(error, "Payment checkout failed."));
      })
      .finally(() => {
        setPaymentPending(false);
      });
  };

  const handleCancelOrder = (order) => {
    if (!cancellableStatuses.has(order.status)) {
      return;
    }

    const key = cancelAttemptKeysRef.current[order.id] || createIdempotencyKey();
    cancelAttemptKeysRef.current[order.id] = key;
    setCancelingOrderId(order.id);
    setOrdersError("");
    cancelOrder(order.id, key)
      .then((updatedOrder) => {
        cancelAttemptKeysRef.current[order.id] = null;
        setOrders((currentOrders) =>
          currentOrders.map((item) => (item.id === updatedOrder.id ? updatedOrder : item))
        );
        refreshNotifications(false).catch(() => {});
      })
      .catch((error) => {
        setOrdersError(errorCopy(error, "Unable to cancel order."));
      })
      .finally(() => {
        setCancelingOrderId(null);
      });
  };

  const handleAdminStatusSubmit = (event) => {
    event.preventDefault();
    const form = new FormData(event.currentTarget);
    const orderId = String(form.get("order_id") || "").trim();
    const status = String(form.get("status") || "").trim();

    if (!orderId || !status) {
      setAdminError("Order ID and target status are required.");
      return;
    }

    const payloadKey = `${orderId}:${status}`;

    if (adminStatusPayloadRef.current && adminStatusPayloadRef.current !== payloadKey) {
      adminStatusAttemptKeyRef.current = null;
    }

    adminStatusPayloadRef.current = payloadKey;
    adminStatusAttemptKeyRef.current =
      adminStatusAttemptKeyRef.current || createIdempotencyKey();
    setAdminPending("status");
    setAdminError("");
    setAdminOrderResult(null);

    updateOrderStatus(orderId, status, adminStatusAttemptKeyRef.current)
      .then((order) => {
        setAdminOrderResult(order);
        adminStatusPayloadRef.current = "";
        adminStatusAttemptKeyRef.current = null;
        refreshOrders(false).catch(() => {});
        refreshNotifications(false).catch(() => {});
      })
      .catch((error) => {
        setAdminError(errorCopy(error, "Unable to update order status."));
      })
      .finally(() => {
        setAdminPending("");
      });
  };

  const handleDeadLetterReplay = (event) => {
    event.preventDefault();
    const form = new FormData(event.currentTarget);
    const deadLetterEventId = String(form.get("dead_letter_id") || "").trim();

    if (!deadLetterEventId) {
      setAdminError("Dead-letter event ID is required.");
      return;
    }

    setAdminPending("replay");
    setAdminError("");
    setAdminReplayResult(null);
    replayDeadLetter(deadLetterEventId)
      .then((result) => {
        setAdminReplayResult(result);
      })
      .catch((error) => {
        setAdminError(errorCopy(error, "Unable to replay dead-letter event."));
      })
      .finally(() => {
        setAdminPending("");
      });
  };

  const panelTabs = [
    ["cart", `Cart (${cartItemTotal})`],
    ["orders", "Orders"],
    ["notifications", `Notifications (${notifications.length})`],
    ["admin", "Admin tools"],
  ];

  return (
    <div className="app-shell">
      <header className="topbar">
        <div className="brand-block">
          <div className="brand-icon" aria-hidden="true">LF</div>
          <div>
            <p className="brand-name">Luc1fer&apos;s Food Bank</p>
            <h1>Operational food ordering MVP</h1>
          </div>
        </div>

        <div className="session-strip" aria-live="polite">
          <span className={`status-dot status-dot--${currentUser ? "ok" : "muted"}`} />
          <div>
            <strong>{sessionLabel}</strong>
            <span>
              {currentUser
                ? currentUser.email
                : "Public restaurant and menu browsing stays available."}
            </span>
          </div>
          {currentUser ? (
            <button className="ghost-button" type="button" onClick={handleLogout}>
              Logout
            </button>
          ) : null}
        </div>
      </header>

      <main className="mvp-grid">
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
                onChange={(event) => setSearchValue(event.target.value)}
              />
            </label>
          </div>

          <Alert tone="danger">{restaurantError}</Alert>
          {restaurantLoading ? (
            <div className="loading-row">Loading restaurants...</div>
          ) : restaurants.length === 0 ? (
            <div className="empty-state">No restaurants are available from the backend.</div>
          ) : (
            <div className="restaurant-rail" aria-label="Restaurants">
              {restaurants.map((restaurant) => (
                <button
                  className={`restaurant-button ${
                    restaurant.id === selectedRestaurantId ? "restaurant-button--active" : ""
                  }`}
                  key={restaurant.id}
                  type="button"
                  onClick={() => setSelectedRestaurantId(restaurant.id)}
                >
                  <span>#{restaurant.id} {restaurant.name}</span>
                  <small>{restaurant.phone || "No phone listed"}</small>
                </button>
              ))}
            </div>
          )}

          {selectedRestaurant ? (
            <article className="restaurant-summary">
              <div className="restaurant-summary__media">
                {selectedRestaurant.image_url ? (
                  <img src={selectedRestaurant.image_url} alt={selectedRestaurant.name} />
                ) : (
                  fallbackImage(selectedRestaurant.name)
                )}
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
              {filteredMenuItems.map((item) => {
                const cartItem = cartItemsByMenuId[item.id];
                const quantity = cartItem?.quantity || 0;
                const pending = pendingMenuItemId === item.id;

                return (
                  <article className="menu-card" key={item.id}>
                    <div className="menu-card__media">
                      {item.image_url ? (
                        <img src={item.image_url} alt={item.name} />
                      ) : (
                        fallbackImage(item.name)
                      )}
                      <span>{formatCurrency(item.price)}</span>
                    </div>
                    <div className="menu-card__body">
                      <div>
                        <p className="menu-card__meta">
                          Item #{item.id} · Restaurant #{item.restaurant_id || selectedRestaurantId}
                        </p>
                        <h3>{item.name}</h3>
                        <p>{item.description || "No description provided by the backend."}</p>
                      </div>

                      <div className="menu-actions">
                        {currentUser && quantity > 0 ? (
                          <div className="stepper" aria-label={`${item.name} quantity`}>
                            <button
                              type="button"
                              disabled={pending}
                              onClick={() => handleDecreaseMenuItem(item.id)}
                              aria-label={`Decrease ${item.name}`}
                            >
                              -
                            </button>
                            <strong>{quantity}</strong>
                            <button
                              type="button"
                              disabled={pending}
                              onClick={() => handleAddMenuItem(item.id)}
                              aria-label={`Increase ${item.name}`}
                            >
                              +
                            </button>
                          </div>
                        ) : (
                          <button
                            className="primary-button"
                            type="button"
                            disabled={pending}
                            onClick={() => handleAddMenuItem(item.id)}
                          >
                            {currentUser ? "Add to cart" : "Sign in to add"}
                          </button>
                        )}
                      </div>
                    </div>
                  </article>
                );
              })}
            </div>
          )}
        </section>

        <aside className="side-workbench" aria-label="Account and order tools">
          <section className="auth-panel" id="auth-panel">
            <div className="panel-heading">
              <p className="section-kicker">Session gate</p>
              <h2>{currentUser ? "Account restored" : "Login or signup"}</h2>
            </div>

            {sessionStatus === "loading" ? (
              <div className="loading-row">Checking /me...</div>
            ) : currentUser ? (
              <div className="signed-in-card">
                <strong>{currentUser.first_name} {currentUser.last_name}</strong>
                <span>{currentUser.email}</span>
                <button className="ghost-button" type="button" onClick={handleLogout}>
                  Logout
                </button>
              </div>
            ) : (
              <>
                <div className="auth-tabs" role="tablist" aria-label="Authentication forms">
                  <button
                    className={authMode === "login" ? "active" : ""}
                    type="button"
                    onClick={() => {
                      setAuthMode("login");
                      setAuthError("");
                    }}
                  >
                    Login
                  </button>
                  <button
                    className={authMode === "signup" ? "active" : ""}
                    type="button"
                    onClick={() => {
                      setAuthMode("signup");
                      setAuthError("");
                    }}
                  >
                    Signup
                  </button>
                </div>

                <Alert tone="success">{authMessage}</Alert>
                <Alert tone="danger">{authError}</Alert>

                {authMode === "login" ? (
                  <form className="stack-form" onSubmit={handleLogin}>
                    <label>
                      Email
                      <input name="username" type="email" placeholder="demo@laifood.com" required />
                    </label>
                    <label>
                      Password
                      <input name="password" type="password" placeholder="demo123" required />
                    </label>
                    <div className="demo-credentials">
                      <code>demo@laifood.com</code>
                      <code>demo123</code>
                    </div>
                    <button className="primary-button" type="submit" disabled={authPending}>
                      {authPending ? "Signing in..." : "Sign in"}
                    </button>
                  </form>
                ) : (
                  <form className="stack-form" onSubmit={handleSignup}>
                    <label>
                      Email
                      <input name="email" type="email" required />
                    </label>
                    <label>
                      Password
                      <input name="password" type="password" required />
                    </label>
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
                    <button className="primary-button" type="submit" disabled={authPending}>
                      {authPending ? "Creating..." : "Create account"}
                    </button>
                  </form>
                )}
              </>
            )}
          </section>

          <section className="operations-panel">
            <div className="panel-tabs" role="tablist" aria-label="Customer operations">
              {panelTabs.map(([key, label]) => (
                <button
                  className={activePanel === key ? "active" : ""}
                  key={key}
                  type="button"
                  onClick={() => setActivePanel(key)}
                >
                  {label}
                </button>
              ))}
            </div>

            {activePanel === "cart" ? (
              <section className="panel-body" aria-label="Cart and payment checkout">
                <div className="panel-heading">
                  <p className="section-kicker">Cart review</p>
                  <h2>{formatCurrency(cartData.total_price)} total</h2>
                </div>
                {!currentUser ? (
                  <div className="empty-state">Sign in to load and edit your cart.</div>
                ) : (
                  <>
                    <Alert tone="danger">{cartError}</Alert>
                    {cartLoading ? <div className="loading-row">Loading cart...</div> : null}
                    {cartData?.order_items?.length ? (
                      <div className="cart-lines">
                        {cartData.order_items.map((item) => (
                          <article className="cart-line" key={item.order_item_id}>
                            <div className="cart-line__media">
                              {item.menu_item_image_url ? (
                                <img src={item.menu_item_image_url} alt={item.menu_item_name} />
                              ) : (
                                fallbackImage(item.menu_item_name)
                              )}
                            </div>
                            <div>
                              <strong>{item.menu_item_name}</strong>
                              <span>{formatCurrency(item.price)} each</span>
                              <span>Subtotal {formatCurrency(item.price * item.quantity)}</span>
                            </div>
                            <div className="stepper cart-line__stepper">
                              <button
                                type="button"
                                disabled={pendingCartItemId === item.order_item_id}
                                onClick={() => handleSetCartQuantity(item, item.quantity - 1)}
                                aria-label={`Decrease ${item.menu_item_name}`}
                              >
                                -
                              </button>
                              <strong>{item.quantity}</strong>
                              <button
                                type="button"
                                disabled={pendingCartItemId === item.order_item_id}
                                onClick={() => handleSetCartQuantity(item, item.quantity + 1)}
                                aria-label={`Increase ${item.menu_item_name}`}
                              >
                                +
                              </button>
                              <button
                                className="danger-icon-button"
                                type="button"
                                disabled={pendingCartItemId === item.order_item_id}
                                onClick={() => handleSetCartQuantity(item, 0)}
                                aria-label={`Remove ${item.menu_item_name}`}
                              >
                                x
                              </button>
                            </div>
                          </article>
                        ))}
                      </div>
                    ) : (
                      <div className="empty-state">Your cart is empty. Checkout is disabled.</div>
                    )}

                    {confirmation ? (
                      <article className="confirmation-card">
                        <p className="section-kicker">Order confirmation</p>
                        <h3>Order #{confirmation.id} · {confirmation.status}</h3>
                        <p>
                          {formatCurrency(confirmation.total_price)} ·{" "}
                          {formatDateTime(confirmation.created_at)}
                        </p>
                        <ul>
                          {(confirmation.items || []).map((item) => (
                            <li key={item.id || item.menu_item_id}>
                              {item.quantity} x {item.menu_item_name} ·{" "}
                              {formatCurrency(item.price * item.quantity)}
                            </li>
                          ))}
                        </ul>
                      </article>
                    ) : null}

                    <form className="payment-form" onSubmit={handlePaymentSubmit}>
                      <div className="panel-heading panel-heading--compact">
                        <p className="section-kicker">Demo payment checkout</p>
                        <h3>{cartItemTotal} item(s), {formatCurrency(cartData.total_price)}</h3>
                      </div>
                      <Alert tone="danger">{paymentError}</Alert>
                      <label>
                        Cardholder name
                        <input
                          value={paymentForm.cardholder_name}
                          onChange={(event) =>
                            updatePaymentForm("cardholder_name", event.target.value)
                          }
                          placeholder="Demo User"
                          disabled={!cartData?.order_items?.length || paymentPending}
                        />
                      </label>
                      <label>
                        Card number
                        <input
                          inputMode="numeric"
                          value={paymentForm.card_number}
                          onChange={(event) =>
                            updatePaymentForm("card_number", event.target.value)
                          }
                          placeholder="4242 4242 4242 4242"
                          disabled={!cartData?.order_items?.length || paymentPending}
                        />
                      </label>
                      <div className="two-fields">
                        <label>
                          Expiry
                          <input
                            inputMode="numeric"
                            value={paymentForm.expiry}
                            onChange={(event) => updatePaymentForm("expiry", event.target.value)}
                            placeholder="12/30"
                            disabled={!cartData?.order_items?.length || paymentPending}
                          />
                        </label>
                        <label>
                          CVV
                          <input
                            inputMode="numeric"
                            value={paymentForm.cvv}
                            onChange={(event) => updatePaymentForm("cvv", event.target.value)}
                            placeholder="123"
                            disabled={!cartData?.order_items?.length || paymentPending}
                          />
                        </label>
                      </div>
                      <button
                        className="primary-button"
                        type="submit"
                        disabled={!cartData?.order_items?.length || paymentPending}
                      >
                        {paymentPending
                          ? "Processing..."
                          : `Pay ${formatCurrency(cartData.total_price)}`}
                      </button>
                    </form>
                  </>
                )}
              </section>
            ) : null}

            {activePanel === "orders" ? (
              <section className="panel-body" aria-label="Order history">
                <div className="panel-heading">
                  <p className="section-kicker">Order history</p>
                  <h2>Newest first from /orders</h2>
                  <button
                    className="ghost-button"
                    type="button"
                    disabled={!currentUser || ordersLoading}
                    onClick={() => refreshOrders(true).catch(() => {})}
                  >
                    Refresh
                  </button>
                </div>
                {!currentUser ? (
                  <div className="empty-state">Sign in to view order history.</div>
                ) : (
                  <>
                    <Alert tone="danger">{ordersError}</Alert>
                    {ordersLoading ? <div className="loading-row">Loading orders...</div> : null}
                    {orders.length === 0 ? (
                      <div className="empty-state">No orders have been placed yet.</div>
                    ) : (
                      <div className="order-list">
                        {orders.map((order) => (
                          <article className="order-card" key={order.id}>
                            <div className="order-card__top">
                              <div>
                                <p className="section-kicker">Order #{order.id}</p>
                                <h3>{formatCurrency(order.total_price)}</h3>
                              </div>
                              <span className={`status-badge status-badge--${order.status}`}>
                                {order.status}
                              </span>
                            </div>
                            <p>{formatDateTime(order.created_at)}</p>
                            <ul>
                              {(order.items || []).map((item) => (
                                <li key={item.id || item.menu_item_id}>
                                  {item.quantity} x {item.menu_item_name}
                                </li>
                              ))}
                            </ul>
                            <button
                              className="ghost-button"
                              type="button"
                              disabled={
                                !cancellableStatuses.has(order.status) ||
                                cancelingOrderId === order.id
                              }
                              onClick={() => handleCancelOrder(order)}
                            >
                              {cancelingOrderId === order.id
                                ? "Cancelling..."
                                : cancellableStatuses.has(order.status)
                                  ? "Cancel order"
                                  : "Cancel unavailable"}
                            </button>
                          </article>
                        ))}
                      </div>
                    )}
                  </>
                )}
              </section>
            ) : null}

            {activePanel === "notifications" ? (
              <section className="panel-body" aria-label="Order notifications">
                <div className="panel-heading">
                  <p className="section-kicker">Notifications</p>
                  <h2>Async order lifecycle updates</h2>
                  <button
                    className="ghost-button"
                    type="button"
                    disabled={!currentUser || notificationsLoading}
                    onClick={() => refreshNotifications(true).catch(() => {})}
                  >
                    Refresh
                  </button>
                </div>
                {!currentUser ? (
                  <div className="empty-state">Sign in to view notifications.</div>
                ) : (
                  <>
                    <label className="toggle-row">
                      <input
                        type="checkbox"
                        checked={notificationsPolling}
                        onChange={(event) => setNotificationsPolling(event.target.checked)}
                      />
                      Poll every 15 seconds
                    </label>
                    <Alert tone="danger">{notificationsError}</Alert>
                    {notificationsLoading ? (
                      <div className="loading-row">Loading notifications...</div>
                    ) : null}
                    {notifications.length === 0 ? (
                      <div className="empty-state">
                        No notifications yet. They may lag checkout or status updates.
                      </div>
                    ) : (
                      <div className="notification-list">
                        {notifications.map((notification) => (
                          <article className="notification-card" key={notification.id}>
                            <span>{notification.event_type}</span>
                            <h3>{notification.title}</h3>
                            <p>{notification.message}</p>
                            <small>
                              Order #{notification.order_id} ·{" "}
                              {formatDateTime(notification.created_at)}
                            </small>
                          </article>
                        ))}
                      </div>
                    )}
                  </>
                )}
              </section>
            ) : null}

            {activePanel === "admin" ? (
              <section className="panel-body" aria-label="Manual admin tools">
                <div className="panel-heading">
                  <p className="section-kicker">Manual admin tools</p>
                  <h2>Use IDs from backend logs or database records</h2>
                </div>
                <p className="quiet-copy">
                  The session endpoint does not expose roles, and the backend does not expose
                  all-order or dead-letter list endpoints. These forms intentionally require
                  manual IDs and let the backend enforce ROLE_ADMIN.
                </p>
                <Alert tone="danger">{adminError}</Alert>

                <form className="stack-form admin-form" onSubmit={handleAdminStatusSubmit}>
                  <h3>Order status transition</h3>
                  <div className="two-fields">
                    <label>
                      Order ID
                      <input name="order_id" inputMode="numeric" placeholder="101" />
                    </label>
                    <label>
                      Target status
                      <select name="status" defaultValue="ACCEPTED">
                        {orderStatuses.map((status) => (
                          <option key={status} value={status}>
                            {status}
                          </option>
                        ))}
                      </select>
                    </label>
                  </div>
                  <button className="primary-button" type="submit" disabled={adminPending === "status"}>
                    {adminPending === "status" ? "Updating..." : "Update status"}
                  </button>
                  {adminOrderResult ? (
                    <div className="result-box">
                      Order #{adminOrderResult.id} is {adminOrderResult.status}
                    </div>
                  ) : null}
                </form>

                <form className="stack-form admin-form" onSubmit={handleDeadLetterReplay}>
                  <h3>Dead-letter replay</h3>
                  <label>
                    Dead-letter event ID
                    <input name="dead_letter_id" inputMode="numeric" placeholder="7" />
                  </label>
                  <button className="primary-button" type="submit" disabled={adminPending === "replay"}>
                    {adminPending === "replay" ? "Replaying..." : "Replay event"}
                  </button>
                  {adminReplayResult ? (
                    <div className="result-box">
                      Replay #{adminReplayResult.id}: {adminReplayResult.replay_status}
                    </div>
                  ) : null}
                </form>
              </section>
            ) : null}
          </section>
        </aside>
      </main>
    </div>
  );
}

export default App;
