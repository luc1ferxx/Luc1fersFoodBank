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
