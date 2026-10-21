import type {
  ActorType,
  AfterSalesAction,
  AfterSalesCloseReason,
  AfterSalesReason,
  AfterSalesResolution,
  AfterSalesStatus,
  DeliveryStatus,
  OrderStatus,
  RefundStatus,
} from "../api/dto";

export const ACTOR_LABELS: Readonly<Record<ActorType, string>> = {
  CUSTOMER: "Customer",
  ADMIN: "Admin",
  MERCHANT: "Restaurant",
  RIDER: "Rider",
  SYSTEM: "System",
};

export const ORDER_STATUS_LABELS: Readonly<Record<OrderStatus, string>> = {
  PLACED: "Placed",
  PAID: "New (paid)",
  ACCEPTED: "Accepted",
  PREPARING: "Preparing",
  COMPLETED: "Completed",
  CANCELLED: "Cancelled",
};

export const DELIVERY_STATUS_LABELS: Readonly<Record<DeliveryStatus, string>> = {
  ASSIGNED: "Assigned",
  PICKED_UP: "Picked up",
  DELIVERED: "Delivered",
  CANCELLED: "Cancelled",
};

export const AFTER_SALES_STATUS_LABELS: Readonly<Record<AfterSalesStatus, string>> = {
  PENDING_MERCHANT: "Waiting for the restaurant",
  COUNTER_OFFERED: "Partial refund offered",
  REJECTED: "Not agreed",
  IN_ARBITRATION: "Platform reviewing",
  REFUNDING: "Refunding",
  REFUNDED: "Refunded",
  CLOSED: "Closed",
};

export const AFTER_SALES_REASON_LABELS: Readonly<Record<AfterSalesReason, string>> = {
  MISSING_ITEM: "Missing item",
  WRONG_ITEM: "Wrong item",
  QUALITY_ISSUE: "Quality issue",
  DAMAGED_PACKAGING: "Damaged packaging",
  FOOD_SAFETY: "Food safety",
  LATE_DELIVERY: "Late delivery",
  OTHER: "Other",
};

export const AFTER_SALES_ACTION_LABELS: Readonly<Record<AfterSalesAction, string>> = {
  OPEN: "Refund requested",
  ORDER_CANCELLED: "Automatic refund of the cancelled order",
  MERCHANT_APPROVE: "Restaurant approved the refund",
  MERCHANT_REJECT: "Restaurant rejected the request",
  MERCHANT_OFFER: "Restaurant offered a partial refund",
  MERCHANT_TIMEOUT: "Restaurant did not answer in time: refund approved",
  CUSTOMER_ACCEPT_OFFER: "Offer accepted",
  CUSTOMER_DECLINE_OFFER: "Offer declined",
  OFFER_TIMEOUT: "Offer not answered in time: accepted",
  CUSTOMER_ESCALATE: "Platform asked to step in",
  CUSTOMER_TIMEOUT: "Not escalated in time: closed",
  CUSTOMER_WITHDRAW: "Request withdrawn",
  ADMIN_APPROVE_FULL: "Platform granted a full refund",
  ADMIN_APPROVE_PARTIAL: "Platform granted a partial refund",
  ADMIN_REJECT: "Platform rejected the request",
  ARBITRATION_TIMEOUT: "Platform did not decide in time: full refund",
  REFUND_SUCCEEDED: "Refund completed",
};

export const AFTER_SALES_RESOLUTION_LABELS: Readonly<Record<AfterSalesResolution, string>> = {
  MERCHANT_APPROVED: "Approved by the restaurant",
  MERCHANT_TIMEOUT: "Approved: the restaurant did not answer in time",
  OFFER_ACCEPTED: "Partial refund accepted",
  OFFER_TIMEOUT: "Partial refund: the offer was not answered in time",
  ARBITRATION_FULL: "Full refund granted by the platform",
  ARBITRATION_PARTIAL: "Partial refund granted by the platform",
  ARBITRATION_TIMEOUT: "Full refund: the platform did not decide in time",
  ORDER_CANCELLED: "Automatic refund of the cancelled order",
};

export const AFTER_SALES_CLOSE_REASON_LABELS: Readonly<Record<AfterSalesCloseReason, string>> = {
  WITHDRAWN: "Withdrawn",
  CUSTOMER_TIMEOUT: "Not escalated in time",
  ARBITRATION_REJECTED: "Rejected by the platform",
};

export const REFUND_STATUS_LABELS: Readonly<Record<RefundStatus, string>> = {
  PENDING: "Pending",
  PROCESSING: "Processing",
  SUCCEEDED: "Refunded",
  FAILED: "Failed",
};

/**
 * "Order #<order number>": the public number customers see, with the internal id as a
 * fallback for a backend that does not send one. Staff tools that need the id add it.
 */
export const orderLabel = (order: { id: number; order_no: string | null }): string =>
  `Order #${order.order_no ?? order.id}`;
