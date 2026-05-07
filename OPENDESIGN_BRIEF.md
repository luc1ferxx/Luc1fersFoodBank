# Open Design Prompt - Luc1fer's Food Bank

请为 **Luc1fer's Food Bank / OnlineOrder** 设计一个高保真在线点餐 Web App。
这不是 marketing landing page，而是首屏即可操作的真实点餐产品。
首屏需要直接呈现 session 状态、餐厅/菜单浏览、购物车入口、订单入口。

## 产品目标
- 公开浏览餐厅和菜单。
- 支持注册、登录、退出、恢复 session。
- 登录后添加菜品到购物车。
- 修改购物车数量、删除商品。
- 使用 demo payment checkout 生成 `PAID` order。
- 查看订单历史和异步订单通知。
- 在允许状态下取消自己的订单。
- 所有设计必须严格匹配现有后端能力，不要假设未实现接口。

## 用户角色
- Guest：未登录；可浏览公开餐厅和菜单；可看到 login / signup。
- Guest 不可添加购物车、结账、查看订单、查看通知。
- Customer：已登录普通用户；可购物、结账、查看订单、取消自己的订单、查看通知。
- Admin：后端支持 status update 和 dead-letter replay，但 `GET /me` 不返回角色。
- Admin 不要做完整后台；如展示，只做低优先级 manual tools，手动输入 order ID 或 dead-letter ID。

## 核心页面 / 视图
1. Auth / Session Gate
- 展示 session loading、当前用户、login、signup、logout。
- Login 字段：email、password；Signup 字段：email、password、first name、last name。
- 可展示 demo account：`demo@laifood.com / demo123`。
- 必须设计 loading、unauthorized、login failed、signup conflict、account locked 状态。

2. Restaurant Directory
- 展示餐厅列表：`id`、`name`、`address`、`phone`、`image_url`。
- 支持餐厅切换，图片 fallback。
- 必须设计 restaurant loading、empty、error 状态。

3. Menu Browser
- 展示选中餐厅菜品：`id`、`restaurant_id`、`name`、`description`、`price`、`image_url`。
- 支持前端本地搜索菜名/描述。
- 登录用户可 add to cart；未登录点击 add 进入 unauthorized / login prompt。
- 已加入购物车的菜品显示 quantity stepper。
- 使用 `menu_item_id` 添加商品，使用 `order_item_id` 更新购物车项。
- 必须设计 menu loading、empty、add/update pending、error、unauthorized 状态。

4. Cart Drawer / Cart Page
- 展示 cart：`id`、`total_price`、`order_items`。
- Cart item 字段：`order_item_id`、`menu_item_id`、`restaurant_id`、`price`、`quantity`、`menu_item_name`、`menu_item_image_url`。
- 支持增加数量、减少数量、删除商品；展示 subtotal 和 total。
- 空购物车时禁用 checkout。
- 必须设计 cart loading、empty、mutation pending、delete pending、error、unauthorized 状态。

5. Payment Checkout
- 用 demo payment form 创建 `PAID` order。
- 字段：`cardholder_name`、`card_number`、`expiry`、`cvv`；`expiry` 格式 `MM/YY`。
- 展示金额、商品数量摘要、payment loading、success confirmation。
- 后端校验：姓名必填、卡号 16 位数字、CVV 3/4 位数字、expiry 未过期。
- 每次 checkout attempt 使用稳定 `Idempotency-Key`。
- 不要设计 Stripe、Apple Pay、Google Pay、保存银行卡、真实支付、退款、优惠券。

6. Order Confirmation
- 结账成功后展示 `OrderDto`：`id`、`total_price`、`status`、`created_at`、`items`。
- Item snapshot：`id`、`menu_item_id`、`restaurant_id`、`price`、`quantity`、`menu_item_name`、`menu_item_image_url`。
- Payment checkout 成功状态通常是 `PAID`。

7. Order History
- 展示当前用户订单，后端返回 newest first。
- 每个订单展示 order id、status、created_at、total_price、item list。
- 仅 `PLACED`、`PAID`、`ACCEPTED` 可取消。
- `PREPARING`、`COMPLETED`、`CANCELLED` 不允许取消。
- 必须设计 orders loading、empty、cancel pending、cancel conflict、unauthorized 状态。

8. Notifications
- 展示当前用户订单通知：`id`、`order_id`、`event_type`、`title`、`message`、`created_at`。
- 事件：`order.created`、`order.paid`、`order.accepted`、`order.preparing`、`order.completed`、`order.cancelled`。
- 通知来自异步 Kafka consumer，可能延迟。
- 设计 refresh / poll 体验、loading、empty、error 状态。
- 不要设计 read/unread、mark as read、push subscription。

9. Optional Admin Manual Tools
- 只做低优先级可选工具。
- 可手动输入 `orderId` + target status，调用 status update。
- 可手动输入 `deadLetterEventId`，调用 dead-letter replay。
- 不要设计全量订单列表、死信列表、角色发现、运营 dashboard。

## 真实 API 能力
- 所有 JSON request/response 字段使用 `snake_case`。
- 认证是 Spring Security form login + server-side session cookie。
- 同源部署优先；跨域开发时 fetch 需要 `credentials: "include"`。
- `GET /me`：需要登录，返回 current user；不返回 roles。
- `POST /login`：form encoded，字段 `username`、`password`；成功无 body。
- `POST /logout`：退出；成功无 body。
- `POST /signup`：JSON 创建用户和空购物车；成功 `201`。
- `GET /restaurants/menu`：公开，返回餐厅和 menu preview。
- `GET /restaurant/{restaurantId}/menu`：公开，返回菜单；未知 ID 返回空数组。
- `GET /cart`：需要登录，返回 cart。
- `POST /cart`：body `{ "menu_id": 1 }`，添加或递增商品。
- `PUT /cart/items/{orderItemId}`：body `{ "quantity": 3 }`，设置准确数量；0 删除。
- `DELETE /cart/items/{orderItemId}`：删除购物车项。
- `POST /cart/checkout`：需要 `Idempotency-Key`，创建 `PLACED` order。
- `POST /payments/checkout`：需要 `Idempotency-Key`，body 为 `cardholder_name`、`card_number`、`expiry`、`cvv`，创建 `PAID` order。
- `GET /orders`：当前用户订单历史。
- `POST /orders/{orderId}/cancel`：取消自己的订单，需要 `Idempotency-Key`。
- `PATCH /orders/{orderId}/status`：admin only，需要 `Idempotency-Key`。
- `GET /notifications`：当前用户通知列表，异步生成，可能延迟。
- `POST /dead-letters/{deadLetterEventId}/replay`：admin only，Kafka enabled 时可用。

## 必须设计的状态
- 每个主要视图都要有 loading、empty、error、unauthorized / logged out 状态。
- 每个 mutation 都要有 pending state 和 disabled state for impossible actions。
- `400` invalid input。
- `401` unauthenticated / login failed。
- `403` no admin permission。
- `404` not found or not owned。
- `409` business conflict，例如 invalid transition 或 idempotency conflict。
- `429` rate limited。
- `500` server failure；可显示 `X-Trace-Id` 作为 debug 信息。

## 后端已支持，应该设计
- 登录、注册、退出、恢复 session。
- 餐厅列表、菜单浏览、前端本地搜索。
- 添加购物车、修改数量、删除商品。
- Demo payment checkout、订单确认。
- 订单历史、当前用户取消订单。
- 通知列表。
- 低优先级 admin manual tools。

## 后端不支持，不要设计
- 外卖地址、配送费、配送时间、司机追踪。
- 库存、售罄、餐厅营业时间。
- 优惠券、税费、tips、退款。
- 真实支付 provider、保存银行卡、Apple Pay / Google Pay。
- 订单详情 by ID。
- 管理员全量订单列表、死信列表、角色自动识别。
- 通知已读/未读。
- 菜单服务端搜索、分页、筛选。
- 用户资料编辑。

## 视觉方向
- 现代、清晰、食物电商场景，强调快速浏览和重复操作。
- 移动端和桌面都要可用。
- 建议包含 top session bar、restaurant selector、menu grid/list、cart drawer、payment drawer/modal、order history、notification panel。
- 设计稿必须能直接映射到现有 API，不要引入后端没有的数据模型。
