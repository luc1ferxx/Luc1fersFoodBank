import { describe, expect, it } from "vitest";
import { type CurrentUser, ROLES } from "../api/dto";
import { availableWorkspaces, defaultWorkspace } from "./workspaces";

const userWith = (roles: string[]): CurrentUser => ({
  id: 1,
  email: "someone@example.com",
  first_name: null,
  last_name: null,
  roles,
  merchant: null,
  rider: null,
});

describe("availableWorkspaces", () => {
  it.each([
    ["a guest", null, ["order"], "order"],
    ["a customer", userWith([ROLES.user]), ["order"], "order"],
    [
      "a customer who is an admin",
      userWith([ROLES.user, ROLES.admin]),
      ["order", "admin"],
      "order",
    ],
    ["a merchant login", userWith([ROLES.merchant]), ["merchant"], "merchant"],
    ["a rider login", userWith([ROLES.rider]), ["rider"], "rider"],
    ["an admin without ROLE_USER", userWith([ROLES.admin]), ["admin"], "admin"],
    [
      "a customer who also works as merchant and rider",
      userWith([ROLES.rider, ROLES.user, ROLES.merchant]),
      ["order", "merchant", "rider"],
      "merchant",
    ],
    ["a login without known roles", userWith([]), ["order"], "order"],
    ["a login with an unknown role", userWith(["ROLE_AUDITOR"]), ["order"], "order"],
  ] as const)("gives %s the right workspaces", (_who, user, expected, expectedDefault) => {
    const workspaces = availableWorkspaces(user);
    expect(workspaces).toEqual(expected);
    expect(defaultWorkspace(workspaces)).toBe(expectedDefault);
  });
});
