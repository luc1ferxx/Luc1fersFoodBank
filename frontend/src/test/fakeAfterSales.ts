import { fieldsOf } from "../api/decode";
import {
  AFTER_SALES_OPEN_STATUSES,
  AFTER_SALES_REASONS,
  type AfterSalesAction,
  type AfterSalesActorType,
  type AfterSalesCase,
  type AfterSalesLine,
  type AfterSalesStatus,
  type EligibleOrder,
  type MerchantAfterSalesCase,
  type Order,
  REFUND_STATUSES,
  ROLES,
  type Refund,
  parseAfterSalesStatus,
} from "../api/dto";
import type { CurrencyCode } from "../api/currency";
import { minorDigits } from "../lib/format";
import { USD, orderNoFor } from "./fixtures";
import type { FakeState } from "./fakeBackend";
import type { FakeContext } from "./fakeFulfilment";
import {
  type FakeHandler,
  type FakeRequest,
  type FakeRoute,
  apiError,
  idParam,
  json,
  requestJson,
} from "./fakeHttp";

const HOUR = 60 * 60 * 1000;

/** The backend's deadlines (ADR 0002 section 20.4 defaults). */
export const AFTER_SALES_TIMEOUTS = {
  requestWindow: 24 * HOUR,
  merchant: 24 * HOUR,
  customer: 48 * HOUR,
  arbitration: 72 * HOUR,
} as const;

/**
 * A time the way the backend writes LocalDateTime: local date and time to the second,
 * without a zone ("2026-10-21T12:00:00"). Relative to the clock, so a deadline the fake
 * sets is still ahead when a test runs.
 */
export const localTimestamp = (offsetMs = 0): string => {
  const date = new Date(Date.now() + offsetMs);
  const pad = (value: number) => String(value).padStart(2, "0");
  return (
    `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}` +
    `T${pad(date.getHours())}:${pad(date.getMinutes())}:${pad(date.getSeconds())}`
  );
};

const cents = (amount: number) => Math.round(amount * 100);

/** A refund with defaults for what a test does not care about (PENDING, no attempt yet). */
export const refundFixture = (
  overrides: Partial<Refund> & Pick<Refund, "id" | "case_id" | "order_id">,
): Refund => ({
  order_no: orderNoFor(overrides.order_id),
  amount: 6.39,
  currency: USD,
  status: "PENDING",
  attempts: 0,
  last_error: null,
  provider_reference: null,
  next_attempt_at: localTimestamp(),
  created_at: localTimestamp(),
  succeeded_at: null,
  ...overrides,
});

/**
 * A customer's request of one Whopper of order `order_id` (Burger Barn), waiting for the
 * merchant until a day from now, with defaults for what a test does not care about.
 */
export const afterSalesCaseFixture = (
  overrides: Partial<AfterSalesCase> & Pick<AfterSalesCase, "id" | "order_id">,
): AfterSalesCase => ({
  order_no: orderNoFor(overrides.order_id),
  restaurant_id: 1,
  kind: "CUSTOMER_REQUEST",
  attempt_no: 1,
  status: "PENDING_MERCHANT",
  reason_code: "QUALITY_ISSUE",
  description: "The burger was cold.",
  requested_amount: 6.39,
  offered_amount: null,
  approved_amount: null,
  refunded_amount: null,
  currency: USD,
  lines: [
    {
      order_item_id: 1,
      menu_item_name: "Whopper",
      quantity: 1,
      unit_price: 6.39,
      amount: 6.39,
      currency: USD,
    },
  ],
  merchant_decision: null,
  merchant_note: null,
  resolution: null,
  close_reason: null,
  admin_note: null,
  deadline_at: localTimestamp(AFTER_SALES_TIMEOUTS.merchant),
  refund: null,
  history: [],
  created_at: localTimestamp(),
  updated_at: localTimestamp(),
  ...overrides,
});

const caseNotFound = () => apiError(404, "After-sales case not found", { code: "NOT_FOUND" });
const forbidden = () => apiError(403, "Access denied", { code: "FORBIDDEN" });
const badRequest = (message: string) => apiError(400, message, { code: "BAD_REQUEST" });
const validationFailed = () =>
  apiError(400, "Request validation failed", { code: "VALIDATION_FAILED" });
/** The 400 of a counter-offer or a partial arbitration that is not part of the requested amount. */
const amountOutOfRange = (current: AfterSalesCase) =>
  badRequest(
    `The amount must be more than 0 and less than the requested ${current.requested_amount.toFixed(2)} ${current.currency}`,
  );
/**
 * The 400 of an amount with more decimals than the case's currency has (none for JPY), or
 * null when it fits (AfterSalesMerchantDecisions.offerAmount, AfterSalesAdminService.amountIn).
 */
const tooManyDecimals = (current: AfterSalesCase, amount: number) => {
  const digits = minorDigits(current.currency);
  const scaled = amount * 10 ** digits;
  return Math.abs(Math.round(scaled) - scaled) > 1e-6
    ? badRequest(`Amount must have at most ${digits} decimal places`)
    : null;
};
const notEligible = (message: string) =>
  apiError(409, message, { code: "AFTER_SALES_NOT_ELIGIBLE" });

/** The statuses each command fires from, in declaration order, and its verb (AfterSalesTrigger). */
const COMMANDS = {
  approve: { from: ["PENDING_MERCHANT"], verb: "approved" },
  reject: { from: ["PENDING_MERCHANT"], verb: "rejected" },
  offer: { from: ["PENDING_MERCHANT"], verb: "given a counter-offer" },
  acceptOffer: { from: ["COUNTER_OFFERED"], verb: "accepted" },
  declineOffer: { from: ["COUNTER_OFFERED"], verb: "declined" },
  escalate: { from: ["REJECTED"], verb: "escalated" },
  withdraw: {
    from: ["PENDING_MERCHANT", "COUNTER_OFFERED", "REJECTED", "IN_ARBITRATION"],
    verb: "withdrawn",
  },
  arbitrate: { from: ["IN_ARBITRATION"], verb: "arbitrated" },
} as const satisfies Record<string, { from: readonly AfterSalesStatus[]; verb: string }>;

type Command = keyof typeof COMMANDS;

/** The 409 AFTER_SALES_STATUS_CONFLICT of a command its status does not allow, word for word. */
const statusConflict = (current: AfterSalesStatus, command: Command) => {
  const { from, verb } = COMMANDS[command];
  return apiError(
    409,
    `After-sales case is ${current}; only ${from.join(" or ")} cases can be ${verb}`,
    { code: "AFTER_SALES_STATUS_CONFLICT" },
  );
};

const firesFrom = (current: AfterSalesStatus, command: Command) =>
  COMMANDS[command].from.some((status) => status === current);

/** The customer's and the merchant's answers have a hard deadline (ADR 0002 section 19.1). */
const deadlinePassed = (current: AfterSalesCase) =>
  current.deadline_at !== null && Date.parse(current.deadline_at) <= Date.now()
    ? apiError(409, `The deadline of this after-sales step passed at ${current.deadline_at}`, {
        code: "AFTER_SALES_DEADLINE_PASSED",
      })
    : null;

/** The case as its customer sees it: no history in lists, never the refund's last_error. */
const customerView = (current: AfterSalesCase, { withHistory }: { withHistory: boolean }) => ({
  ...current,
  history: withHistory ? current.history : undefined,
  refund: current.refund ? { ...current.refund, last_error: undefined } : null,
});

const merchantView = (current: AfterSalesCase): MerchantAfterSalesCase => ({
  case_id: current.id,
  order_id: current.order_id,
  order_no: current.order_no,
  status: current.status,
  reason_code: current.reason_code ?? "OTHER",
  description: current.description ?? "",
  lines: current.lines,
  requested_amount: current.requested_amount,
  offered_amount: current.offered_amount,
  approved_amount: current.approved_amount,
  currency: current.currency,
  merchant_decision: current.merchant_decision,
  merchant_note: current.merchant_note,
  deadline_at: current.deadline_at,
  opened_at: current.created_at,
  updated_at: current.updated_at,
});

/**
 * Moves `current` to `to` the way the backend's single writer does: a new deadline for
 * an open status (none otherwise), a history row, and a PENDING refund on entering
 * REFUNDING.
 */
function step(
  state: FakeState,
  current: AfterSalesCase,
  to: AfterSalesStatus,
  action: AfterSalesAction,
  actorType: AfterSalesActorType,
  { amount = null, note = null }: { amount?: number | null; note?: string | null } = {},
) {
  const from = current.status;
  const now = localTimestamp();
  current.status = to;
  current.updated_at = now;
  current.deadline_at =
    to === "PENDING_MERCHANT"
      ? localTimestamp(AFTER_SALES_TIMEOUTS.merchant)
      : to === "COUNTER_OFFERED" || to === "REJECTED"
        ? localTimestamp(AFTER_SALES_TIMEOUTS.customer)
        : to === "IN_ARBITRATION"
          ? localTimestamp(AFTER_SALES_TIMEOUTS.arbitration)
          : null;
  if (to === "REFUNDING" && current.approved_amount !== null) {
    current.refund = refundFixture({
      id: state.nextAfterSalesId++,
      case_id: current.id,
      order_id: current.order_id,
      order_no: current.order_no,
      amount: current.approved_amount,
      currency: current.currency,
    });
  }
  current.history.push({
    action,
    from_status: from,
    to_status: to,
    actor_type: actorType,
    amount,
    currency: current.currency,
    note,
    occurred_at: now,
  });
}

/**
 * The executor refunded the case's refund: REFUNDED with the refunded amount. Tests call
 * it to let a REFUNDING case finish while the UI polls.
 */
export function settleAfterSalesRefund(state: FakeState, caseId: number) {
  const current = state.afterSalesCases.find((item) => item.id === caseId);
  if (!current?.refund || current.status !== "REFUNDING") {
    throw new Error(`Case ${caseId} has no refund in progress`);
  }
  current.refund.status = "SUCCEEDED";
  current.refund.attempts += 1;
  current.refund.provider_reference = `SIMREFUND-${current.refund.id}`;
  current.refund.succeeded_at = localTimestamp();
  current.refund.next_attempt_at = null;
  current.refunded_amount = current.approved_amount;
  step(state, current, "REFUNDED", "REFUND_SUCCEEDED", "SYSTEM", {
    amount: current.approved_amount,
  });
}

/** app.after-sales.picked-up-cancel-refund-percent at its default. */
export const PICKED_UP_CANCEL_REFUND_PERCENT = 50;

/**
 * `percent` of `amount`, rounded half-even to the currency's minor unit like the backend's
 * Money.multiply (4.505 USD is 4.50, 475.5 JPY is 476).
 */
const shareOf = (amount: number, percent: number, currency: CurrencyCode): number => {
  const unit = 10 ** minorDigits(currency);
  const exact = (Math.round(amount * unit) * percent) / 100;
  const floor = Math.floor(exact);
  const rest = exact - floor;
  const rounded = rest > 0.5 || (rest === 0.5 && floor % 2 === 1) ? floor + 1 : floor;
  return rounded / unit;
};

/**
 * Opens the automatic refund of a paid order that was just cancelled (ADR 0002 D12): an
 * ORDER_CANCELLED case in REFUNDING that requests the order total and refunds it, unless the
 * order was not paid or is free. A customer's cancel after the pickup refunds only
 * PICKED_UP_CANCEL_REFUND_PERCENT of it (section 27, follow-up 17); an admin's cancel refunds
 * it all.
 */
export function openCancellationRefund(
  state: FakeState,
  order: Order,
  wasPaid: boolean,
  cancelledBy: "CUSTOMER" | "ADMIN",
) {
  if (!wasPaid || order.total_price <= 0) {
    return;
  }
  const refunded =
    cancelledBy === "CUSTOMER" && state.orderPickups.has(order.id)
      ? shareOf(order.total_price, PICKED_UP_CANCEL_REFUND_PERCENT, order.currency)
      : order.total_price;
  if (refunded <= 0) {
    return;
  }
  const current = afterSalesCaseFixture({
    id: state.nextAfterSalesId++,
    order_id: order.id,
    order_no: order.order_no,
    restaurant_id: order.restaurant_id,
    kind: "ORDER_CANCELLED",
    status: "REFUNDING",
    reason_code: null,
    description: null,
    requested_amount: order.total_price,
    approved_amount: refunded,
    resolution: "ORDER_CANCELLED",
    currency: order.currency,
    lines: order.items.map((item): AfterSalesLine => ({
      order_item_id: item.id,
      menu_item_name: item.menu_item_name,
      quantity: item.quantity,
      unit_price: item.price,
      amount: (cents(item.price) * item.quantity) / 100,
      currency: item.currency,
    })),
    deadline_at: null,
  });
  current.refund = refundFixture({
    id: state.nextAfterSalesId++,
    case_id: current.id,
    order_id: order.id,
    order_no: order.order_no,
    amount: refunded,
    currency: order.currency,
  });
  current.history.push({
    action: "ORDER_CANCELLED",
    from_status: null,
    to_status: "REFUNDING",
    actor_type: "SYSTEM",
    amount: refunded,
    currency: order.currency,
    note: null,
    occurred_at: current.created_at,
  });
  state.afterSalesCases.unshift(current);
}

/**
 * The after-sales API (ADR 0002 section 11) over `state.afterSalesCases` (newest first,
 * each with its refund and full history):
 *
 * - /after-sales/** and POST /orders/{id}/after-sales: any signed-in user, for the
 *   orders in `state.orders` (the signed-in customer's); another order's case is 404.
 * - /merchant/after-sales/**: ROLE_MERCHANT with an active `state.user.merchant`
 *   (403 otherwise, re-checked on every request), the requests of its restaurant.
 * - /admin/after-sales/** and /admin/refunds/**: ROLE_ADMIN.
 *
 * Commands follow the backend's state machine: a repeat of a step already taken returns
 * the case unchanged, a status that does not allow the command is 409
 * AFTER_SALES_STATUS_CONFLICT with the backend's message, and a customer's or merchant's
 * answer from the deadline on is 409 AFTER_SALES_DEADLINE_PASSED. The customer commands
 * and an arbitration need an Idempotency-Key: a key that succeeded replays the case it
 * opened or changed as it is now, the same key with another request is 409 CONFLICT, and
 * a failed request stores nothing (its key may be retried).
 */
export function afterSalesRoutes(context: FakeContext): FakeRoute[] {
  const { state, requireRole, requireSession } = context;

  const ownCase = (caseId: number) =>
    state.afterSalesCases.find(
      (item) => item.id === caseId && state.orders.some((order) => order.id === item.order_id),
    );

  /**
   * Runs `command` under the request's Idempotency-Key; `scope` names the command and
   * its target, so a key reused for another one is a different request.
   */
  const idempotent = (
    request: FakeRequest,
    scope: string,
    command: () => AfterSalesCase | Response,
    view: (current: AfterSalesCase) => unknown,
  ): Response => {
    const key = request.headers.get("Idempotency-Key")?.trim();
    if (!key) {
      return badRequest("Idempotency-Key header is required");
    }
    const fingerprint = `${scope}\n${request.body ?? ""}`;
    const stored = state.afterSalesKeys.get(key);
    if (stored) {
      const replayed = state.afterSalesCases.find((item) => item.id === stored.caseId);
      if (stored.fingerprint !== fingerprint || !replayed) {
        return apiError(409, "Idempotency key was already used with a different request", {
          code: "CONFLICT",
        });
      }
      return json(view(replayed));
    }
    const result = command();
    if (result instanceof Response) {
      return result;
    }
    state.afterSalesKeys.set(key, { fingerprint, caseId: result.id });
    return json(view(result));
  };

  const asCustomer = (
    command: Command,
    run: (current: AfterSalesCase, request: FakeRequest) => AfterSalesCase | Response,
  ): FakeHandler =>
    requireSession((request, match) => {
      const caseId = idParam(match);
      return idempotent(
        request,
        `${command}:${caseId}`,
        () => {
          const current = ownCase(caseId);
          return current ? run(current, request) : caseNotFound();
        },
        (current) => customerView(current, { withHistory: true }),
      );
    });

  const eligibleOrders = (): EligibleOrder[] =>
    state.orders.flatMap((order) => {
      if (order.status !== "COMPLETED") {
        return [];
      }
      const requests = state.afterSalesCases.filter(
        (item) => item.order_id === order.id && item.kind === "CUSTOMER_REQUEST",
      );
      const attempt =
        requests.length === 0
          ? 1
          : requests.length === 1 && requests[0]?.close_reason === "WITHDRAWN"
            ? 2
            : null;
      return attempt === null
        ? []
        : [
            {
              order_id: order.id,
              order_no: order.order_no,
              attempt_no: attempt,
              request_deadline: state.afterSalesRequestDeadline,
            },
          ];
    });

  const open = (request: FakeRequest, orderId: number): AfterSalesCase | Response => {
    const order = state.orders.find((item) => item.id === orderId);
    if (!order) {
      return apiError(404, "Order not found", { code: "NOT_FOUND" });
    }
    const body = fieldsOf(requestJson(request), "body");
    const scope = body.optionalString("scope");
    const reason = body.optionalString("reason_code");
    const description = body.optionalString("description");
    if (!scope?.trim() || !reason?.trim() || !description?.trim() || description.length > 1000) {
      return validationFailed();
    }
    if (order.status !== "COMPLETED") {
      return notEligible(
        `Only COMPLETED orders can get after-sales; this order is ${order.status}`,
      );
    }
    if (Date.parse(state.afterSalesRequestDeadline) <= Date.now()) {
      return notEligible(
        `The after-sales window of this order closed at ${state.afterSalesRequestDeadline}`,
      );
    }
    const requested = body.arrayOrEmpty("lines", (value, path) => {
      const line = fieldsOf(value, path);
      return { id: line.number("order_item_id"), quantity: line.number("quantity") };
    });
    if (scope === "WHOLE_ORDER" && requested.length > 0) {
      return badRequest("A WHOLE_ORDER request takes no lines");
    }
    if (scope === "ITEMS" && requested.length === 0) {
      return badRequest("An ITEMS request needs at least one line");
    }
    if (scope !== "WHOLE_ORDER" && scope !== "ITEMS") {
      return badRequest("Scope must be WHOLE_ORDER or ITEMS");
    }
    const reasonCode = AFTER_SALES_REASONS.find((candidate) => candidate === reason);
    if (!reasonCode) {
      return badRequest(`Unsupported reason code: ${reason}`);
    }
    const lines: AfterSalesLine[] = [];
    const wanted =
      scope === "WHOLE_ORDER"
        ? order.items.map((item) => ({ id: item.id, quantity: item.quantity }))
        : requested;
    for (const line of wanted) {
      const item = order.items.find((candidate) => candidate.id === line.id);
      if (!item) {
        return badRequest(`Order item ${line.id} is not part of this order`);
      }
      if (lines.some((existing) => existing.order_item_id === line.id)) {
        return badRequest(`Order item ${line.id} is listed more than once`);
      }
      if (line.quantity < 1) {
        return validationFailed();
      }
      if (line.quantity > item.quantity) {
        return badRequest(
          `Order item ${item.id} was ordered ${item.quantity} times; at most that many can be refunded`,
        );
      }
      lines.push({
        order_item_id: item.id,
        menu_item_name: item.menu_item_name,
        quantity: line.quantity,
        unit_price: item.price,
        amount: (cents(item.price) * line.quantity) / 100,
        currency: item.currency,
      });
    }
    const requestedCents = lines.reduce((sum, line) => sum + cents(line.amount), 0);
    if (requestedCents <= 0) {
      return badRequest("Refund amount must be greater than zero");
    }
    const requests = state.afterSalesCases.filter(
      (item) => item.order_id === order.id && item.kind === "CUSTOMER_REQUEST",
    );
    const latest = requests[0];
    if (latest && latest.status !== "REFUNDED" && latest.status !== "CLOSED") {
      return notEligible("An after-sales case is already open for this order");
    }
    if (latest && (requests.length > 1 || latest.close_reason !== "WITHDRAWN")) {
      return notEligible("This order cannot be submitted for after-sales again");
    }
    const now = localTimestamp();
    const opened = afterSalesCaseFixture({
      id: state.nextAfterSalesId++,
      order_id: order.id,
      order_no: order.order_no,
      restaurant_id: order.restaurant_id,
      attempt_no: requests.length + 1,
      reason_code: reasonCode,
      description,
      requested_amount: requestedCents / 100,
      currency: order.currency,
      lines,
      created_at: now,
      updated_at: now,
    });
    opened.history.push({
      action: "OPEN",
      from_status: null,
      to_status: "PENDING_MERCHANT",
      actor_type: "CUSTOMER",
      amount: opened.requested_amount,
      currency: opened.currency,
      note: null,
      occurred_at: now,
    });
    state.afterSalesCases.unshift(opened);
    return opened;
  };

  const customerRoutes: FakeRoute[] = [
    [
      "GET",
      /^\/after-sales\/cases$/,
      requireSession((request) => {
        const limit = Number(request.query.get("limit") ?? "50");
        return json({
          cases: state.afterSalesCases
            .filter((item) => state.orders.some((order) => order.id === item.order_id))
            .slice(0, limit)
            .map((item) => customerView(item, { withHistory: false })),
          eligible_orders: eligibleOrders().slice(0, limit),
        });
      }),
    ],
    [
      "GET",
      /^\/after-sales\/cases\/(\d+)$/,
      requireSession((_request, match) => {
        const current = ownCase(idParam(match));
        return current ? json(customerView(current, { withHistory: true })) : caseNotFound();
      }),
    ],
    [
      "POST",
      /^\/orders\/(\d+)\/after-sales$/,
      requireSession((request, match) => {
        const orderId = idParam(match);
        return idempotent(
          request,
          `open:${orderId}`,
          () => open(request, orderId),
          (current) => customerView(current, { withHistory: true }),
        );
      }),
    ],
    [
      "POST",
      /^\/after-sales\/cases\/(\d+)\/accept-offer$/,
      asCustomer("acceptOffer", (current) => {
        if (current.resolution === "OFFER_ACCEPTED") {
          return current;
        }
        if (!firesFrom(current.status, "acceptOffer")) {
          return statusConflict(current.status, "acceptOffer");
        }
        const late = deadlinePassed(current);
        if (late) {
          return late;
        }
        current.approved_amount = current.offered_amount;
        current.resolution = "OFFER_ACCEPTED";
        step(state, current, "REFUNDING", "CUSTOMER_ACCEPT_OFFER", "CUSTOMER", {
          amount: current.offered_amount,
        });
        return current;
      }),
    ],
    [
      "POST",
      /^\/after-sales\/cases\/(\d+)\/decline-offer$/,
      asCustomer("declineOffer", (current) => {
        if (current.status === "REJECTED" && current.merchant_decision === "OFFERED") {
          return current;
        }
        if (!firesFrom(current.status, "declineOffer")) {
          return statusConflict(current.status, "declineOffer");
        }
        const late = deadlinePassed(current);
        if (late) {
          return late;
        }
        step(state, current, "REJECTED", "CUSTOMER_DECLINE_OFFER", "CUSTOMER");
        return current;
      }),
    ],
    [
      "POST",
      /^\/after-sales\/cases\/(\d+)\/escalate$/,
      asCustomer("escalate", (current, request) => {
        const note = request.body
          ? fieldsOf(requestJson(request), "body").optionalString("note")
          : null;
        if (note !== null && note.length > 1000) {
          return validationFailed();
        }
        if (current.status === "IN_ARBITRATION") {
          return current;
        }
        if (!firesFrom(current.status, "escalate")) {
          return statusConflict(current.status, "escalate");
        }
        const late = deadlinePassed(current);
        if (late) {
          return late;
        }
        step(state, current, "IN_ARBITRATION", "CUSTOMER_ESCALATE", "CUSTOMER", { note });
        return current;
      }),
    ],
    [
      "POST",
      /^\/after-sales\/cases\/(\d+)\/withdraw$/,
      asCustomer("withdraw", (current) => {
        if (current.status === "CLOSED" && current.close_reason === "WITHDRAWN") {
          return current;
        }
        if (!firesFrom(current.status, "withdraw")) {
          return statusConflict(current.status, "withdraw");
        }
        current.close_reason = "WITHDRAWN";
        step(state, current, "CLOSED", "CUSTOMER_WITHDRAW", "CUSTOMER");
        return current;
      }),
    ],
  ];

  const asActiveMerchant = (handler: FakeHandler): FakeHandler =>
    requireRole(ROLES.merchant, (request, match) =>
      state.user?.merchant?.active === true ? handler(request, match) : forbidden(),
    );

  const merchantCase = (caseId: number) =>
    state.afterSalesCases.find(
      (item) =>
        item.id === caseId &&
        item.kind === "CUSTOMER_REQUEST" &&
        item.restaurant_id === state.user?.merchant?.restaurant_id,
    );

  const asMerchantCommand = (
    run: (current: AfterSalesCase, request: FakeRequest) => Response | null,
  ): FakeHandler =>
    asActiveMerchant((request, match) => {
      const current = merchantCase(idParam(match));
      if (!current) {
        return caseNotFound();
      }
      return run(current, request) ?? json(merchantView(current));
    });

  /** A merchant answer: unchanged when already given, else the status and deadline checks. */
  const answer = (
    current: AfterSalesCase,
    command: "approve" | "reject" | "offer",
    alreadyDone: boolean,
    apply: () => Response | null,
  ): Response | null => {
    if (alreadyDone) {
      return null;
    }
    if (!firesFrom(current.status, command)) {
      return statusConflict(current.status, command);
    }
    return deadlinePassed(current) ?? apply();
  };

  const merchantRoutes: FakeRoute[] = [
    [
      "GET",
      /^\/merchant\/after-sales$/,
      asActiveMerchant((request) => {
        const statusFilter = request.query.get("status")?.trim().toUpperCase() ?? "";
        const status = statusFilter ? parseAfterSalesStatus(statusFilter) : null;
        if (statusFilter && !status) {
          return badRequest(`Unsupported after-sales status: ${statusFilter}`);
        }
        const limit = Number(request.query.get("limit") ?? "50");
        return json(
          state.afterSalesCases
            .filter(
              (item) =>
                item.kind === "CUSTOMER_REQUEST" &&
                item.restaurant_id === state.user?.merchant?.restaurant_id &&
                (!status || item.status === status),
            )
            .slice(0, limit)
            .map(merchantView),
        );
      }),
    ],
    [
      "POST",
      /^\/merchant\/after-sales\/(\d+)\/approve$/,
      asMerchantCommand((current) =>
        answer(current, "approve", current.merchant_decision === "APPROVED", () => {
          current.merchant_decision = "APPROVED";
          current.approved_amount = current.requested_amount;
          current.resolution = "MERCHANT_APPROVED";
          step(state, current, "REFUNDING", "MERCHANT_APPROVE", "MERCHANT", {
            amount: current.requested_amount,
          });
          return null;
        }),
      ),
    ],
    [
      "POST",
      /^\/merchant\/after-sales\/(\d+)\/reject$/,
      asMerchantCommand((current, request) => {
        const reason = fieldsOf(requestJson(request), "body").optionalString("reason")?.trim();
        if (!reason || reason.length > 500) {
          return validationFailed();
        }
        return answer(current, "reject", current.merchant_decision === "REJECTED", () => {
          current.merchant_decision = "REJECTED";
          current.merchant_note = reason;
          step(state, current, "REJECTED", "MERCHANT_REJECT", "MERCHANT", { note: reason });
          return null;
        });
      }),
    ],
    [
      "POST",
      /^\/merchant\/after-sales\/(\d+)\/offer$/,
      asMerchantCommand((current, request) => {
        const body = fieldsOf(requestJson(request), "body");
        const amount = body.optionalNumber("amount");
        const note = body.optionalString("note");
        if (amount === null || (note !== null && note.length > 500)) {
          return validationFailed();
        }
        const decimals = tooManyDecimals(current, amount);
        if (decimals) {
          return decimals;
        }
        const repeated =
          current.merchant_decision === "OFFERED" &&
          current.offered_amount !== null &&
          cents(current.offered_amount) === cents(amount);
        return answer(current, "offer", repeated, () => {
          if (amount <= 0 || cents(amount) >= cents(current.requested_amount)) {
            return amountOutOfRange(current);
          }
          current.merchant_decision = "OFFERED";
          current.offered_amount = amount;
          current.merchant_note = note;
          step(state, current, "COUNTER_OFFERED", "MERCHANT_OFFER", "MERCHANT", {
            amount,
            note,
          });
          return null;
        });
      }),
    ],
  ];

  const adminRoutes: FakeRoute[] = [
    [
      "GET",
      /^\/admin\/after-sales$/,
      requireRole(ROLES.admin, (request) => {
        const statusParam = request.query.get("status") ?? "IN_ARBITRATION";
        const status = parseAfterSalesStatus(statusParam.trim().toUpperCase());
        if (!status) {
          return badRequest(`Unsupported after-sales status: ${statusParam}`);
        }
        const limit = Number(request.query.get("limit") ?? "50");
        const cases = state.afterSalesCases.filter((item) => item.status === status);
        if (AFTER_SALES_OPEN_STATUSES.has(status)) {
          // The queue: the nearest deadline first.
          cases.sort((a, b) => Date.parse(a.deadline_at ?? "") - Date.parse(b.deadline_at ?? ""));
        }
        return json(cases.slice(0, limit).map((item) => ({ ...item, history: undefined })));
      }),
    ],
    [
      "GET",
      /^\/admin\/after-sales\/(\d+)$/,
      requireRole(ROLES.admin, (_request, match) => {
        const current = state.afterSalesCases.find((item) => item.id === idParam(match));
        return current ? json(current) : caseNotFound();
      }),
    ],
    [
      "POST",
      /^\/admin\/after-sales\/(\d+)\/arbitrate$/,
      requireRole(ROLES.admin, (request, match) => {
        const caseId = idParam(match);
        return idempotent(
          request,
          `arbitrate:${caseId}`,
          () => {
            const current = state.afterSalesCases.find((item) => item.id === caseId);
            if (!current) {
              return caseNotFound();
            }
            const body = fieldsOf(requestJson(request), "body");
            const decision = body.optionalString("decision");
            const amount = body.optionalNumber("amount");
            const note = body.optionalString("note")?.trim();
            if (!decision?.trim() || !note || note.length > 1000) {
              return validationFailed();
            }
            if (decision !== "FULL" && decision !== "PARTIAL" && decision !== "REJECT") {
              return badRequest("Decision must be FULL, PARTIAL or REJECT");
            }
            if (decision === "PARTIAL" && amount === null) {
              return badRequest("A PARTIAL decision needs an amount");
            }
            if (decision !== "PARTIAL" && amount !== null) {
              return badRequest(`A ${decision} decision takes no amount`);
            }
            const decimals = amount === null ? null : tooManyDecimals(current, amount);
            if (decimals) {
              return decimals;
            }
            if (!firesFrom(current.status, "arbitrate")) {
              return statusConflict(current.status, "arbitrate");
            }
            current.admin_note = note;
            if (decision === "REJECT") {
              current.close_reason = "ARBITRATION_REJECTED";
              step(state, current, "CLOSED", "ADMIN_REJECT", "ADMIN", { note });
              return current;
            }
            const approved = amount ?? current.requested_amount;
            if (approved <= 0 || cents(approved) > cents(current.requested_amount)) {
              return amountOutOfRange(current);
            }
            if (decision === "PARTIAL" && cents(approved) === cents(current.requested_amount)) {
              return amountOutOfRange(current);
            }
            current.approved_amount = approved;
            current.resolution = decision === "FULL" ? "ARBITRATION_FULL" : "ARBITRATION_PARTIAL";
            step(
              state,
              current,
              "REFUNDING",
              decision === "FULL" ? "ADMIN_APPROVE_FULL" : "ADMIN_APPROVE_PARTIAL",
              "ADMIN",
              { amount: approved, note },
            );
            return current;
          },
          (current) => current,
        );
      }),
    ],
    [
      "GET",
      /^\/admin\/refunds$/,
      requireRole(ROLES.admin, (request) => {
        const statusParam = request.query.get("status") ?? "FAILED";
        const status = REFUND_STATUSES.find(
          (candidate) => candidate === statusParam.trim().toUpperCase(),
        );
        if (!status) {
          return badRequest(`Unsupported refund status: ${statusParam}`);
        }
        const limit = Number(request.query.get("limit") ?? "50");
        return json(
          state.afterSalesCases
            .flatMap((item) => (item.refund?.status === status ? [item.refund] : []))
            .slice(0, limit),
        );
      }),
    ],
    [
      "POST",
      /^\/admin\/refunds\/(\d+)\/retry$/,
      requireRole(ROLES.admin, (_request, match) => {
        const refund = state.afterSalesCases.find(
          (item) => item.refund?.id === idParam(match),
        )?.refund;
        if (!refund) {
          return apiError(404, "Refund not found", { code: "NOT_FOUND" });
        }
        if (refund.status === "PENDING") {
          return json(refund);
        }
        if (refund.status !== "FAILED") {
          return apiError(409, `Refund is ${refund.status}; only FAILED refunds can be retried`, {
            code: "REFUND_STATUS_CONFLICT",
          });
        }
        refund.status = "PENDING";
        refund.next_attempt_at = localTimestamp();
        return json({ ...refund });
      }),
    ],
  ];

  return [...customerRoutes, ...merchantRoutes, ...adminRoutes];
}
