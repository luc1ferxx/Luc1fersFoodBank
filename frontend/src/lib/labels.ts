import type { ActorType } from "../api/dto";

export const ACTOR_LABELS: Readonly<Record<ActorType, string>> = {
  CUSTOMER: "Customer",
  ADMIN: "Admin",
  MERCHANT: "Restaurant",
  RIDER: "Rider",
  SYSTEM: "System",
};
