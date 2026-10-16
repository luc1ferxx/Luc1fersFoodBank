import { screen, waitFor, within } from "@testing-library/react";
import type { UserEvent } from "@testing-library/user-event";
import { describe, expect, it } from "vitest";
import {
  apiError,
  demoUser,
  installFakeBackend,
  merchantAccountFixture,
  requestJson,
  riderAccountFixture,
} from "../test/fakeBackend";
import { renderApp } from "../test/helpers";

const adminBackend = () => {
  const backend = installFakeBackend({ user: demoUser });
  backend.state.merchantAccounts = [
    merchantAccountFixture({ merchant_id: 3, email: "merchant.burgerbarn@laifood.com" }),
  ];
  backend.state.riderAccounts = [
    riderAccountFixture({ rider_id: 1, name: "Demo Rider Alice", customer_id: null }),
  ];
  return backend;
};

const openAdmin = async (user: UserEvent, section: "Merchants" | "Riders" = "Merchants") => {
  await screen.findByText("Demo is signed in");
  await user.click(screen.getByRole("button", { name: "Admin console" }));
  await user.click(screen.getByRole("button", { name: section }));
  return screen.findByRole("region", { name: `${section.slice(0, -1)} accounts` });
};

const rowFor = (section: HTMLElement, text: string) => {
  const row = within(section)
    .getAllByRole("row")
    .find((candidate) => candidate.textContent?.includes(text));
  if (!row) {
    throw new Error(`No row with ${text}`);
  }
  return row;
};

const fillLogin = async (form: HTMLElement, user: UserEvent, email: string) => {
  await user.type(within(form).getByLabelText("Email"), email);
  await user.type(within(form).getByLabelText("Password"), "secret123");
  await user.type(within(form).getByLabelText("First name"), "Nina");
  await user.type(within(form).getByLabelText("Last name"), "Noodle");
};

describe("admin console: merchant accounts", () => {
  it("lists merchants and creates one for a restaurant picked from the catalog", async () => {
    const backend = adminBackend();
    const user = renderApp();

    const section = await openAdmin(user);
    await waitFor(() =>
      expect(rowFor(section, "merchant.burgerbarn@laifood.com")).toHaveTextContent("Burger Barn"),
    );

    const form = within(section).getByRole("form", { name: "Create a merchant account" });
    const picker = within(form).getByLabelText("Restaurant");
    await waitFor(() => expect(picker).toBeEnabled());
    expect(
      within(picker)
        .getAllByRole("option")
        .map((option) => option.textContent),
    ).toEqual(["Choose a restaurant", "#1 Burger Barn", "#2 Noodle Nook", "#3 Taco Town"]);

    await fillLogin(form, user, "nina@noodlenook.com");
    await user.selectOptions(picker, "#2 Noodle Nook");
    await user.click(within(form).getByRole("button", { name: "Create merchant" }));

    expect(await within(section).findByText("Created merchant nina@noodlenook.com.")).toBeVisible();
    expect(requestJson(backend.callsTo("POST", "/admin/merchants")[0])).toEqual({
      email: "nina@noodlenook.com",
      password: "secret123",
      first_name: "Nina",
      last_name: "Noodle",
      restaurant_id: 2,
    });
    expect(rowFor(section, "nina@noodlenook.com")).toHaveTextContent("Noodle Nook");
    expect(within(form).getByLabelText("Email")).toHaveValue("");
  });

  it("explains an email that already has an account and keeps the form", async () => {
    const backend = adminBackend();
    const user = renderApp();

    const section = await openAdmin(user);
    const form = within(section).getByRole("form", { name: "Create a merchant account" });
    await waitFor(() => expect(within(form).getByLabelText("Restaurant")).toBeEnabled());
    await fillLogin(form, user, "demo@laifood.com");
    await user.selectOptions(within(form).getByLabelText("Restaurant"), "#1 Burger Barn");
    const listReads = backend.callsTo("GET", "/admin/merchants").length;
    await user.click(within(form).getByRole("button", { name: "Create merchant" }));

    expect(await within(form).findByRole("alert")).toHaveTextContent(
      "demo@laifood.com already has an account. Use another email.",
    );
    expect(within(form).getByLabelText("Email")).toHaveValue("demo@laifood.com");
    expect(backend.state.merchantAccounts).toHaveLength(1);
    await waitFor(() =>
      expect(backend.callsTo("GET", "/admin/merchants").length).toBe(listReads + 1),
    );
  });

  it("reports an unknown restaurant and missing fields", async () => {
    const backend = adminBackend();
    backend.on("POST", "/admin/merchants", () =>
      apiError(404, "Restaurant not found", { code: "NOT_FOUND" }),
    );
    const user = renderApp();

    const section = await openAdmin(user);
    const form = within(section).getByRole("form", { name: "Create a merchant account" });
    await user.click(within(form).getByRole("button", { name: "Create merchant" }));
    expect(within(form).getByRole("alert")).toHaveTextContent(
      "Fill in every field and choose a restaurant.",
    );
    expect(backend.callsTo("POST", "/admin/merchants")).toHaveLength(0);

    await waitFor(() => expect(within(form).getByLabelText("Restaurant")).toBeEnabled());
    await fillLogin(form, user, "nina@noodlenook.com");
    await user.selectOptions(within(form).getByLabelText("Restaurant"), "#3 Taco Town");
    await user.click(within(form).getByRole("button", { name: "Create merchant" }));

    expect(await within(form).findByRole("alert")).toHaveTextContent("404: Restaurant not found");
  });

  it("deactivates and reactivates a merchant", async () => {
    const backend = adminBackend();
    const user = renderApp();

    const section = await openAdmin(user);
    const deactivate = await within(section).findByRole("button", {
      name: "Deactivate merchant.burgerbarn@laifood.com",
    });
    await user.click(deactivate);

    const activate = await within(section).findByRole("button", {
      name: "Activate merchant.burgerbarn@laifood.com",
    });
    expect(rowFor(section, "merchant.burgerbarn@laifood.com")).toHaveTextContent("Inactive");
    expect(
      within(section).getByText("Deactivated merchant merchant.burgerbarn@laifood.com."),
    ).toBeInTheDocument();

    await user.click(activate);
    await waitFor(() =>
      expect(rowFor(section, "merchant.burgerbarn@laifood.com")).toHaveTextContent("Active"),
    );
    expect(backend.callsTo("PATCH", "/admin/merchants/3").map(requestJson)).toEqual([
      { active: false },
      { active: true },
    ]);
  });

  it("shows a failed deactivation on its row", async () => {
    const backend = adminBackend();
    backend.on("PATCH", "/admin/merchants/3", () =>
      apiError(503, "Accounts unavailable", { traceId: "trace-95" }),
    );
    const user = renderApp();

    const section = await openAdmin(user);
    await user.click(
      await within(section).findByRole("button", {
        name: "Deactivate merchant.burgerbarn@laifood.com",
      }),
    );

    const row = rowFor(section, "merchant.burgerbarn@laifood.com");
    expect(await within(row).findByRole("alert")).toHaveTextContent(
      "503: Accounts unavailable Trace trace-95.",
    );
    expect(row).toHaveTextContent("Active");
  });
});

describe("admin console: rider accounts", () => {
  it("lists riders, including seeded ones without a login, and creates a rider", async () => {
    const backend = adminBackend();
    const user = renderApp();

    const section = await openAdmin(user, "Riders");
    await waitFor(() => expect(rowFor(section, "Demo Rider Alice")).toHaveTextContent("No login"));

    const form = within(section).getByRole("form", { name: "Create a rider account" });
    await fillLogin(form, user, "nina.rider@laifood.com");
    await user.type(within(form).getByLabelText("Phone"), "(408) 555-0199");
    await user.click(within(form).getByRole("button", { name: "Create rider" }));

    expect(
      await within(section).findByText("Created rider nina.rider@laifood.com."),
    ).toBeInTheDocument();
    expect(requestJson(backend.callsTo("POST", "/admin/riders")[0])).toEqual({
      email: "nina.rider@laifood.com",
      password: "secret123",
      first_name: "Nina",
      last_name: "Noodle",
      phone: "(408) 555-0199",
    });
    expect(rowFor(section, "nina.rider@laifood.com")).toHaveTextContent("Nina Noodle");
  });

  it("explains a rider email that already has an account", async () => {
    const backend = adminBackend();
    const user = renderApp();

    const section = await openAdmin(user, "Riders");
    const form = within(section).getByRole("form", { name: "Create a rider account" });
    await fillLogin(form, user, "rider.alice@laifood.com");
    await user.type(within(form).getByLabelText("Phone"), "(408) 555-0199");
    await user.click(within(form).getByRole("button", { name: "Create rider" }));

    expect(await within(form).findByRole("alert")).toHaveTextContent(
      "rider.alice@laifood.com already has an account. Use another email.",
    );
    expect(backend.state.riderAccounts).toHaveLength(1);
  });

  it("deactivates a rider", async () => {
    const backend = adminBackend();
    const user = renderApp();

    const section = await openAdmin(user, "Riders");
    await user.click(
      await within(section).findByRole("button", { name: "Deactivate Demo Rider Alice" }),
    );

    expect(
      await within(section).findByRole("button", { name: "Activate Demo Rider Alice" }),
    ).toBeInTheDocument();
    expect(requestJson(backend.callsTo("PATCH", "/admin/riders/1")[0])).toEqual({ active: false });
  });

  it("shows a load failure of the rider list", async () => {
    const backend = adminBackend();
    backend.on("GET", "/admin/riders", () => apiError(403, "Access denied", { code: "FORBIDDEN" }));
    const user = renderApp();

    const section = await openAdmin(user, "Riders");
    expect(await within(section).findByRole("alert")).toHaveTextContent("403: Access denied");
    expect(within(section).queryByText("Loading riders...")).toBeNull();
  });
});
