import { screen } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";
import { demoUser, installFakeBackend, merchantUser } from "./test/fakeBackend";
import { renderApp } from "./test/helpers";

/**
 * Counts how often each console's module is loaded: Vitest runs a module mock's factory
 * the first time the module is imported, which for a React.lazy console is the dynamic
 * import of its chunk, not the import of App. The admin console's load also waits until
 * the test releases it, to look at the page while a chunk is still loading.
 */
const chunks = vi.hoisted(() => {
  const loads = { merchant: 0, rider: 0, admin: 0 };
  let releaseAdmin: () => void = () => {};
  const adminChunk = new Promise<void>((resolve) => {
    releaseAdmin = resolve;
  });
  return { loads, adminChunk, releaseAdmin: () => releaseAdmin() };
});

vi.mock("./pages/MerchantConsole", (importOriginal) => {
  chunks.loads.merchant += 1;
  return importOriginal();
});
vi.mock("./pages/RiderConsole", (importOriginal) => {
  chunks.loads.rider += 1;
  return importOriginal();
});
vi.mock("./pages/AdminConsole", async (importOriginal) => {
  chunks.loads.admin += 1;
  await chunks.adminChunk;
  return importOriginal();
});

describe("lazily loaded consoles", () => {
  it("loads a console's chunk only when its workspace is first shown", async () => {
    installFakeBackend();
    const user = renderApp();

    // A guest gets the ordering page without any console's chunk.
    await user.type(await screen.findByLabelText("Email"), merchantUser.email);
    expect(chunks.loads).toEqual({ merchant: 0, rider: 0, admin: 0 });

    await user.type(screen.getByLabelText("Password"), "merchant123");
    await user.click(screen.getByRole("button", { name: "Sign in" }));
    expect(await screen.findByRole("region", { name: "Merchant console" })).toBeInTheDocument();
    expect(chunks.loads).toEqual({ merchant: 1, rider: 0, admin: 0 });
  });

  it("shows a loading row while a console's chunk loads and keeps the other workspace", async () => {
    installFakeBackend({ user: demoUser });
    const user = renderApp();

    await screen.findByText("Demo is signed in");
    expect(await screen.findByRole("button", { name: "Cart (0)" })).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "Admin console" }));

    expect(await screen.findByText("Loading admin console...")).toBeInTheDocument();
    expect(chunks.loads.admin).toBe(1);
    // Only the admin console's own boundary suspends: the ordering page is hidden by the
    // switch as before, not unmounted (its forms and retry keys survive).
    expect(screen.queryByRole("region", { name: "Restaurant and menu browsing" })).toBeNull();
    expect(
      screen.getByRole("region", { name: "Restaurant and menu browsing", hidden: true }),
    ).toBeInTheDocument();

    chunks.releaseAdmin();
    expect(await screen.findByRole("region", { name: "Admin console" })).toBeInTheDocument();
    expect(screen.queryByText("Loading admin console...")).toBeNull();
    expect(screen.getByRole("button", { name: "Cart (0)", hidden: true })).toBeInTheDocument();
  });
});
