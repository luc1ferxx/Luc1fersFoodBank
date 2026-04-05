import { ClockCircleOutlined, HistoryOutlined } from "@ant-design/icons";
import { Button, Drawer, Empty, List, Space, Spin, Tag, Typography, message } from "antd";
import { useEffect, useState } from "react";
import { getOrders } from "../utils";

const { Text, Title } = Typography;

const statusColors = {
  CANCELLED: "volcano",
  CREATED: "gold",
  DELIVERED: "green",
  PAID: "blue",
};

const OrderHistory = () => {
  const [drawerOpen, setDrawerOpen] = useState(false);
  const [loading, setLoading] = useState(false);
  const [orders, setOrders] = useState([]);

  useEffect(() => {
    if (!drawerOpen) {
      return;
    }

    setLoading(true);
    getOrders()
      .then((data) => {
        setOrders(data);
      })
      .catch((err) => {
        message.error(err.message);
      })
      .finally(() => {
        setLoading(false);
      });
  }, [drawerOpen]);

  return (
    <>
      <Button
        className="action-button"
        icon={<HistoryOutlined />}
        shape="round"
        onClick={() => setDrawerOpen(true)}
      >
        Orders
      </Button>
      <Drawer
        className="fashion-drawer"
        title={
          <div className="drawer-heading">
            <Text className="drawer-heading__eyebrow">Order archive</Text>
            <Title level={3} className="drawer-heading__title">
              Review every placed order in one place.
            </Title>
          </div>
        }
        width={640}
        onClose={() => setDrawerOpen(false)}
        open={drawerOpen}
      >
        {loading ? (
          <div className="drawer-loading-state">
            <Spin />
          </div>
        ) : orders.length === 0 ? (
          <div className="drawer-empty-state">
            <Empty description="No orders yet" />
          </div>
        ) : (
          <List
            className="order-list"
            dataSource={orders}
            renderItem={(order) => (
              <List.Item className="order-card" key={order.id}>
                <div className="order-card__header">
                  <div>
                    <Text className="order-card__eyebrow">{`Order #${order.id}`}</Text>
                    <Title level={4} className="order-card__title">
                      {`$${order.total_price?.toFixed(2)}`}
                    </Title>
                  </div>
                  <Space wrap>
                    <Tag color={statusColors[order.status] || "default"}>{order.status}</Tag>
                    <Text className="order-card__timestamp">
                      <ClockCircleOutlined />
                      {new Date(order.created_at).toLocaleString()}
                    </Text>
                  </Space>
                </div>

                <List
                  className="order-card__items"
                  size="small"
                  dataSource={order.items}
                  renderItem={(item) => (
                    <List.Item key={item.id}>
                      <div className="order-line">
                        <Text>{`${item.quantity} x ${item.menu_item_name}`}</Text>
                        <Text>{`$${(item.price * item.quantity).toFixed(2)}`}</Text>
                      </div>
                    </List.Item>
                  )}
                />
              </List.Item>
            )}
          />
        )}
      </Drawer>
    </>
  );
};

export default OrderHistory;
