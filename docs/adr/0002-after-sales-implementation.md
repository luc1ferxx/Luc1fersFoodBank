# ADR 0002 实施记录（第 21–27 节）

[ADR 0002](0002-after-sales-state-machine.md) 的第 1–20 节是设计；本文件是从它拆出的实施记录：第 21–26 节按阶段记录实际实现和与设计的差异，第 27 节汇总偏差和后续事项。编号延续原文，所以代码注释和文档里的"ADR 0002 第 27 节"、"section 27, follow-up N"仍指这里的章节。设计与代码不一致时以代码为准。

---

## 21. P1 实施记录（提交 39c0f84；P2 开始前必读）

P1 只加测试，全量门禁通过（873 个测试，0 失败，0 跳过）。实施中发现的、与前文描述不一致或 P2 需要知道的地方：

1. **no-op 也会写幂等行。** 经 `runIdempotentOrderOperation` 的 no-op（管理员 PATCH 到当前状态、顾客重复取消）不写 orders、history、事件和指标，但会插入并标记一条 SUCCEEDED 的 `idempotency_requests` 行。13.2 里"no-op 什么都不写"只针对订单本身。
2. **竞态 2 管理员先执行时，PATCH 本身不删 deadline。** 之后轮询器触发，`cancelIfStillIn` 返回 empty，`OrderDeadlineProcessor` 照样执行 `deleteById`。测试断言的是"两者都结束后 deadline 不存在"。
3. **商家重复命令也会改写收件箱行。** `MerchantOrderService.decide` 在 `changed=false` 时也会重写 `merchant_orders` 的 status、cancel_reason、updated_at。
4. **P2-4 会按预期打破的测试（13.3 未列全）：**
   - `OrderServiceTests#adminPatch_shouldLockAndWriteInTheDocumentedOrder`、`#customerCancel_shouldLockAndWriteInTheDocumentedOrder`（InOrder 里 stub 并校验 `orderRepository.save`）、`#adminPatch_cancelled_shouldRecordAdminCancelled`。
   - `DispatchServiceTests#advance_illegalMoves_shouldConflictWithTheExactMessage`（`never().updateStatus`，删掉 `updateStatus` 后编译失败）。
   - 处理方式：把 `save` / `updateStatus` 换成新的 `compareAndSetStatus`，**保留**原有的顺序断言和"不写库"断言。
5. **"不写库"断言改成 `verifyNoMoreInteractions`。** 新增测试在显式校验加锁调用后用 `verifyNoMoreInteractions(orderRepository)`，所以 P2 换成 CAS 后仍然有效。旧测试里的 `never().save(...)`（如 `OrderServiceTests` 约 138、205 行）在 P2 后会变成空断言，P2 要一并改掉。
6. **竞态测试改成强制串行顺序。** 竞态测试用 `T/PostgresRowLocks.java` 先持有订单行锁，按固定顺序让两个命令排队，再放锁，每轮交替顺序，所以两种结局每次都会跑到。它依赖连接池的 `lock_timeout`（3000 ms，`application.yml`）。
7. **骑手负载递减有下限保护。** `RiderRepository.decrementLoad` 带 `AND active_deliveries > 0`，重复释放会被截在 0。负载总和断言（`assertEveryRiderLoadMatchesTheirOpenDeliveries`）能发现重复释放，P2 不能删这个断言。
8. **13.2、13.3、14 节的行号已漂移**，以 `grep` 为准。

---

## 22. P2 实施记录（提交 7d1204a、64fc095、292e931、d804199；P3 开始前必读）

对抗审查结论：通过，没有必须修复的问题。每个生产调用方都自己传入触发器和允许的 actor；与 P2 之前（cbdab99）的代码逐个分支对比，409 的错误码和文案、no-op 结果、历史原因、cancel_reason、事件、审计、幂等行、骑手负载和 tombstone 都保持一致。P1 测试只做了允许范围内的调整：`save`、`updateStatus`、`updateRider` 换成 CAS 方法，原有的顺序断言和"不写库"断言都保留。

**与前文的偏差**
1. `OrderTrigger`、`OrderTransitionContext` 和 `OrderStateMachine` 都放在 `com.laioffer.onlineorder.model`，没有按 4.1 标题放在 service 包。私有的 `cancelReasonCode` 移到了 `OrderTransitionContext.cancelReasonCode`（public static）。
2. `restaurantTrigger` 和 `timeoutTrigger` 在加锁**之前**选定触发器：在同一族（`MERCHANT_*` 或 `*_TIMEOUT`）里取第一个 from 集合和目标都匹配的。三种商家命令以外的组合，以及 PLACED、PAID 以外的等待状态，直接抛 `IllegalArgumentException`，不加锁。旧代码会接受任何合法边，但没有调用方会传这些参数。
3. `decide()` 最先检查 actor：表里不允许的 actor 会得到 `IllegalStateException`（500），例如用 CUSTOMER 调 `transitionRestaurantOrder`。生产代码里没有这种调用。
4. 配送用 `DeliveryStateMachine.advanceTrigger` 把目标状态映射成触发器；目标不是 PICKED_UP 或 DELIVERED 时，在加锁之后、写入之前抛 `IllegalArgumentException`。
5. CAS 失败的文案：`"Compare-and-set of <aggregate> <id> from <expected> changed <rows> rows instead of 1"`；配送的 expected 写成 `"<from> with rider <riderId>"`。
6. 新增两个 PostgreSQL 级 CAS 测试：订单 `orderCompareAndSet_shouldOnlyChangeARowThatIsStillInTheExpectedStatus`，配送一个对应的。只有它们在真实数据库上验证了 0 行的情况。
7. "上下文里恰好一个 `TaskScheduler`"的断言在 `FulfilmentFlowContainerTests` 里。它需要 Docker，没有 Docker 的机器上会被跳过；CI 有 Docker。
8. `OrderStatus.CHECKOUT_INITIAL_STATES` 改为包级可见的不可变集合，供测试断言它等于 `DEFINITION.initialStates()`。

**P3 需要知道的**
- 处理 `Decision` 用 `instanceof` 模式匹配（`Decision.Apply` / `AlreadyDone` / `Rejected`），不要用 switch 模式匹配。`decide()` 的顺序是 actor → alreadyDoneWhen → from 状态 → 守卫。
- 机器定义是 `static final DEFINITION`，集合都是不可变副本；`canMove` 和 `targets` 不包含 stay 转换和 from 为空的转换。
- **包循环**：`model.OrderStateMachine` 引用 `fulfilment.order.OrderActor`，而 `fulfilment.order.OrderLifecycleCommands` 本来就引用 `model`。售后状态机放在 `aftersales` 包，不要加深这个循环；以后可以考虑把 `OrderActor` 移到中立的包。
- P3 要扩展 `OrderLifecycleCommands`。届时在它的 Javadoc 里写清楚状态机在运行时强制的规则（每个方法允许的 actor 和状态），审查指出现在没写。
- 留给后续的小问题：
  - `completeDeliveredOrder` 忽略了第一步的 `TransitionOutcome`。如果将来声明变化导致 `DELIVERY_PREPARE` 被拒，第二步会拿到旧订单。
  - `firingOnlyFrom` 在同一族里出现两个 from 和目标都相同的触发器时会有歧义。
  - `DispatchService` 里 `from.holdsRider()` 为假的分支不可达，是一个永久的 JaCoCo 未覆盖分支。

---

## 23. P3a 实施记录（提交 0bf521e 到 8ce1e60；P3b 开始前必读）

对抗审查结论：通过，没有必须修复的问题。审查逐项尝试推翻以下几类问题，都没有成立：超退（单行、总额、跨两次申请）、H2 与 PostgreSQL 一致性、支付记录只写一次、守卫与 alreadyDone 语义、开单并发与第二次申请规则、幂等绑定、事件契约，以及订单与配送的既有行为。

**已完成**
- `Money` 增加 `minus`，以及 `compareTo`、`isPositive`、`isGreaterThan` 等比较方法，都会校验币种。
- 迁移：
  - V12 建出 P3 需要的全部表：售后单、行、历史、退款、`order_payments`、`merchant_after_sales_cases`，以及 `order_notifications.dedup_scope` 和新的唯一键。
  - V12_1 加币种检查。
  - V12_2 给 `idempotency_requests` 加 `case_id`，用来做售后的幂等绑定。
- 付款下单时，在同一事务里写一条 `order_payments`（CAPTURED，`SIMPAY-…`）。重放和普通下单都不写。
- 状态机：
  - 售后和退款两个状态机放在 `aftersales` 包。
  - 用新的 `AfterSalesActor` 枚举（CUSTOMER、MERCHANT、ADMIN、SYSTEM），不用 `OrderActor.Type`，以免加深包循环。服务层用 `AfterSalesActor.valueOf(orderActor.type().name())` 转换。
  - 守卫按第 19.1 条只放在顾客和商家的响应上。`RECLAIM` 带 `leaseExpired` 和 `attemptsLeft` 两个守卫。
- 新 topic `after-sales-events`，7 个事件类型，均为 INITIAL；附 schema、catalog、SHA256SUMS 和 13 个 samples。事件数据带 `attempt_no`；每行都带 currency；`lines` 至少一行。
- 单一写入者 `AfterSalesTransitions`：
  - `fire()` 覆盖第 5.2 节全部 15 个触发器，P3b 和 P3c 只需新增调用方。
  - CAS 条件是 `id + status + version`，写入时 version + 1，并清空截止时间的重试字段。
  - 每次进入新状态都重算 `deadline_at`。
  - 指标 `onlineorder.aftersales.steps` 在提交后计数。
- 顾客接口 `AfterSalesController`：开单、总览（`{cases, eligible_orders}`）、详情、接受还价、拒绝还价、申请平台介入、撤销。资格检查都在订单行锁内完成。

**与前文的偏差**
1. 不再定义 `AFTER_SALES_CASE_EXISTS`。按第 20 节，所有“已有售后单”的情况都返回 409 `AFTER_SALES_NOT_ELIGIBLE`。
2. 申请金额超过“扣款金额减去本订单所有已批准金额”时，返回 409 `AFTER_SALES_NOT_ELIGIBLE`，文案是 `"A refund of X USD would exceed the Y USD left of the order's captured payment"`。
3. `AfterSalesCaseDto` 在 `kind` 后面加了 `attempt_no`。`EligibleOrderDto` 的字段是 `(order_id, order_no, attempt_no, request_deadline)`。
4. 约束比第 8.1 节多出几条：
   - attempt 只能是 1 或 2，且 ORDER_CANCELLED 单固定为 1。
   - 状态为 CLOSED 当且仅当填了 `close_reason`。
   - 行金额 = 单价 × 数量。
   - refunds 的几个状态一致性检查。
   - `order_payments.provider_reference` 唯一。
5. `rescheduleDeadline(id, version, retryAt, attempts)` 只在 version 匹配时更新（第 19.3 条）。
6. `application-demo.yml` 还没有加。它需要打开轮询器，留给 P3b。

**P3b 需要知道 / 待决定**
- ORDER_CANCELLED 开单：在 `AfterSalesTransitions` 里增加一个开单方法，插入一张单：REFUNDING、attempt 1、`requested = approved = order_total`、resolution 为 ORDER_CANCELLED、包含全部订单行；历史由 SYSTEM 记为 ORDER_CANCELLED；同时插入 refund。没有支付记录或金额为 0 时，什么都不建（D12）。
- **包循环**：`aftersales` 已经依赖 `service` 和 `model`，目前还没有人依赖 `aftersales`。P3b 让 `OrderService` 调用 `CancellationRefunds`、P3c 让 `OrderService` 委托商家命令之后，会形成 `service` ↔ `aftersales` 的循环。实现时要么接受并在文档里写明，要么用接口反转依赖。
- 轮询器调用 `fire()` 时传入已 claim 的售后单，触发器用 `AfterSalesStateMachine.timeoutTrigger(status)`。退款执行器用 `RefundTransitionContext(lockedRefund, now, maxAttempts)`。
- **待定**：`RefundDto.last_error` 目前会通过售后单详情返回给顾客。推荐只对管理员显示，P3b 实现退款执行器时一起改。

**留给 P4 和 P5**
- P4：在前端 `proxyPaths.ts` 加 `/after-sales`。第二次申请必须用**新的** Idempotency-Key：复用第一次的 key 和请求体，会重放第一张（已撤销的）单。
- P5：V12 里替换 `order_notifications` 唯一键需要重建索引，**不是**在线安全的操作，第 15 节“可以在线执行”的说法要改。其他待写进文档的内容：V12_2 的外键检查、新指标、取消 `AFTER_SALES_CASE_EXISTS`、eligible 列表包含 0.00 的已付订单（`open()` 会返回 400）。

---

## 24. P3b 实施记录（提交 d2fa479、1adce61、3bc6e6c、6445dc6、61017e9；P3c 开始前必读）

对抗审查结论：通过，没有必须修复的问题。审查尝试从这几方面推翻实现：重复付款或超额付款、取消退款的完整性、P1/P2 订单行为、硬截止和软截止、SKIP LOCKED、version 守卫、网关调用在事务外、RECLAIM 上限、锁顺序、轮询器规则、`last_error` 隐私。都没有找到失败场景。全量门禁 1351 个测试，0 失败，0 跳过。

**已完成**
- **取消自动退款**
  - 订单模块自己定义接口 `service.CancellationRefunds`，由 `aftersales.OrderCancellationRefunds` 实现，`service` 包不 import `aftersales`。
  - `OrderService.applyTransition` 在 PAID/ACCEPTED → CANCELLED 时调用它。调用时机：订单自身的 outbox 事件之后，转换指标、审计和幂等标记之前。
  - 加锁顺序：订单行 → ORDER 的 outbox 序列行 → 新建售后单、行、历史、退款 → AFTER_SALES_CASE 的 outbox 序列行。
  - 先判断总额是否大于 0，再查支付记录。有付款状态但没有扣款记录的订单记 WARN 日志，正常取消，不建售后单。
- **截止时间**：`AfterSalesDeadlineProcessor` 加 `AfterSalesPoller`（`SmartLifecycle`，独立线程 `after-sales-poller`）。
- **退款执行器**：`RefundExecutor`、`RefundGateway` 和 `SimulatedRefundGateway`。
  - 网关调用在独立的线程池里执行，线程名 `refund-gateway-N`，用 `Future.get(timeout)` 等待，超时就中断。
  - 启动时校验配置：max-attempts ≥ 1，所有时长为正，retry-backoff-max ≥ retry-backoff，网关超时 < claim-timeout。
- **演示配置**：`application-demo.yml`。
- **测试 JVM 堆**：提到 1 GB。默认 512 MB 时，缓存的 Spring 测试上下文会撑爆内存。

**与前文的偏差**
1. 第 19.4 条的金额校验放在 claim 事务里、写租约之前。另外要求 `refunds.payment_reference` 等于 `order_payments.provider_reference`，比 ADR 更严格。不一致时直接放弃（FAILED），不调用网关。
2. ORDER_CANCELLED 单在开单时不和扣款金额比较。扣款金额更小的情况，由执行器的金额校验让它 FAILED。
3. 只有商家拒单和接单超时的通知会加上 "Your payment will be refunded automatically."（前提是扣款金额大于 0）。顾客取消、管理员取消、支付超时的通知文案没有改。
4. 新增配置 `app.after-sales.refund-gateway.timeout`，默认 10s。
5. 指标的 outcome 取值：
   - `onlineorder.aftersales.refunds`：succeeded、retried、failed、superseded、unrecorded、skipped。
   - `onlineorder.aftersales.deadlines`：fired、skipped、failed。
6. `RefundDto.forCustomer` 去掉了 `last_error`，P3a 遗留的这一项已经关闭。管理员视图仍然带这个字段。
7. "上下文里恰好一个 TaskScheduler"的断言另外放进了 `AfterSalesDeadlinePostgresIntegrationTests` 和 `AfterSalesDemoProfileTests`。后者不需要 Docker。

**P3c 应一并处理（审查给出的稳健性建议）**
1. 截止时间处理器遇到 skipped 时，和退款执行器一样结束本轮。否则时钟回拨时，同一张单会在一轮里被重复 claim 最多 batch-size 次。对应测试 `refusedTimeout_shouldCountAsSkippedAndBackNothingOff` 把第二次 claim 打桩成 null，掩盖了这个问题，要一起修。
2. `RefundRepository.claimNextDue` 是 `ORDER BY id` 加上两个 OR 条件，表大了可能扫过所有 SUCCEEDED 行。拆成两条查询：PENDING 按 `next_attempt_at`，PROCESSING 按 `claimed_until`。
3. claim 事务里某一条退款抛异常时，只让这一条退避，不要卡住它后面所有的退款。
4. 网关线程池加上限；每个 tick 设时间预算，避免网关挂起时截止时间被拖延太久。
5. 补一个 H2 测试，覆盖 SKIP LOCKED 真的会跳过被锁的行。
6. 改写 application.yml 里 `refund-gateway.timeout` 的注释：超时之后的安全性靠网关按 `gateway_key` 幂等来保证。
7. 统一通知文案：只要是已扣款的取消，都提示"将自动退款"。和 P3-10 的通知消费者一起做。
8. 开单时如果订单总额大于扣款金额，记日志或指标，让客服在退款失败之前就能看到。

---

## 25. P3c 实施记录（提交 045039d 到 1ebafd4；P4、P5 开始前必读）

做法：四块工作在各自的 worktree 里并行完成，分别是稳健性、商家端、管理员端和通知；之后按顺序 cherry-pick 到 `feature/after-sales`，过程中没有文本冲突。端到端测试和只读审查同时进行。

对抗审查结论：通过，没有必须修复的问题。端到端测试 `AfterSalesFlowContainerTests` 在真实的 PostgreSQL、Redis 和 Kafka 上跑通了 6 条完整流程：
- 同意退款
- 还价后接受
- 拒绝后申请平台介入，管理员部分退款
- 商家超时，自动同意
- 已付款订单取消后自动退款
- 撤销后用新 key 再次申请

每条流程的模拟网关都先失败一次，再重试成功。测试一次就通过，没有发现生产代码的缺陷。

**已完成**
- 稳健性（第 24 节第 1–6、8 条）：
  - 截止时间处理器遇到 skipped 就结束本轮。
  - 退款 claim 拆成两条有索引的查询：先领过期租约，再领到期的 PENDING。
  - claim 失败时只让这一条退款退避，指标 outcome 记为 `backed_off`，不计尝试次数。
  - 网关线程池加上限 `app.after-sales.refund-gateway.max-threads`（默认 4）。
  - 每个 tick 设时间预算 `app.after-sales.poller.tick-budget`（默认 5s）。
  - 新增 H2 上验证 SKIP LOCKED 的测试。
  - 少扣款的取消会在提交后记 WARN，并计数 `onlineorder.aftersales.cancellations.undercaptured`。
- 商家端：
  - 同步接口 `OrderLifecycleCommands.decideAfterSalesCase`。依赖反转：`service.AfterSalesMerchantCommands` 由 `aftersales.AfterSalesMerchantDecisions` 实现。
  - 投影消费者 `MerchantAfterSalesConsumer`，消费组 `app.after-sales.merchant-group`，按 `data.version` 只前进。
  - 接口 `/merchant/after-sales`：列表、approve、reject、offer。
- 管理员端：
  - `/admin/after-sales`：队列、详情、`arbitrate`（需要 Idempotency-Key），审计 `after_sales_case.arbitrate`。
  - `/admin/refunds`：列表和 `{id}/retry`，审计 `refund.retry`。
  - AppConfig 为这两类路径加了显式的 ROLE_ADMIN 匹配规则。
- 通知：消费者在 `fulfilment.notification`，消费组 `app.after-sales.notification-group`，`dedup_scope = 'case:' + caseId`。所有已扣款且金额大于 0 的取消通知，都统一写"将自动退款"。

**与前文的偏差**
1. 通知范围比第 10.3 节宽：开单、申请平台介入、所有关闭原因（包括 WITHDRAWN）、顾客拒绝还价都会通知。case_closed 的标题是 "Refund request closed"。
2. 商家命令要求投影行已经存在：消费者处理到这张单之前，商家操作返回 404。所以在 h2 profile（Kafka 关闭）下，商家看不到售后单，也无法处理，和商家订单收件箱的现状一致；商家超时仍会自动同意。
3. 商家端没有详情接口（第 11.3 节没有定义）。状态过滤值会转成大写；管理员的过滤值区分大小写，两边不一致（见待办）。
4. 管理员重试写在 `AfterSalesAdminService`（加锁 → `ADMIN_RETRY` → 带 version 的 save），重试后 `last_error` 保留到下一次尝试。管理员队列里，开放状态按截止时间由近到远排序。
5. 通知代码在 `service` 包里，用字符串字面量比较售后的 kind、action、resolution，以免引入包循环。
6. listener 容器从 8 个增加到 10 个。在默认并发 3 的情况下是 30 个消费线程，等于默认 Hikari 连接池的 30；但 prod overlay 的连接池只有 20。

**待办（审查给出，非阻断）**
- **P4 前端需要知道的**：
  - `proxyPaths.ts` 加 `/after-sales`（`/merchant`、`/admin` 已有）。
  - 顾客命令和管理员仲裁需要 Idempotency-Key，重试时复用同一个 key；第二次申请必须用新 key。商家命令不带 key。
  - h2 下商家收件箱是空的。
  - 新的 DTO 要和后端的字段顺序一致，其中 `MerchantAfterSalesCaseDto` 和管理员的 `RefundDto[]` 列表还没有纳入 `ApiContractIntegrationTests`。
- **P5 文档和运维**：
  - 在 docs/operations.md 写运行手册：FAILED 退款、`backed_off`、少扣款的取消、V12 的 order_notifications 唯一键不能在线执行。
  - 加 promtool 告警规则：refunds `outcome=failed`、undercaptured。
  - README 的模块表加上售后模块。
  - AGENTS.md 的容器测试列表加上 `AfterSalesFlowContainerTests`。
  - 在 10 个 listener 的情况下重跑 k6 order-flow，并评估 prod 连接池 20 是否够用。
- **小修**：
  - 新的 POST 接口（arbitrate、refund retry、商家的 approve/reject/offer）还没有限流规则。
  - 管理员状态过滤改为不区分大小写。
  - 一直失败的 claim 只会无限退避，永远不会变成 FAILED，需要设上限。
  - 网关线程全部被卡住时，要能观察到这种状态。
  - `AuditLogIntegrationTests` 中 `refund.retry` 断言真实的 order_id。
  - application.yml 里 `refund.retry-backoff` 的注释补上"claim 失败也用这个间隔"。
  - 售后通知没有过期事件保护：从死信重放的旧事件仍会写通知，这一点和订单通知的现状一致。

---

## 26. P4 实施记录（提交 6017b71 到 ddaa775；P5 开始前必读）

做法：先单独提交共享 API 层和 fake backend（6017b71），再让顾客、商家、管理员三块界面在各自的 worktree 里并行完成，按商家（b0f8b06）、管理员（f583ce4）、顾客（c983903）的顺序 cherry-pick 到 `feature/after-sales`。只有 `App.css` 末尾追加的样式有文本冲突，两边都保留。之后做了一轮只读审查（78a2262），最后用 `npm run deploy:backend` 重新生成后端内嵌的前端产物（ddaa775）。

门禁：在 `frontend/` 下依次运行 `npm ci`、`typecheck`、`lint`、`npm test`、`build`，全部通过。前端测试从 111 个增加到 161 个，0 失败。新代码里没有 `any`、`as` 断言（`as const` 除外）和 `!`。

对抗审查只读三件事：幂等 key 的复用、过期响应的丢弃、解码的严格程度。结论如下：
- 必须修的有两处，都已修复（78a2262）：
  - 管理员仲裁的响应会中止**当时选中的另一张单**的详情读取，那张单会一直停在 "Loading"。它还会中止期间切换过去的其他状态的列表读取，导致列表显示为空。
  - 修复后：只有响应对应的单仍被选中时，才替换详情；列表改为重新读取，而不是中止。退款重试也同样改为重新读取。
  - 新增回归测试 "keeps loading another case selected while an arbitration was answered"。已确认这个测试在修复前失败、修复后通过。
- 另修了一个不稳定的测试：48 小时的倒计时只允许显示 "1d 23h"，但 `useNow` 的时钟可能比 fake 的时钟慢一拍，显示成 "2d 0h"。
- 其余项逐一检查后没有发现问题：
  - 三处的 key 都按 payload 复用：失败后重试用同一个 key；payload 变了就换新 key；成功后丢弃。
  - 第二次申请的 slot 带 attempt，所以一定会用新 key。
  - 读取都经过 `useLiveResource` 或 `useLatestRequest`，命令结果会丢弃在它之前发出的读取。
  - 解码器全部是严格的。

**已完成**
- API 层（6017b71）：
  - `dto.ts` 新增常量：`AFTER_SALES_STATUSES`、`AFTER_SALES_OPEN_STATUSES`、`AFTER_SALES_FINAL_STATUSES`、`AFTER_SALES_REASONS`、`AFTER_SALES_KINDS`、`AFTER_SALES_MERCHANT_DECISIONS`、`AFTER_SALES_RESOLUTIONS`、`AFTER_SALES_CLOSE_REASONS`、`AFTER_SALES_ACTIONS`、`AFTER_SALES_ACTOR_TYPES`、`REFUND_STATUSES`、`AFTER_SALES_SCOPES`、`ARBITRATION_DECISIONS`。
  - 对应的接口和解码器有 `decodeAfterSalesLine`、`decodeAfterSalesHistoryEntry`、`decodeRefund`、`decodeAfterSalesCase`、`decodeEligibleOrder`、`decodeAfterSalesOverview`、`decodeMerchantAfterSalesCase`。
  - `ERROR_CODES` 新增四个错误码。
  - `endpoints.ts` 新增第 12.1 节列出的全部 16 个端点函数。顾客命令和仲裁都要求传 key，商家命令和退款重试不带 key。
  - `proxyPaths.ts` 新增 `/after-sales`。
- Fake backend：`src/test/fakeAfterSales.ts`，在 `fakeBackend.ts` 的路由表末尾注册。它实现了：
  - 角色检查：顾客只要登录即可，别人的单返回 404；商家每次请求都检查账号是否启用，未启用返回 403；管理员需要 ROLE_ADMIN。
  - 第 5.2 节的转换，以及 alreadyDone 的判断顺序。
  - 409 的 code 和文案，与后端逐字一致。
  - 硬截止（顾客和商家的应答）。
  - 幂等重放：同一个 key 返回该 key 绑定的单的**当前**状态；同一个 key 搭配不同请求返回 409 CONFLICT；失败的请求不记录 key。
  - 已付款订单取消时，自动开一张 ORDER_CANCELLED 单。
- 共享的界面工具（第 12 节没有列出）：
  - `components/Countdown.tsx`、`hooks/useNow.ts`
  - `lib/format.ts` 的 `timeLeft`
  - `lib/forms.ts` 的 `parseAmountInput`、`isPartialAmount`
  - `lib/labels.ts` 中状态、原因、退款状态、历史动作、resolution、关闭原因的英文标签
  - `App.css` 中共享的售后样式
- 商家（b0f8b06）：
  - `MerchantConsole` 增加 "Orders" / "After-sales" 页签。
  - `useMerchantAfterSales` 每 15s 轮询一次；403 时停止轮询并显示停用提示；命令不带 key；遇到 404、409 或 403 时重新读取列表。
  - `MerchantAfterSalesList` 提供倒计时；截止后按钮禁用；拒绝必须填理由；还价金额必须小于申请金额。
- 管理员（f583ce4）：
  - `AdminConsole` 的 `SECTIONS` 新增 After-sales。
  - `useAfterSalesAdmin` 在顶层创建，与 `useAdminTools` 并列。
  - `AfterSalesAdminPanel` 包括：按状态查看的队列；详情，含完整历史、退款的 attempts 和 `last_error`；仲裁表单，说明必填，PARTIAL 金额必须小于申请金额，带 key；失败退款表格和重试按钮。
- 顾客（c983903）：
  - `useAfterSales` 在 `OrderingPage` 创建，与 `useOrders` 并列。
  - `AfterSalesRequestForm` 支持整单或按菜品申请；数量上限是下单数量；有金额预览。
  - `AfterSalesCaseView` 包括：状态、各项金额、倒计时、商家和平台的说明、退款状态，以及可展开的历史（按需读取详情接口）；各状态对应的按钮；撤销前要确认。
  - 撤销后在窗口内显示 "Request a refund again (1 left)"。
  - ORDER_CANCELLED 单显示为 "Automatic refund {金额}: Refunding / Refunded"。
- 新增测试：
  - API 和 fake：`client.test.ts` 新增 1 个，`dto.test.ts` 新增 6 个，`fakeAfterSales.test.ts` 6 个，`format.test.ts` 新增 4 个。
  - 商家：`MerchantConsole.test.tsx` 新增 9 个。
  - 管理员：`AdminConsole.test.tsx` 新增 7 个（其中 1 个是审查时补的）。
  - 顾客：`AfterSalesRequestForm.test.tsx` 3 个，`AfterSalesCaseView.test.tsx` 7 个，`App.test.tsx` 新增 5 个，访客测试另外断言不会请求 `/after-sales`。
  - 切换工作区：`Workspaces.test.tsx` 新增 2 个，失败的申请和失败的仲裁在切换工作区后仍复用原 key。

**与前文的偏差**
1. `ERROR_CODES` 没有 `afterSalesCaseExists`：后端已取消这个错误码（第 23 节偏差 1）。`AfterSalesCase` 和 `EligibleOrder` 带 `attempt_no`（第 23 节偏差 3）。
2. 历史的 `action` 按 `AFTER_SALES_ACTIONS` 中的 17 个值严格解码。后端以后新增动作时，必须先发布能识别它的前端。状态、kind、退款状态也一样严格。
3. 端点签名：
   - `escalateAfterSalesCase(caseId, note | null, key)`：说明为空时不带请求体。
   - `offerMerchantAfterSales(caseId, amount, note | null)`：note 为空时请求体里不写这个字段。
   - 金额以 JSON 数字发送。
4. Fake 的数据结构和第 12.5 节不同：
   - 退款嵌在所属的单里，没有单独的 `refunds` 数组，管理员的退款列表由单派生。
   - `afterSalesKeys` 的值是 `{fingerprint, caseId}`。
   - 另有 `afterSalesRequestDeadline` 和 `nextAfterSalesId`。
   - `FakeContext` 新增 `requireSession`。
   - 商家收件箱直接由单派生，所以 fake 里商家**立刻**就能看到新单；真实后端要等 Kafka 投影写入（h2 下为空，见第 25 节偏差 2）。
   - fake 不跑超时，测试直接改 state 来模拟。
5. 顾客：
   - `open(order, attemptNo, draft)` 比第 12.2 节多一个 `attemptNo`，slot 写成 `open:{orderId}:{attempt}`。
   - `pending` 和 `errors` 按目标分别保存。
   - 页面可见、且有开放或 REFUNDING 的单时，每 10s 轮询一次。
   - overview 在取消订单后、页面重新显示时、以及点 Refresh 时重新读取。
   - 截止后禁用接受、拒绝还价和申请平台介入；撤销是软截止（第 19.1 条），截止后仍可用。
   - 遇到 404 或 409 时重新读取。
   - 历史由同一文件里的 `useAfterSalesCaseDetail` 读取。
6. 商家：
   - 每个页签的 section 各自创建 hook。所以打开 After-sales 时订单列表不再轮询，切换页签会重置过滤条件。
   - 倒计时文案随状态变化。
   - 前端只校验"最多两位小数"；0 位小数的币种由后端返回 400。
7. 管理员：
   - key 的 payload 用解析后的金额和去掉首尾空格的说明拼成。
   - 仲裁成功后重新读取队列，已裁决的单离开 IN_ARBITRATION 列表。
   - 退款重试的错误显示在表格上方。
   - 不轮询，打开页签和点 Refresh 时读取。
8. 后端内嵌的前端产物按以往做法重新生成。`processResources` 排除 `public/**`，打包时总是使用新构建的产物，所以这一步不影响 jar 的内容。

**待办（非阻断）**
- 时间戳是不带时区的 LocalDateTime，前端按浏览器本地时间解析。浏览器和服务器不在同一时区时，倒计时会有偏差。现有的其他时间戳也有这个问题。
- 顾客只在有开放或退款中的单时才轮询。订单刚变成 COMPLETED 时，"Request a refund" 要等页面重新显示或点 Refresh 后才出现。
- 管理员仲裁沿用 `useAdminTools` 的单 slot 写法：在两次重试之间去仲裁另一张单，会丢掉第一张单的 key。之后再重试第一张单，会拿到 409 状态冲突，而不是重放结果。
- 第 25 节的后端待办已处理（2026-10-22 核实）：`MerchantAfterSalesCaseDto` 和管理员的 `RefundDto[]` 已纳入 `ApiContractIntegrationTests`；管理员状态过滤已不区分大小写（`AfterSalesAdminService` 统一转大写）。
- P5 写文档时一并说明：前端的售后入口和 h2 下商家收件箱为空。

---

## 27. 实施总结（提交 e8b5f77 到 55119c0；P5 写）

P5 开始时，`git log --oneline master..HEAD` 共 38 个提交。每个阶段先提交代码，再单独提交一次实施记录：

| 阶段 | 代码提交 | 实施记录 | 内容 |
|---|---|---|---|
| 设计 | e8b5f77 | — | 本 ADR |
| P1 | 39c0f84 | cbdab99（第 21 节） | 迁移前锁定订单和配送转换行为的测试 |
| P2 | 7d1204a、64fc095、292e931、d804199 | 383b9b5（第 22 节） | 转换指标改为提交后计数；状态机组件；配送和订单迁移到状态机，写入改为 CAS |
| P3a | 0bf521e 到 8ce1e60，共 7 个 | bb41f2f（第 23 节） | `Money` 补充；V12、V12_1、V12_2；支付记录；售后和退款状态机；事件契约；单一写入者；顾客 API |
| P3b | d2fa479、1adce61、3bc6e6c、6445dc6、61017e9 | 4241868（第 24 节） | 取消自动退款；截止时间轮询器；退款执行器；demo profile；测试 JVM 堆 |
| P3c | 045039d 到 1ebafd4，共 8 个 | 0d8e2d1（第 25 节） | 轮询器稳健性；管理员端；通知；商家端；端到端、竞态和角色测试 |
| P4 | 6017b71 到 ddaa775，共 6 个 | 55119c0（第 26 节） | 前端 API 层和 fake backend；商家、管理员、顾客界面；审查修复；重新生成内嵌产物 |

P5 的文档提交在这个范围之后。

**最终状态**
- **通用组件**：`com.laioffer.onlineorder.statemachine` 共 6 个类（`StateMachine`、`Transition`、`Guard`、`Decision`、`CompareAndSet`、`StaleTransitionException`），只依赖 JDK。四个声明都是 `static final DEFINITION`：`model.OrderStateMachine`、`fulfilment.dispatch.DeliveryStateMachine`、`aftersales.AfterSalesStateMachine`、`aftersales.RefundStateMachine`。
- **订单与配送迁移**：
  - `orders.status` 和 `deliveries.status` 的每次写入都先 `decide`，再用 CAS 写（`OrderRepository.compareAndSetStatus`；`DeliveryRepository.compareAndSetStatus`、`compareAndSetRider`），行数不为 1 时抛 `StaleTransitionException`。`SELECT ... FOR UPDATE` 仍是决策点。
  - 409 的错误码和文案、no-op、历史、事件和审计与迁移前一致（第 22 节）。`onlineorder.order.transition` 改为提交后计数。
- **售后后端**：
  - 迁移：V12 建售后单、行、历史、`refunds`、`order_payments`、`merchant_after_sales_cases`，并给 `order_notifications` 加 `dedup_scope`；V12_1（Java）加币种检查；V12_2 给 `idempotency_requests` 加 `case_id`；P5 之后 V12_3 给 `refunds` 加 `claim_failures`（后续事项第 9 条）。
  - 写入：`AfterSalesTransitions` 是售后单的单一写入者，CAS 条件是 `id + status + version`。已付款订单取消时，经 `service.CancellationRefunds` 在同一事务里开 ORDER_CANCELLED 单和退款。商家决策经 `OrderLifecycleCommands.decideAfterSalesCase` 同步进入。
  - 接口共 16 个：
    - 顾客：`POST /orders/{orderId}/after-sales`、`GET /after-sales/cases`、`GET /after-sales/cases/{caseId}`，以及 `POST /after-sales/cases/{caseId}/` 下的 `accept-offer`、`decline-offer`、`escalate`、`withdraw`。顾客的 POST 都要求 `Idempotency-Key`。
    - 商家：`GET /merchant/after-sales`，`POST /merchant/after-sales/{caseId}/` 下的 `approve`、`reject`、`offer`，不带 key。
    - 管理员：`GET /admin/after-sales`、`GET /admin/after-sales/{caseId}`、`POST /admin/after-sales/{caseId}/arbitrate`（要求 key）、`GET /admin/refunds`、`POST /admin/refunds/{refundId}/retry`。
  - 新错误码 4 个：`AFTER_SALES_NOT_ELIGIBLE`、`AFTER_SALES_STATUS_CONFLICT`、`AFTER_SALES_DEADLINE_PASSED`、`REFUND_STATUS_CONFLICT`。审计动作 2 个：`after_sales_case.arbitrate`、`refund.retry`。
  - 事件：topic `after-sales-events`，7 个 `aftersales.*` 事件类型，都是 INITIAL。消费组 `onlineorder-merchant-aftersales` 维护商家投影（只前进），`onlineorder-aftersales-notification` 写顾客通知。
  - 执行：轮询器 `AfterSalesPoller`（线程 `after-sales-poller`）每个 tick 先触发到期的截止时间（`AfterSalesDeadlineProcessor`），再执行到期的退款（`RefundExecutor`，网关调用在事务外，网关是 `SimulatedRefundGateway`）。开关 `app.after-sales.poller.enabled` 默认跟随 `app.kafka.enabled`；`demo` profile 缩短时限并打开轮询器。
  - 指标：`onlineorder.aftersales.steps{action,from,to}`、`onlineorder.aftersales.deadlines{trigger,outcome}`、`onlineorder.aftersales.refunds{outcome}`、`onlineorder.aftersales.cancellations.undercaptured`、`onlineorder.aftersales.merchant_decisions{decision}`；P5 之后加了网关线程的 gauge 和 `onlineorder.aftersales.refund_gateway.saturated`（后续事项第 10 条）。
- **前端**：
  - API 层：`proxyPaths.ts` 加 `/after-sales`；`dto.ts` 加 DTO 和严格解码器；`endpoints.ts` 加 16 个端点函数；fake backend 是 `src/test/fakeAfterSales.ts`。
  - 顾客在 `OrderingPage` 的订单上申请和跟进（`AfterSalesRequestForm`、`AfterSalesCaseView`、`useAfterSales`）。
  - 商家在 `MerchantConsole` 的 After-sales 页签处理（`MerchantAfterSalesList`、`useMerchantAfterSales`）。
  - 管理员在 `AdminConsole` 的 After-sales 区块仲裁和重试退款（`AfterSalesAdminPanel`、`useAfterSalesAdmin`）。
  - 后端内嵌的前端产物已重新生成（ddaa775）。
- **文档**：P5 更新 README.md、AGENTS.md、docs/operations.md（第 12 节是售后的运行手册），在 `deploy/observability/prometheus/` 加售后告警，并把本 ADR 改为 Accepted。

**测试**
- 后端：最后一次全量门禁 1527 个测试，0 失败，0 跳过。P1 结束时是 873 个，P3b 结束时是 1351 个。
  - P5 之后的代码小修（58983b5 到 68a1d85，后续事项第 7–11、13、14 条）之后：全量门禁（`--rerun`）1551 个测试，0 失败，0 跳过，Testcontainers 测试都实际执行了。前端仍是 161 个。
  - 状态机：`StateMachineTests`、`OrderStateMachineTests`、`DeliveryStateMachineTests`、`AfterSalesStateMachineTests`、`RefundStateMachineTests`。
  - 售后的 schema、顾客 API、管理员、截止时间、取消退款、退款执行器、商家和通知，各有 H2 和 PostgreSQL 两套集成测试。
  - 端到端：`AfterSalesFlowContainerTests`（第 25 节的 6 条流程）。
- 前端：17 个测试文件，161 个测试，P4 之前是 111 个。后续事项第 20、21 条（65b229e、5aab969）之后是 164 个。

**与原设计的主要偏差**（细节见括号里的节）
1. **声明的位置和 actor 类型**：订单的 `OrderTrigger`、`OrderTransitionContext`、`OrderStateMachine` 在 `model` 包，不在 `service`（第 19.12 条、第 22 节）。售后和退款状态机用自己的 `AfterSalesActor`（CUSTOMER、MERCHANT、ADMIN、SYSTEM），不复用 `OrderActor.Type`（第 23 节）。
2. **触发器由调用方选定**，而且在加锁之前选定。`decide()` 最先检查 actor，不允许的 actor 得到 `IllegalStateException`（500）（第 19 节、第 22 节）。
3. **每单至多两次顾客申请**，替换 D5 的 `UNIQUE (order_id)`：
   - 第 1 张在 `PENDING_MERCHANT` 或 `COUNTER_OFFERED` 时被撤销后，可以在原窗口内再申请一次（`attempt_no`，`UNIQUE (order_id, kind, attempt_no)`；后续事项第 24 条）。
   - `refunds` 仍有 `UNIQUE (order_id)`，所以每单至多一笔退款。
   - 售后的幂等记录经 `idempotency_requests.case_id`（V12_2）绑定到售后单；通知去重加了 `dedup_scope`（第 20、23 节）。
4. **错误码**：没有 `AFTER_SALES_CASE_EXISTS`。已有售后单和超出可退余额都返回 409 `AFTER_SALES_NOT_ELIGIBLE`（第 23 节）。
5. **截止时间**：硬截止只适用于顾客和商家的应答。撤销和管理员命令是软截止，谁先拿到行锁谁生效（第 19.1 条）。
6. **支付记录与取消退款**：
   - 新增 `order_payments`（D16，审查后加入）。没有扣款记录或总额为 0 的已付订单，取消时不建售后单（D12）。
   - ORDER_CANCELLED 单开单时不和扣款金额比较。扣款少于订单总额时，提交后记 WARN 并计数 `onlineorder.aftersales.cancellations.undercaptured`，执行器在调用网关前把这笔退款判为 FAILED（第 24、25 节）。
7. **退款执行比第 9 节更严**（第 24、25 节）：
   - claim 事务里校验金额，并要求 `payment_reference` 等于支付记录的 `provider_reference`。
   - 网关调用在有上限的线程池上：`app.after-sales.refund-gateway.timeout` 默认 10s，`app.after-sales.refund-gateway.max-threads` 默认 4。
   - 每个 tick 有时间预算 `app.after-sales.poller.tick-budget`，默认 5s。
   - claim 拆成两条查询：先领过期租约，再领到期的 PENDING。
   - claim 事务失败时只让这一条退避，outcome 记为 `backed_off`，不计尝试次数。
8. **依赖反转**：订单模块定义 `service.CancellationRefunds` 和 `service.AfterSalesMerchantCommands`，由 `aftersales` 实现，`service` 包不 import `aftersales`（第 24、25 节）。
9. **商家端**：命令要求投影行已经存在，否则返回 404。所以 h2 profile（Kafka 关闭）下，商家看不到也处理不了售后单，商家超时仍会自动同意。没有商家详情接口（第 25 节）。
10. **通知**：范围比第 10.3 节宽，开单、申请平台介入、所有关闭原因和顾客拒绝还价都会通知。已扣款且金额大于 0 的取消，通知都写"将自动退款"（第 25 节）。
11. **退款的 `last_error` 只给管理员**：顾客看到的 `RefundDto` 不带这个字段（第 24 节）。
12. **发布**（运维步骤见 `docs/operations.md` 第 12 节"Rollout (V12-V12_3)"）：
    - 第 15 节"V12 可以在线执行"的说法不成立。V12 给 `order_notifications` 换唯一键要建唯一索引（第 23 节）。
    - 第 15 节"回滚：新表保留，影响无害"也不成立。V12 删掉了旧键 `uq_notification_order_event`，上一版本写通知用的 `ON CONFLICT (order_id, event_type)` 会被 PostgreSQL 拒绝。回滚后，直到再次前滚，上一版本的消费者会把每个订单事件，以及 `delivery.assigned`、`delivery.picked_up`、`delivery.delivered`，都送进死信；它取消的已付订单不建售后单，也就不会自动退款。旧版本 pod 与新版本并存时也是这样。
    - 把旧键 `UNIQUE (order_id, event_type)` 加回去，只在没有任何订单有两条同类型通知时可行；一个订单的第二次售后申请就会产生这样的两条。所以优先前滚。
13. **前端**：
    - 历史的 `action`、状态、kind、退款状态都严格解码。后端新增取值之前，必须先发布能识别它的前端。
    - 顾客有开放或 REFUNDING 的单时每 10s 轮询，商家每 15s 轮询，管理员不轮询。后续事项第 20 条之后，通知轮询读到新通知时，顾客的订单列表和售后 overview 也会重新读取。
    - fake backend 的数据结构与第 12.5 节不同（第 26 节）。
    - 金额输入按币种校验小数位（3a1c244）：商家还价和管理员部分仲裁的 `parseAmountInput` 接受售后单币种的 ISO 4217 小数位数（`minorDigits`，取自 `Intl`），JPY、KRW 这类没有辅币的币种只接受整数，fake backend 对多出的小数返回和后端相同的 400。这取代第 26 节偏差 6 的"前端只校验最多两位小数"。

**后续事项**（汇总第 19、22–26 节；"已完成"一栏已对照代码核实）

| # | 事项 | 来源 | 状态 |
|---|---|---|---|
| 1 | `OrderLifecycleCommands` 的 Javadoc 写清状态机在运行时强制的规则 | 第 22 节 | 已完成：Javadoc 里有每个命令的触发器、状态、actor 和"已完成"语义的表 |
| 2 | `service` ↔ `aftersales` 的包循环 | 第 23 节 | 已完成：见偏差 8 |
| 3 | `RefundDto.last_error` 不返回给顾客 | 第 23 节 | 已完成（P3b，`RefundDto.forCustomer`） |
| 4 | 轮询器稳健性：skipped 结束本轮、claim 拆成两条查询、claim 失败只退避这一条、网关线程上限、tick 预算、H2 的 SKIP LOCKED 测试、少扣款的取消可观测 | 第 24 节第 1–6、8 条 | 已完成（P3c） |
| 5 | 已扣款的取消，通知统一写"将自动退款" | 第 24 节第 7 条 | 已完成（P3c） |
| 6 | 前端代理 `/after-sales`、失败重试复用 key、第二次申请换新 key | 第 23、25 节 | 已完成（P4） |
| 7 | 新 POST 接口的限流 | 第 25 节 | 已完成（58983b5 管理员和商家端，c8b27f0 顾客端）：`RateLimitingFilter` 给管理员的 arbitrate 和退款 retry 各加一个按用户的桶（10 个，每 6s 补 1 个，同 outbox retry/abandon），商家的 approve、reject、offer 共用一个按用户的桶（30 个，每 2s 补 1 个，同订单状态变更）。顾客的 5 个 POST（开单 `POST /orders/{id}/after-sales`，以及售后单的 accept-offer、decline-offer、escalate、withdraw）共用一个按用户的桶 `customer-after-sales`（20 个，每 3s 补 1 个，同购物车结账：它们和结账一样是顾客自己的、带 `Idempotency-Key` 的写操作，正常使用每单最多两次申请、每个售后单一两个动作，远低于这个量）。同一个 key 的重放也计数，和结账、订单状态变更一致：过滤器在幂等检查之前运行，不读 `Idempotency-Key`；被 429 拒绝的请求没有到达处理器，之后用同一个 key 重试是安全的：这个 key 已经执行过的，返回它绑定的售后单的当前状态，没执行过的照常执行一次。这 5 个映射的 id 限定为十进制数字（`{orderId:\d+}`、`{caseId:\d+}`）：不限定时 Spring 转 `Long` 也接受 `+42`、`0x2A`、空白和非 ASCII 数字，这些写法匹配不到限流规则的 `\d+`，可以绕过限流；现在它们是 404（`AfterSalesApi*IntegrationTests`）。之后其他用 `\d+` 匹配的规则背后的 13 个映射（商家的 approve/reject/offer、管理员的 arbitrate 和退款 retry、订单状态 PATCH、死信重放、outbox retry/abandon、目录和员工的 PATCH）也这样限定了；`RateLimitedRouteMappingTests` 扫描所有控制器（包括按条件注册的），检查每个被限流规则覆盖的映射的 id 都是 `\d+`、其他写法匹配不到，并固定被覆盖映射的清单。`RateLimitingFilterTests` 覆盖路径匹配、编码和不规范的路径、按登录名和匿名按 IP 的键、桶空时 5 个命令都返回 429 以及重放计数。这是请求频率的限制，不是第 17 节刻意不做的业务防滥用（比如每个顾客一段时间内的退款次数） |
| 8 | 管理员状态过滤不区分大小写 | 第 25、26 节 | 已完成（36904ae）：`GET /admin/after-sales` 和 `GET /admin/refunds` 先 trim 并转大写，和商家端一致；锁定小写返回 400 的 P3c 单元测试改用未知状态；fake backend 同步 |
| 9 | 一直 claim 失败的退款需要上限 | 第 25 节 | 已完成（e175477）：V12_3 加 `refunds.claim_failures`，每次退避加 1（版本不变），退款每保存一步（含管理员重试）归零。连续失败达到 `app.after-sales.refund.max-claim-failures`（默认 10，至少 1，启动时校验）时，最后一次在自己的事务里、按 claim 时的版本放弃：PENDING 走 CLAIM 再 GIVE_UP，过期租约直接 GIVE_UP，尝试次数不变，不调网关，状态机的表不变。期间被别人改过或放弃本身失败时，照旧退避 |
| 10 | 网关线程全部被卡住时要能观察到 | 第 25 节 | 已完成（db55f14）：`RefundExecutor` 是 `MeterBinder`，有 gauge `onlineorder.aftersales.refund_gateway.threads.busy` 和 `.threads.max`；每次因为没有空闲线程而什么也没 claim 的运行计入 `onlineorder.aftersales.refund_gateway.saturated`（启动时注册为 0）。告警 `OnlineOrderRefundGatewaySaturated`（持续 10 分钟），promtool 测试 2 组 |
| 11 | `refund.retry` 的审计测试断言真实的 order_id | 第 25 节 | 已完成（fac4c13）：断言等于被重试退款所属售后单的 `order_id` |
| 12 | `app.after-sales.refund.retry-backoff` 的注释补上"claim 失败也用这个间隔" | 第 25 节 | 已完成（P5，`application.yml` 的注释） |
| 13 | 售后通知的过期事件保护 | 第 25 节 | 已完成（8096186）：`AfterSalesNotificationService` 在 processed_events 标记之后调用 `EventSequenceTracker.advance`，过期事件不写通知（计入 `onlineorder.events.stale_skipped`）；只有会写通知的事件（`OrderNotificationService.notifiesOf`）推进位置。后来订单和配送通知也加了同样的保护（128b889；`OrderEventHandlerService` 对每个订单事件、`DeliveryNotificationService` 只对 assigned、picked_up、delivered 推进位置，`StaleEventPostgresIntegrationTests` 两个场景） |
| 14 | `ApiContractIntegrationTests` 覆盖 `MerchantAfterSalesCaseDto` 和管理员的 `RefundDto[]` 列表 | 第 25、26 节 | 已完成（68a1d85）：锁定商家列表里还价中的售后单及其行，以及管理员退款列表（含 `last_error`）的字段顺序 |
| 15 | 10 个 listener 容器下重跑 k6 order-flow，评估 prod 连接池 | 第 25 节 | 已完成（2026-10-21）：k6 order-flow 在单个后端上跑了三组配置（池 30 / 并发 3、池 20 / 并发 3、池 20 / 并发 1），每组 100–700 VU；另在 300 VU 下跑了真积压和全 topic 重放积压。所有轮次都是 0 个 Hikari 超时、0 个失败请求、0 个死信；池 20 和池 30 的吞吐、p95、p99 相差不到几个百分点。建议 prod 保持 `DATABASE_POOL_MAX_SIZE=20`、`FULFILMENT_LISTENER_CONCURRENCY=3`，不改 `deploy/`：并发 1 时单 pod 的消费跟不上（500 VU 时付款到商家收件箱的 p95 是 44s，并发 3 时是 2.2s）。数据、前提和限制见 `docs/operations.md` 第 12 节"Connections: listener threads and the pool" |
| 16 | promtool 告警规则：`onlineorder.aftersales.refunds{outcome=failed}`、`onlineorder.aftersales.cancellations.undercaptured` | 第 25 节 | 已完成（P5）：`alerts.yml` 加了 `OnlineOrderRefundsGivenUp`、`OnlineOrderRefundsBackingOff`、`OnlineOrderCancellationsUndercaptured`，`alerts.test.yml` 加了 5 组测试。截止时间失败的告警后来由 2616cea 补上：`OnlineOrderAfterSalesDeadlinesFailing`，条件是每个 5 分钟窗口都有 `outcome="failed"`、持续 15 分钟（一张单反复失败约每分钟 2 次，短暂的锁超时不会触发），promtool 测试 2 组，运行手册 `docs/operations.md` 第 12 节"Deadlines"。网关线程被占满的指标和告警已由第 10 条补上 |
| 17 | 限制骑手取餐后的取消 | 第 19.18 条 | 已完成（2026-10-21，按用户决定"只退部分"）：取消仍然允许，但退款按比例。订单模块从 `delivery.picked_up` 记录取餐时间（V12_4 `orders.picked_up_at`，`OrderLifecycleCommands.recordPickup`，只记第一次）；取消时在订单行锁下读取。顾客在取餐后取消，自动退款为总额的 `app.after-sales.picked-up-cancel-refund-percent`（默认 50，按币种最小单位银行家舍入；为 0 时不退）；售后单的 requested 仍是订单总额，approved 和退款是这个比例。管理员取消仍全额退款。没有新的状态、kind 或事件取值。取餐只在订单未取消、未完成时记录，所以记下的取餐一定早于取消；取消通知和"Refund completed"通知写明退的是多少（"8.04 USD of your 16.09 USD payment"，比例为 0 时写不退）。`aftersales.case_opened` v1 的描述说 ORDER_CANCELLED 单批准订单总额，属性没变所以没有发布新版本，docs/correctness.md "Event contracts" 写明了例外。仍有的缺口：取餐事件到达订单模块前（outbox 发布延迟内）的取消仍是全额 |
| 18 | P2 留下的小问题：`completeDeliveredOrder` 忽略第一步的 `TransitionOutcome`；`firingOnlyFrom` 在同族出现两个相同的 from 和目标时有歧义；`DispatchService` 里 `from.holdsRider()` 为假的分支不可达；把 `OrderActor` 移到中立的包，解开 `model` ↔ `fulfilment.order` 的循环 | 第 22 节 | 已完成（2616cea）：`completeDeliveredOrder` 每一步都要求状态机真的执行了（`TransitionOutcome.requireApplied`），没执行就抛 `IllegalStateException`，事务回滚，不会拿旧订单走下一步；`OrderStateMachine` 在类初始化时把商家和超时两族触发器按"唯一 from 状态 + 目标"建索引（`soleMoves`），同族两个触发器的移动相同时类初始化失败，取代按列表顺序取第一个的 `firingOnlyFrom`（`OrderStateMachineTests` 两个新测试）；`DispatchService` 只看目标状态是否还占用骑手，每个移动都从占用骑手的状态出发（`DeliveryStatusTransitionTableTests` 已锁定），不可达分支去掉了；`OrderActor` 从 `fulfilment.order` 移到 `model`，`model` 不再引用 `fulfilment.order`，循环解开，只改 import，行为不变。没有给"状态机拒绝送达步骤"写测试：现在的声明总会执行这两步，构造不出被拒的情况 |
| 19 | 时间戳是不带时区的 LocalDateTime，浏览器和服务器不在同一时区时倒计时有偏差 | 第 26 节 | 已完成（99eaa90、b49d286）：服务器固定用 UTC。`OnlineOrderApplication.main` 在 Spring 启动前把 JVM 默认时区设为 UTC，Gradle 的 `test` 和 `bootRun` 用 `-Duser.timezone=UTC` 启动 JVM，所以 `LocalDateTime.now()`、PostgreSQL 会话的 `TimeZone`（`CURRENT_TIMESTAMP` 写进 `TIMESTAMP` 列也是 UTC）、H2 和 Flyway 都用 UTC。JSON 格式不变，仍不带偏移。前端只用 `src/lib/format.ts` 的 `parseTimestamp` 解析后端时间戳，不带偏移的按 UTC 读（补 `Z`）；所有时间戳都改了，不只是售后的，倒计时、截止判断和显示（换算到浏览器时区）在任何时区都一致，ESLint 禁止其他地方用 `Date.parse` 和 `new Date(value)`。fake backend 按 UTC 写时间，Vitest 在 `America/New_York` 下运行。之前由其他时区的服务器写入的行保留当时的本地时间（`docs/operations.md` 第 3 节）。测试：`ServerTimeZoneTests`、`ServerTimeZonePostgresTests`、`format.test.ts`。后端写进文字的时间（通知里的截止时间、`AFTER_SALES_DEADLINE_PASSED` 和 `AFTER_SALES_NOT_ELIGIBLE` 的 409 消息）都带上 "UTC" 字样，因为界面原样显示消息，而其他时间都换算成浏览器时区 |
| 20 | 订单刚变成 COMPLETED 时，"Request a refund" 要等页面重新显示或点 Refresh 才出现 | 第 26 节 | 已完成（65b229e）：通知本来就每 15s 轮询。某次读取带来的通知 id 比之前所有读取都大时（`useNotifications` 的 `onNewNotifications`），`OrderingPage` 重新读取订单列表和售后 overview。第一次读取只记基线，没有新的定时器，也没有新的常态轮询；后端在订单变成 COMPLETED 之后才写 "Order completed" 通知。关掉通知轮询时，仍要点 Refresh 或重新显示页面。订单读取现在会在没有用户操作时发起，所以取消成功后也重新读取订单，丢掉在它之前发出、还没返回的读取（否则会把订单改回取消前的状态）。测试：`App.test.tsx` 的 "offers a refund once a polled notification says the order completed" 和 "keeps a cancel's result over an order read a notification started before it" |
| 21 | 管理员仲裁是单 slot 的 key：两次重试之间去仲裁另一张单，会丢掉第一张单的 key | 第 26 节 | 已完成（5aab969）：`useAfterSalesAdmin` 按售后单保存 key（slot `arbitrate:{caseId}`），规则同 `useAfterSales`：payload 不变就复用 key，payload 变了就换新 key，成功后只删除这一单的 key。测试：`AdminConsole.test.tsx` 的 "keeps a failed arbitration's key per case while another case is arbitrated" |
| 22 | 运行手册：FAILED 退款和 `backed_off`、少扣款的取消、V12 不能在线执行、V12_2 的外键检查、V12 之前的已付订单没有支付记录时的人工处理、新指标 | D16、第 23、25 节 | 已完成（P5）：docs/operations.md 第 12 节写了 FAILED 退款与 `backed_off`、少扣款的取消、V12 与 V12_2 的上线说明、缺支付记录时的人工处理和新指标 |
| 23 | README 的模块表和前端售后入口、h2 下商家售后收件箱为空、取消 `AFTER_SALES_CASE_EXISTS`、eligible 列表包含扣款 0.00 的订单（申请会返回 400）；AGENTS.md 的容器测试列表加上 `AfterSalesFlowContainerTests` | 第 23、25、26 节 | 已完成（P5；2026-10-22 文档重组后内容在 docs/after-sales.md、docs/frontend-workspaces.md 和 AGENTS.md，容器测试列表含 `AfterSalesFlowContainerTests`） |
| 24 | 撤销后再申请的漏洞：按第 20 节第 1 条，第 1 张单无论从哪个状态撤销都放行第 2 次。商家拒绝后，顾客撤销再申请，商家处理时限重新计时，商家错过时 `MERCHANT_TIMEOUT` 全额退款，绕过了拒绝；从 `IN_ARBITRATION` 撤销再申请同样绕过平台 | 用户决定（2026-10-21） | 已完成（2026-10-21，取代第 20 节第 1 条"任何撤销都放行"的写法）：第 2 次只在第 1 张单从 `PENDING_MERCHANT` 或 `COUNTER_OFFERED` 撤销时允许，依据是第 1 张单 `CUSTOMER_WITHDRAW` 历史行的 `from_status`（`AfterSalesCaseHistoryRepository.findWithdrawnFromStatus`，在订单行锁内读取；`AfterSalesCustomerService.REQUEST_AGAIN_AFTER_WITHDRAWAL_FROM`）。从 `REJECTED`（商家拒绝，或顾客拒绝还价）或 `IN_ARBITRATION` 撤销，第 2 次返回 409 `AFTER_SALES_NOT_ELIGIBLE` "This order cannot be submitted for after-sales again"；没有撤销历史行的也一样。`OrderRepository.findPageOpenToAfterSales`（overview 的 `eligible_orders`）用同样的规则（`EXISTS` 子查询，PostgreSQL 和 H2 都能执行），界面不会再提供会被拒绝的申请；顾客撤销确认框按状态说明撤销后能否再申请。状态机声明、迁移和事件都没变；之前已从这些状态撤销的订单也不再能申请。fake backend 同步。测试：`AfterSalesCustomerServiceTests`（每个撤销来源，以及 SQL 与 Java 的状态集合一致）、`AfterSalesApi*IntegrationTests` 的 `secondRequest_shouldNeedTheFirstWithdrawnBeforeARejection`、`fakeAfterSales.test.ts`、`AfterSalesCaseView.test.tsx`、`App.test.tsx` 的 "offers no other request once a rejected request is withdrawn" |
