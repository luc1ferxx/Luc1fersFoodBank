import {
  CreditCardOutlined,
  DeleteOutlined,
  LockOutlined,
  MinusOutlined,
  PlusOutlined,
  SafetyCertificateOutlined,
  ShoppingCartOutlined,
} from "@ant-design/icons";
import {
  Badge,
  Button,
  Drawer,
  Empty,
  Form,
  Input,
  List,
  Space,
  Typography,
  message,
} from "antd";
import { useEffect, useMemo, useRef, useState } from "react";
import { flushSync } from "react-dom";
import {
  addItemToCart,
  checkoutWithPayment,
  createIdempotencyKey,
  getCart,
  removeCartItem,
  updateCartItem,
} from "../utils";

const { Text, Title } = Typography;

const emptyCart = {
  order_items: [],
  total_price: 0,
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

const MyCart = ({ cartVersion, onCartChanged }) => {
  const [cartVisible, setCartVisible] = useState(false);
  const [drawerStep, setDrawerStep] = useState("cart");
  const [cartData, setCartData] = useState(emptyCart);
  const [loading, setLoading] = useState(false);
  const [processingPayment, setProcessingPayment] = useState(false);
  const [updatingItemId, setUpdatingItemId] = useState(null);
  const [paymentForm] = Form.useForm();
  const paymentRequestKeyRef = useRef(null);
  const paymentInFlightRef = useRef(false);

  const refreshCart = (showSpinner = false) => {
    setLoading(showSpinner);
    return getCart()
      .then((data) => {
        setCartData(data || emptyCart);
      })
      .catch((err) => {
        message.error(err.message);
      })
      .finally(() => {
        setLoading(false);
      });
  };

  useEffect(() => {
    refreshCart(cartVisible);

    if (!cartVisible) {
      setDrawerStep("cart");
      paymentRequestKeyRef.current = null;
      paymentInFlightRef.current = false;
      paymentForm.resetFields();
    }
  }, [cartVersion, cartVisible, paymentForm]);

  const closePanels = () => {
    setCartVisible(false);
    setDrawerStep("cart");
    paymentRequestKeyRef.current = null;
    paymentInFlightRef.current = false;
    paymentForm.resetFields();
  };

  const updateItem = (orderItemId, request) => {
    setUpdatingItemId(orderItemId);
    request()
      .then(() => {
        refreshCart(true);
        onCartChanged();
      })
      .catch((err) => {
        message.error(err.message);
      })
      .finally(() => {
        setUpdatingItemId(null);
      });
  };

  const onIncreaseQuantity = (item) => {
    updateItem(item.order_item_id, () => addItemToCart(item.menu_item_id));
  };

  const onDecreaseQuantity = (item) => {
    updateItem(item.order_item_id, () =>
      updateCartItem(item.order_item_id, item.quantity - 1)
    );
  };

  const onRemoveItem = (item) => {
    updateItem(item.order_item_id, () => removeCartItem(item.order_item_id));
  };

  const openPaymentStep = () => {
    if (loading || !cartData?.order_items?.length) {
      return;
    }

    paymentRequestKeyRef.current = null;
    paymentForm.resetFields();
    setDrawerStep("payment");
  };

  const returnToCart = () => {
    paymentRequestKeyRef.current = null;
    paymentInFlightRef.current = false;
    setDrawerStep("cart");
  };

  const onPaymentFinish = (values) => {
    if (paymentInFlightRef.current) {
      return;
    }

    const idempotencyKey =
      paymentRequestKeyRef.current || createIdempotencyKey();
    const feedbackKey = "payment-status";
    paymentRequestKeyRef.current = idempotencyKey;
    paymentInFlightRef.current = true;
    setProcessingPayment(true);
    message.loading({
      content: "Processing payment...",
      duration: 0,
      key: feedbackKey,
    });

    checkoutWithPayment({
      cardholder_name: values.cardholder_name.trim(),
      card_number: values.card_number.replace(/\s+/g, ""),
      expiry: values.expiry,
      cvv: values.cvv,
    }, idempotencyKey)
      .then((order) => {
        flushSync(() => {
          setCartData(emptyCart);
          setDrawerStep("cart");
          setCartVisible(false);
        });
        paymentForm.resetFields();
        paymentRequestKeyRef.current = null;
        message.success({
          content: `Payment accepted. Order #${order.id} placed successfully`,
          duration: 2.2,
          key: feedbackKey,
        });
        onCartChanged();
      })
      .catch((err) => {
        message.error({
          content: err.message,
          key: feedbackKey,
        });
      })
      .finally(() => {
        paymentInFlightRef.current = false;
        setProcessingPayment(false);
      });
  };

  const totalItems = useMemo(
    () =>
      (cartData?.order_items || []).reduce(
        (sum, item) => sum + (item.quantity || 0),
        0
      ),
    [cartData]
  );

  const drawerTitle =
    drawerStep === "payment" ? (
      <div className="drawer-heading">
        <Text className="drawer-heading__eyebrow">Payment</Text>
        <Title level={3} className="drawer-heading__title">
          Confirm the order and pay in one step.
        </Title>
      </div>
    ) : (
      <div className="drawer-heading">
        <Text className="drawer-heading__eyebrow">Cart review</Text>
        <Title level={3} className="drawer-heading__title">
          Build the order before checkout.
        </Title>
      </div>
    );

  const drawerFooter =
    drawerStep === "payment" ? (
      <div className="cart-footer">
        <div>
          <Text className="cart-footer__label">Amount due</Text>
          <Title level={4} className="cart-footer__value">
            {`$${(cartData?.total_price || 0).toFixed(2)}`}
          </Title>
        </div>
        <Space>
          <Button className="action-button" onClick={returnToCart}>
            Back to bag
          </Button>
          <Button
            className="action-button action-button--primary"
            type="primary"
            loading={processingPayment}
            disabled={loading || !cartData?.order_items?.length}
            onClick={() => paymentForm.submit()}
          >
            {`Pay $${(cartData?.total_price || 0).toFixed(2)}`}
          </Button>
        </Space>
      </div>
    ) : (
      <div className="cart-footer">
        <div>
          <Text className="cart-footer__label">Order total</Text>
          <Title level={4} className="cart-footer__value">
            {`$${(cartData?.total_price || 0).toFixed(2)}`}
          </Title>
        </div>
        <Space>
          <Button className="action-button" onClick={closePanels}>
            Keep browsing
          </Button>
          <Button
            className="action-button action-button--primary"
            onClick={openPaymentStep}
            type="primary"
            disabled={loading || !cartData?.order_items?.length}
          >
            Checkout
          </Button>
        </Space>
      </div>
    );

  return (
    <>
      <Badge count={totalItems} size="small" offset={[-6, 6]}>
        <Button
          className="action-button action-button--primary"
          icon={<ShoppingCartOutlined />}
          shape="round"
          onClick={() => setCartVisible(true)}
        >
          My bag
        </Button>
      </Badge>

      <Drawer
        className="fashion-drawer"
        title={drawerTitle}
        onClose={closePanels}
        open={cartVisible}
        width={560}
        footer={drawerFooter}
      >
        {drawerStep === "payment" ? (
          <div className="payment-drawer">
            <div className="payment-modal__hero">
              <Text className="signup-modal__eyebrow">Payment details</Text>
              <Title level={3} className="signup-modal__title">
                Finish the order without leaving your bag.
              </Title>
              <Text className="payment-modal__note">
                This demo charges immediately and marks the order as paid.
              </Text>
            </div>

            <div className="payment-modal__layout">
              <div className="payment-summary">
                <div className="payment-summary__row">
                  <Text className="payment-summary__label">Items</Text>
                  <strong>{totalItems}</strong>
                </div>
                <div className="payment-summary__row">
                  <Text className="payment-summary__label">Amount due</Text>
                  <strong className="payment-summary__value">
                    {`$${(cartData?.total_price || 0).toFixed(2)}`}
                  </strong>
                </div>
                <div className="payment-summary__row payment-summary__row--note">
                  <SafetyCertificateOutlined />
                  <span>Orders placed through this flow will be marked as paid.</span>
                </div>
              </div>

              <Form form={paymentForm} layout="vertical" onFinish={onPaymentFinish}>
                <Form.Item
                  label="Cardholder name"
                  name="cardholder_name"
                  rules={[{ required: true, message: "Please enter the name on the card" }]}
                >
                  <Input
                    className="auth-input"
                    prefix={<CreditCardOutlined />}
                    placeholder="Luc1fer Buyer"
                  />
                </Form.Item>

                <Form.Item
                  label="Card number"
                  name="card_number"
                  getValueFromEvent={(event) => formatCardNumberInput(event.target.value)}
                  rules={[
                    { required: true, message: "Please enter a card number" },
                    {
                      validator: (_, value) =>
                        value && value.replace(/\s+/g, "").length === 16
                          ? Promise.resolve()
                          : Promise.reject(new Error("Card number must contain 16 digits")),
                    },
                  ]}
                >
                  <Input
                    className="auth-input"
                    prefix={<CreditCardOutlined />}
                    placeholder="4242 4242 4242 4242"
                    maxLength={19}
                  />
                </Form.Item>

                <div className="payment-modal__fields">
                  <Form.Item
                    label="Expiry"
                    name="expiry"
                    getValueFromEvent={(event) => formatExpiryInput(event.target.value)}
                    rules={[
                      { required: true, message: "Required" },
                      {
                        pattern: /^(0[1-9]|1[0-2])\/\d{2}$/,
                        message: "Use MM/YY",
                      },
                    ]}
                  >
                    <Input className="auth-input" placeholder="12/30" maxLength={5} />
                  </Form.Item>

                  <Form.Item
                    label="Security code"
                    name="cvv"
                    getValueFromEvent={(event) => formatSecurityCode(event.target.value)}
                    rules={[
                      { required: true, message: "Required" },
                      {
                        pattern: /^\d{3,4}$/,
                        message: "Use 3 or 4 digits",
                      },
                    ]}
                  >
                    <Input
                      className="auth-input"
                      prefix={<LockOutlined />}
                      placeholder="123"
                      maxLength={4}
                    />
                  </Form.Item>
                </div>
              </Form>
            </div>
          </div>
        ) : (
          <>
            <div className="drawer-summary">
              <div className="drawer-summary__card">
                <Text className="drawer-summary__label">Items</Text>
                <strong>{totalItems}</strong>
              </div>
              <div className="drawer-summary__card">
                <Text className="drawer-summary__label">Status</Text>
                <strong>{totalItems ? "Payment required" : "Empty bag"}</strong>
              </div>
            </div>

            {cartData?.order_items?.length ? (
              <List
                className="cart-list"
                loading={loading}
                itemLayout="horizontal"
                dataSource={cartData.order_items}
                renderItem={(item) => (
                  <List.Item className="cart-item" key={item.order_item_id}>
                    <div className="cart-item__media">
                      <img src={item.menu_item_image_url} alt={item.menu_item_name} />
                    </div>

                    <div className="cart-item__content">
                      <div className="cart-item__copy">
                        <Title level={5} className="cart-item__title">
                          {item.menu_item_name}
                        </Title>
                        <Text className="cart-item__meta">{`$${item.price?.toFixed(2)} each`}</Text>
                        <Text className="cart-item__meta">
                          {`Subtotal: $${(item.price * item.quantity).toFixed(2)}`}
                        </Text>
                      </div>

                      <div className="cart-item__actions">
                        <span className="cart-item__quantity">{item.quantity}</span>
                        <Button
                          className="cart-item__button"
                          icon={<MinusOutlined />}
                          disabled={updatingItemId === item.order_item_id}
                          onClick={() => onDecreaseQuantity(item)}
                        />
                        <Button
                          className="cart-item__button"
                          icon={<PlusOutlined />}
                          disabled={updatingItemId === item.order_item_id}
                          onClick={() => onIncreaseQuantity(item)}
                        />
                        <Button
                          className="cart-item__button cart-item__button--danger"
                          danger
                          icon={<DeleteOutlined />}
                          disabled={updatingItemId === item.order_item_id}
                          onClick={() => onRemoveItem(item)}
                        />
                      </div>
                    </div>
                  </List.Item>
                )}
              />
            ) : (
              <div className="drawer-empty-state">
                <Empty
                  image={Empty.PRESENTED_IMAGE_SIMPLE}
                  description={loading ? "Loading cart..." : "Your bag is still empty"}
                />
              </div>
            )}
          </>
        )}
      </Drawer>
    </>
  );
};

export default MyCart;
