import {
  DeleteOutlined,
  MinusOutlined,
  PlusOutlined,
  ShoppingCartOutlined,
} from "@ant-design/icons";
import { Badge, Button, Drawer, Empty, List, Space, Typography, message } from "antd";
import { useEffect, useMemo, useState } from "react";
import {
  addItemToCart,
  checkout,
  getCart,
  removeCartItem,
  updateCartItem,
} from "../utils";

const { Text, Title } = Typography;

const emptyCart = {
  order_items: [],
  total_price: 0,
};

const MyCart = ({ cartVersion, onCartChanged }) => {
  const [cartVisible, setCartVisible] = useState(false);
  const [cartData, setCartData] = useState(emptyCart);
  const [loading, setLoading] = useState(false);
  const [checkingOut, setCheckingOut] = useState(false);
  const [updatingItemId, setUpdatingItemId] = useState(null);

  const refreshCart = (showSpinner = false) => {
    setLoading(showSpinner);
    getCart()
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
  }, [cartVersion, cartVisible]);

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

  const onCheckout = () => {
    setCheckingOut(true);
    checkout()
      .then((order) => {
        message.success(`Order #${order.id} placed successfully`);
        setCartVisible(false);
        setCartData(emptyCart);
        onCartChanged();
      })
      .catch((err) => {
        message.error(err.message);
      })
      .finally(() => {
        setCheckingOut(false);
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
        title={
          <div className="drawer-heading">
            <Text className="drawer-heading__eyebrow">Cart review</Text>
            <Title level={3} className="drawer-heading__title">
              Build the order before checkout.
            </Title>
          </div>
        }
        onClose={() => setCartVisible(false)}
        open={cartVisible}
        width={560}
        footer={
          <div className="cart-footer">
            <div>
              <Text className="cart-footer__label">Order total</Text>
              <Title level={4} className="cart-footer__value">
                {`$${(cartData?.total_price || 0).toFixed(2)}`}
              </Title>
            </div>
            <Space>
              <Button className="action-button" onClick={() => setCartVisible(false)}>
                Keep browsing
              </Button>
              <Button
                className="action-button action-button--primary"
                onClick={onCheckout}
                type="primary"
                loading={checkingOut}
                disabled={loading || !cartData?.order_items?.length}
              >
                Checkout
              </Button>
            </Space>
          </div>
        }
      >
        <div className="drawer-summary">
          <div className="drawer-summary__card">
            <Text className="drawer-summary__label">Items</Text>
            <strong>{totalItems}</strong>
          </div>
          <div className="drawer-summary__card">
            <Text className="drawer-summary__label">Status</Text>
            <strong>{totalItems ? "Ready to order" : "Empty bag"}</strong>
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
      </Drawer>
    </>
  );
};

export default MyCart;
