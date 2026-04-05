import {
  CompassOutlined,
  LogoutOutlined,
  ShoppingOutlined,
  StarFilled,
  UserOutlined,
} from "@ant-design/icons";
import { Avatar, Button, Space, Spin, Typography, message } from "antd";
import { useEffect, useMemo, useState } from "react";
import "./App.css";
import FoodList from "./components/FoodList";
import LoginForm from "./components/LoginForm";
import MyCart from "./components/MyCart";
import OrderHistory from "./components/OrderHistory";
import SignupForm from "./components/SignupForm";
import { getCurrentUser, logout } from "./utils";

const { Paragraph, Text, Title } = Typography;

const getGreeting = () => {
  const hour = new Date().getHours();

  if (hour < 12) {
    return "Good morning";
  }

  if (hour < 18) {
    return "Good afternoon";
  }

  return "Good evening";
};

function App() {
  const [currentUser, setCurrentUser] = useState(null);
  const [loadingSession, setLoadingSession] = useState(true);
  const [cartVersion, setCartVersion] = useState(0);

  const refreshSession = () => {
    setLoadingSession(true);
    getCurrentUser()
      .then((user) => {
        setCurrentUser(user);
      })
      .catch(() => {
        setCurrentUser(null);
      })
      .finally(() => {
        setLoadingSession(false);
      });
  };

  useEffect(() => {
    refreshSession();
  }, []);

  const handleItemAdded = () => {
    setCartVersion((value) => value + 1);
  };

  const handleCartChanged = () => {
    setCartVersion((value) => value + 1);
  };

  const handleLogout = () => {
    logout()
      .then(() => {
        setCurrentUser(null);
        setCartVersion((value) => value + 1);
        message.success("Logged out");
      })
      .catch((err) => {
        message.error(err.message);
      });
  };

  const heroStats = useMemo(
    () => [
      {
        label: "Curated menus",
        value: currentUser ? "Ready now" : "Fresh demo",
      },
      {
        label: "Cart flow",
        value: currentUser ? "Live editing" : "Fast checkout",
      },
      {
        label: "Mood",
        value: "Bold and warm",
      },
    ],
    [currentUser]
  );

  return (
    <div className="app-shell">
      <div className="app-shell__glow app-shell__glow--one" />
      <div className="app-shell__glow app-shell__glow--two" />

      <div className="app-frame">
        <header className="topbar">
          <div className="brand-mark">
            <div className="brand-mark__icon">
              <ShoppingOutlined />
            </div>
            <div>
              <Text className="brand-mark__eyebrow">Luc1fer&apos;s Food Bank</Text>
              <Title level={2} className="brand-mark__title">
                Delivery with a sharper point of view.
              </Title>
            </div>
          </div>

          <Space size="middle" wrap className="topbar__actions">
            {currentUser ? (
              <>
                <div className="user-pill">
                  <Avatar
                    className="user-pill__avatar"
                    icon={<UserOutlined />}
                    size="small"
                  />
                  <div>
                    <Text className="user-pill__label">{getGreeting()}</Text>
                    <Text className="user-pill__value">
                      {currentUser.first_name || currentUser.email}
                    </Text>
                  </div>
                </div>
                <Button
                  className="topbar__logout"
                  icon={<LogoutOutlined />}
                  onClick={handleLogout}
                >
                  Logout
                </Button>
              </>
            ) : (
              <SignupForm
                buttonLabel="Create account"
                buttonClassName="topbar__register"
              />
            )}
          </Space>
        </header>

        <section className="hero-panel">
          <div className="hero-copy">
            <Text className="hero-copy__eyebrow">
              <StarFilled />
              Fashionably fast food ordering
            </Text>
            <Title level={1} className="hero-copy__title">
              A warmer, more tactile storefront for your ordering flow.
            </Title>
            <Paragraph className="hero-copy__text">
              This redesign keeps the existing demo APIs, but reshapes the experience
              into something more editorial: stronger composition, richer surfaces,
              and interactions that feel intentional instead of default.
            </Paragraph>

            <div className="hero-stats">
              {heroStats.map((item) => (
                <div className="hero-stat" key={item.label}>
                  <Text className="hero-stat__label">{item.label}</Text>
                  <Text className="hero-stat__value">{item.value}</Text>
                </div>
              ))}
            </div>

            {currentUser ? (
              <div className="hero-actions">
                <OrderHistory />
                <MyCart cartVersion={cartVersion} onCartChanged={handleCartChanged} />
              </div>
            ) : (
              <div className="hero-copy__feature-strip">
                <div className="feature-tile">
                  <CompassOutlined />
                  <span>Discover restaurants faster</span>
                </div>
                <div className="feature-tile">
                  <ShoppingOutlined />
                  <span>Edit cart inline before checkout</span>
                </div>
              </div>
            )}
          </div>

          <div className="hero-side">
            {loadingSession ? (
              <div className="loading-panel">
                <Spin size="large" />
                <Text className="loading-panel__text">Loading your session...</Text>
              </div>
            ) : currentUser ? (
              <div className="status-panel">
                <Text className="status-panel__eyebrow">Session status</Text>
                <Title level={3} className="status-panel__title">
                  Signed in and ready to browse.
                </Title>
                <Paragraph className="status-panel__text">
                  Open your bag, review past orders, and keep building the cart without
                  leaving the menu.
                </Paragraph>
                <div className="status-panel__note">
                  <span>Current mode</span>
                  <strong>Interactive shopping demo</strong>
                </div>
              </div>
            ) : (
              <div className="auth-stack">
                <LoginForm onSuccess={refreshSession} />
                <div className="auth-promo">
                  <Text className="auth-promo__eyebrow">New here?</Text>
                  <Title level={4} className="auth-promo__title">
                    Open an account and test the full flow.
                  </Title>
                  <SignupForm
                    buttonLabel="Register now"
                    buttonClassName="auth-promo__button"
                  />
                </div>
              </div>
            )}
          </div>
        </section>

        {loadingSession ? null : currentUser ? (
          <section className="menu-stage">
            <FoodList onItemAdded={handleItemAdded} />
          </section>
        ) : (
          <section className="guest-banner">
            <Text className="guest-banner__eyebrow">Preview the experience</Text>
            <Title level={3} className="guest-banner__title">
              Sign in with the demo account to explore menus, adjust quantities, and
              place orders.
            </Title>
          </section>
        )}
      </div>
    </div>
  );
}

export default App;
