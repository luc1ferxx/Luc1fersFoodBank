import { MinusOutlined, PlusOutlined, SearchOutlined } from "@ant-design/icons";
import {
  Button,
  Card,
  Empty,
  Input,
  List,
  Space,
  Tag,
  Tooltip,
  Typography,
  message,
} from "antd";
import { useEffect, useMemo, useState } from "react";
import {
  addItemToCart,
  getCart,
  getMenus,
  getRestaurants,
  updateCartItem,
} from "../utils";

const { Paragraph, Text, Title } = Typography;

const buildCartItemMap = (cartData) =>
  (cartData?.order_items || []).reduce((cartMap, item) => {
    cartMap[item.menu_item_id] = {
      orderItemId: item.order_item_id,
      quantity: item.quantity,
    };
    return cartMap;
  }, {});

const QuantityControl = ({ loading, quantity = 0, onIncrease, onDecrease }) => {
  if (quantity > 0) {
    return (
      <div className="menu-card__stepper" role="group" aria-label="Item quantity">
        <Button
          className="menu-card__stepper-button"
          disabled={loading}
          icon={<MinusOutlined />}
          shape="circle"
          onClick={onDecrease}
        />
        <span className="menu-card__quantity">{quantity}</span>
        <Button
          className="menu-card__stepper-button menu-card__stepper-button--plus"
          disabled={loading}
          icon={<PlusOutlined />}
          shape="circle"
          onClick={onIncrease}
        />
      </div>
    );
  }

  return (
    <Tooltip title="Add to bag">
      <Button
        className="menu-card__cta"
        loading={loading}
        icon={<PlusOutlined />}
        shape="round"
        onClick={onIncrease}
      >
        Add to bag
      </Button>
    </Tooltip>
  );
};

const FoodList = ({ cartVersion, onItemAdded }) => {
  const [foodData, setFoodData] = useState([]);
  const [currentRestaurantId, setCurrentRestaurantId] = useState();
  const [restaurants, setRestaurants] = useState([]);
  const [cartItemsByMenuId, setCartItemsByMenuId] = useState({});
  const [loadingMenus, setLoadingMenus] = useState(false);
  const [loadingRestaurants, setLoadingRestaurants] = useState(false);
  const [pendingCartItems, setPendingCartItems] = useState({});
  const [searchValue, setSearchValue] = useState("");

  const refreshCartSnapshot = () =>
    getCart().then((cartData) => {
      setCartItemsByMenuId(buildCartItemMap(cartData));
      return cartData;
    });

  useEffect(() => {
    refreshCartSnapshot().catch((err) => {
      message.error(err.message);
    });
  }, [cartVersion]);

  useEffect(() => {
    setLoadingRestaurants(true);
    getRestaurants()
      .then((data) => {
        setRestaurants(data);
        if (data.length > 0) {
          setCurrentRestaurantId(data[0].id);
        }
      })
      .catch((err) => {
        message.error(err.message);
      })
      .finally(() => {
        setLoadingRestaurants(false);
      });
  }, []);

  useEffect(() => {
    if (!currentRestaurantId) {
      return;
    }

    setLoadingMenus(true);
    getMenus(currentRestaurantId)
      .then((data) => {
        setFoodData(data);
      })
      .catch((err) => {
        message.error(err.message);
      })
      .finally(() => {
        setLoadingMenus(false);
      });
  }, [currentRestaurantId]);

  const currentRestaurant = useMemo(
    () => restaurants.find((item) => item.id === currentRestaurantId),
    [currentRestaurantId, restaurants]
  );

  const filteredFoodData = useMemo(() => {
    const keyword = searchValue.trim().toLowerCase();

    if (!keyword) {
      return foodData;
    }

    return foodData.filter((item) => {
      const haystack = `${item.name} ${item.description || ""}`.toLowerCase();
      return haystack.includes(keyword);
    });
  }, [foodData, searchValue]);

  const heroImage = filteredFoodData[0]?.image_url || foodData[0]?.image_url;

  const setPendingState = (menuItemId, isPending) => {
    setPendingCartItems((currentPendingItems) => {
      const nextPendingItems = { ...currentPendingItems };

      if (isPending) {
        nextPendingItems[menuItemId] = true;
      } else {
        delete nextPendingItems[menuItemId];
      }

      return nextPendingItems;
    });
  };

  const handleQuickAdd = (menuItemId) => {
    const previousCartSnapshot = cartItemsByMenuId;

    setPendingState(menuItemId, true);
    setCartItemsByMenuId((currentCartItems) => ({
      ...currentCartItems,
      [menuItemId]: {
        orderItemId: currentCartItems[menuItemId]?.orderItemId,
        quantity: (currentCartItems[menuItemId]?.quantity || 0) + 1,
      },
    }));

    addItemToCart(menuItemId)
      .then(() => refreshCartSnapshot())
      .then(() => {
        onItemAdded();
      })
      .catch((err) => {
        setCartItemsByMenuId(previousCartSnapshot);
        message.error(err.message);
      })
      .finally(() => {
        setPendingState(menuItemId, false);
      });
  };

  const handleQuickDecrease = (menuItemId) => {
    const previousCartSnapshot = cartItemsByMenuId;
    const previousItem = previousCartSnapshot[menuItemId];

    if (!previousItem?.orderItemId || !previousItem.quantity) {
      return;
    }

    const nextQuantity = previousItem.quantity - 1;

    setPendingState(menuItemId, true);
    setCartItemsByMenuId((currentCartItems) => {
      const nextCartItems = { ...currentCartItems };

      if (nextQuantity > 0) {
        nextCartItems[menuItemId] = {
          orderItemId: previousItem.orderItemId,
          quantity: nextQuantity,
        };
      } else {
        delete nextCartItems[menuItemId];
      }

      return nextCartItems;
    });

    updateCartItem(previousItem.orderItemId, nextQuantity)
      .then(() => refreshCartSnapshot())
      .then(() => {
        onItemAdded();
      })
      .catch((err) => {
        setCartItemsByMenuId(previousCartSnapshot);
        message.error(err.message);
      })
      .finally(() => {
        setPendingState(menuItemId, false);
      });
  };

  return (
    <div className="food-stage">
      <div className="food-stage__header">
        <div>
          <Text className="section-eyebrow">Now serving</Text>
          <Title level={2} className="section-title">
            Switch restaurants, search the menu, and build the cart in place.
          </Title>
        </div>

        <Input
          allowClear
          className="menu-search"
          prefix={<SearchOutlined />}
          placeholder="Search dishes or ingredients"
          value={searchValue}
          onChange={(event) => setSearchValue(event.target.value)}
        />
      </div>

      <div className="restaurant-picker">
        {restaurants.map((item) => (
          <button
            className={`restaurant-chip ${
              item.id === currentRestaurantId ? "restaurant-chip--active" : ""
            }`}
            disabled={loadingRestaurants}
            key={item.id}
            onClick={() => setCurrentRestaurantId(item.id)}
            type="button"
          >
            <span className="restaurant-chip__name">{item.name}</span>
            <span className="restaurant-chip__meta">Tap to preview</span>
          </button>
        ))}
      </div>

      {currentRestaurant && (
        <div className="restaurant-spotlight">
          <div className="restaurant-spotlight__content">
            <Text className="restaurant-spotlight__eyebrow">Selected house</Text>
            <Title level={3} className="restaurant-spotlight__title">
              {currentRestaurant.name}
            </Title>
            <Paragraph className="restaurant-spotlight__text">
              {currentRestaurant.address}
            </Paragraph>
            <div className="restaurant-spotlight__details">
              <Tag className="restaurant-spotlight__tag">{currentRestaurant.phone}</Tag>
              <Tag className="restaurant-spotlight__tag">
                {foodData.length} dishes available
              </Tag>
              {searchValue ? (
                <Tag className="restaurant-spotlight__tag">
                  {filteredFoodData.length} matching results
                </Tag>
              ) : null}
            </div>
          </div>

          <div className="restaurant-spotlight__media">
            {heroImage ? (
              <img src={heroImage} alt={currentRestaurant.name} />
            ) : (
              <div className="restaurant-spotlight__placeholder">Fresh menu preview</div>
            )}
          </div>
        </div>
      )}

      {filteredFoodData.length === 0 && !loadingMenus ? (
        <div className="menu-empty-state">
          <Empty description="No menu items match your search" />
        </div>
      ) : (
        <List
          className="menu-grid"
          loading={loadingMenus}
          grid={{
            gutter: 24,
            xs: 1,
            sm: 1,
            md: 2,
            lg: 2,
            xl: 3,
            xxl: 3,
          }}
          dataSource={filteredFoodData}
          renderItem={(item, index) => {
            const cartItem = cartItemsByMenuId[item.id];
            const quantity = cartItem?.quantity || 0;
            const isPending = Boolean(pendingCartItems[item.id]);

            return (
              <List.Item key={item.id}>
                <Card
                  className="menu-card"
                  cover={
                    <div className="menu-card__cover">
                      <img src={item.image_url} alt={item.name} />
                      <div className="menu-card__overlay">
                        <Text className="menu-card__price">{`$${item.price?.toFixed(2)}`}</Text>
                        {index === 0 ? (
                          <Tag className="menu-card__tag">Chef&apos;s pick</Tag>
                        ) : (
                          <Tag className="menu-card__tag">Fresh today</Tag>
                        )}
                      </div>
                    </div>
                  }
                >
                  <Space className="menu-card__content" direction="vertical" size="middle">
                    <div>
                      <Title level={4} className="menu-card__title">
                        {item.name}
                      </Title>
                      <Paragraph
                        className="menu-card__description"
                        ellipsis={{ rows: 3, expandable: false }}
                      >
                        {item.description || "No description available."}
                      </Paragraph>
                    </div>

                    <div className="menu-card__footer">
                      <div>
                        <Text className="menu-card__label">Quick order</Text>
                        <Text className="menu-card__subtitle">
                          Add this item directly to your bag
                        </Text>
                      </div>
                      <QuantityControl
                        loading={isPending}
                        quantity={quantity}
                        onIncrease={() => handleQuickAdd(item.id)}
                        onDecrease={() => handleQuickDecrease(item.id)}
                      />
                    </div>
                  </Space>
                </Card>
              </List.Item>
            );
          }}
        />
      )}
    </div>
  );
};

export default FoodList;
