import type { ActorType, DeliveryStatus, OrderStatus } from "../api/dto";

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

/**
 * "Order #<order number>": the public number customers see, with the internal id as a
 * fallback for a backend that does not send one. Staff tools that need the id add it.
 */
export const orderLabel = (order: { id: number; order_no: string | null }): string =>
  `Order #${order.order_no ?? order.id}`;
