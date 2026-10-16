import { type CurrentUser, ROLES } from "../api/dto";

/** The top-level views; which ones a user gets follows their roles from GET /me. */
export type Workspace = "order" | "merchant" | "rider" | "admin";

export const WORKSPACE_LABELS: Readonly<Record<Workspace, string>> = {
  order: "Order food",
  merchant: "Merchant console",
  rider: "Rider console",
  admin: "Admin console",
};

const DISPLAY_ORDER: readonly Workspace[] = ["order", "merchant", "rider", "admin"];
// A merchant or rider signs in to work; an admin who also orders lands on ordering.
const DEFAULT_ORDER: readonly Workspace[] = ["merchant", "rider", "order", "admin"];

/**
 * Guests and customers (ROLE_USER) order food; ROLE_MERCHANT, ROLE_RIDER, and
 * ROLE_ADMIN add their consoles. A login with none of the known roles (or a backend
 * whose /me has no roles yet) falls back to ordering.
 */
export function availableWorkspaces(user: CurrentUser | null): Workspace[] {
  if (!user) {
    return ["order"];
  }
  const roles = new Set(user.roles);
  const consoles: Readonly<Record<Exclude<Workspace, "order">, boolean>> = {
    merchant: roles.has(ROLES.merchant),
    rider: roles.has(ROLES.rider),
    admin: roles.has(ROLES.admin),
  };
  const orders =
    roles.has(ROLES.user) || (!consoles.merchant && !consoles.rider && !consoles.admin);

  return DISPLAY_ORDER.filter((workspace) =>
    workspace === "order" ? orders : consoles[workspace],
  );
}

export const defaultWorkspace = (available: readonly Workspace[]): Workspace =>
  DEFAULT_ORDER.find((workspace) => available.includes(workspace)) ?? "order";
