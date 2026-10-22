import { screen, waitFor, within } from "@testing-library/react";
import type { UserEvent } from "@testing-library/user-event";
import { describe, expect, it } from "vitest";
import { currencyCode } from "../api/currency";
import type { AfterSalesCase } from "../api/dto";
import {
  AFTER_SALES_TIMEOUTS,
  USD,
  afterSalesCaseFixture,
  apiError,
  backendTimestamp,
  deferred,
  demoUser,
  idempotencyKeys,
  installFakeBackend,
  json,
  merchantAccountFixture,
  refundFixture,
  requestJson,
  riderAccountFixture,
} from "../test/fakeBackend";
import { flushPromises, renderApp } from "../test/helpers";

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
  // The console is a lazy chunk: its tabs appear once it has loaded.
  await user.click(await screen.findByRole("button", { name: section }));
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

const twoWhoppers = [
  {
    order_item_id: 5,
    menu_item_name: "Whopper",
    quantity: 2,
    unit_price: 6.39,
    amount: 12.78,
    currency: USD,
  },
];

/** A case the customer escalated after the restaurant rejected it. */
const escalatedCase = (id: number, overrides: Partial<AfterSalesCase> = {}): AfterSalesCase =>
  afterSalesCaseFixture({
    id,
    order_id: id - 800,
    status: "IN_ARBITRATION",
    requested_amount: 12.78,
    lines: twoWhoppers,
    merchant_decision: "REJECTED",
    merchant_note: "Looked fine when it left",
    deadline_at: backendTimestamp(AFTER_SALES_TIMEOUTS.arbitration),
    history: [
      {
        action: "OPEN",
        from_status: null,
        to_status: "PENDING_MERCHANT",
        actor_type: "CUSTOMER",
        amount: 12.78,
        currency: USD,
        note: null,
        occurred_at: "2026-10-20T12:00:00",
      },
      {
        action: "MERCHANT_REJECT",
        from_status: "PENDING_MERCHANT",
        to_status: "REJECTED",
        actor_type: "MERCHANT",
        amount: null,
        currency: USD,
        note: "Looked fine when it left",
        occurred_at: "2026-10-20T13:00:00",
      },
      {
        action: "CUSTOMER_ESCALATE",
        from_status: "REJECTED",
        to_status: "IN_ARBITRATION",
        actor_type: "CUSTOMER",
        amount: null,
        currency: USD,
        note: "Both burgers were cold",
        occurred_at: "2026-10-20T14:00:00",
      },
    ],
    ...overrides,
  });

/** A case whose refund the executor gave up on. */
const failedRefundCase = (id: number, refundId: number): AfterSalesCase =>
  afterSalesCaseFixture({
    id,
    order_id: id - 800,
    status: "REFUNDING",
    approved_amount: 6.39,
    resolution: "MERCHANT_APPROVED",
    merchant_decision: "APPROVED",
    deadline_at: null,
    refund: refundFixture({
      id: refundId,
      case_id: id,
      order_id: id - 800,
      status: "FAILED",
      attempts: 5,
      last_error: "Gateway timeout",
    }),
  });

const openAfterSales = async (user: UserEvent) => {
  await screen.findByText("Demo is signed in");
  await user.click(screen.getByRole("button", { name: "Admin console" }));
  await user.click(await screen.findByRole("button", { name: "After-sales" }));
  return screen.findByRole("region", { name: "After-sales" });
};

const viewCase = async (section: HTMLElement, user: UserEvent, id: number) => {
  await user.click(await within(section).findByRole("button", { name: `View case #${id}` }));
  return within(section).findByRole("article", { name: `Case #${id} details` });
};

const decide = async (
  details: HTMLElement,
  user: UserEvent,
  decision: "Full refund" | "Partial refund" | "Reject",
  note: string,
  amount?: string,
) => {
  await user.click(within(details).getByRole("radio", { name: decision }));
  if (amount !== undefined) {
    const field = within(details).getByLabelText(/Refund amount/);
    await user.clear(field);
    await user.type(field, amount);
  }
  const noteField = within(details).getByLabelText("Note for the customer and the restaurant");
  await user.clear(noteField);
  if (note) {
    await user.type(noteField, note);
  }
  await user.click(within(details).getByRole("button", { name: "Submit decision" }));
};

describe("admin console: after-sales", () => {
  it("lists the arbitration queue and shows a case's history and its refund's last error", async () => {
    const backend = installFakeBackend({ user: demoUser });
    backend.state.afterSalesCases = [escalatedCase(900), failedRefundCase(905, 906)];
    const user = renderApp();

    const section = await openAfterSales(user);
    const queue = await within(section).findByRole("list", { name: "Refund requests" });
    expect(
      within(queue)
        .getAllByRole("article")
        .map((card) => card.getAttribute("aria-label")),
    ).toEqual(["Case #900"]);
    const card = within(queue).getByRole("article", { name: "Case #900" });
    expect(card).toHaveTextContent("Order #7391823564100000100 (id 100)");
    expect(card).toHaveTextContent("$12.78");
    expect(card).toHaveTextContent("Platform reviewing");
    expect(card).toHaveTextContent(/Due in \d+d \d+h/);
    expect(backend.callsTo("GET", "/admin/after-sales")[0]?.query.get("status")).toBe(
      "IN_ARBITRATION",
    );

    const details = await viewCase(section, user, 900);
    const history = within(details).getByRole("list", { name: "History of case #900" });
    expect(
      within(history)
        .getAllByRole("listitem")
        .map((entry) => entry.textContent),
    ).toEqual([
      expect.stringContaining("Refund requested"),
      expect.stringContaining("Restaurant rejected the request"),
      expect.stringContaining("Platform asked to step in"),
    ]);
    expect(history).toHaveTextContent("Restaurant · Looked fine when it left");
    expect(history).toHaveTextContent("Customer · Both burgers were cold");
    expect(details).toHaveTextContent("2 x Whopper · $12.78");
    expect(details).toHaveTextContent("REJECTED: Looked fine when it left");
    expect(within(details).getByRole("form", { name: "Arbitrate case #900" })).toBeVisible();

    // Another status, and a case whose refund failed: staff see its last error.
    await user.selectOptions(within(section).getByLabelText("Status"), "Refunding");
    await waitFor(() =>
      expect(backend.callsTo("GET", "/admin/after-sales").at(-1)?.query.get("status")).toBe(
        "REFUNDING",
      ),
    );
    const refunding = await viewCase(section, user, 905);
    expect(refunding).toHaveTextContent("Refund #906 · Failed");
    expect(refunding).toHaveTextContent("5 attempt(s)");
    expect(refunding).toHaveTextContent("Last error: Gateway timeout");
    expect(within(refunding).queryByRole("form")).toBeNull();

    const failed = within(section).getByRole("region", { name: "Failed refunds" });
    expect(await within(failed).findByRole("row", { name: /Refund #906/ })).toHaveTextContent(
      "Gateway timeout",
    );
  });

  it("arbitrates in full, in part and with a rejection, each with its note", async () => {
    const backend = installFakeBackend({ user: demoUser });
    backend.state.afterSalesCases = [escalatedCase(900), escalatedCase(901), escalatedCase(902)];
    const user = renderApp();
    const section = await openAfterSales(user);

    await decide(await viewCase(section, user, 900), user, "Full refund", "Both were cold");
    expect(
      await within(section).findByText("Case #900 arbitrated: it is REFUNDING now."),
    ).toBeVisible();
    const full = within(section).getByRole("article", { name: "Case #900 details" });
    expect(full).toHaveTextContent("Full refund granted by the platform");
    expect(full).toHaveTextContent("Platform noteBoth were cold");
    expect(within(full).queryByRole("form")).toBeNull();

    await decide(
      await viewCase(section, user, 901),
      user,
      "Partial refund",
      "One was cold",
      "6.39",
    );
    expect(
      await within(section).findByText("Case #901 arbitrated: it is REFUNDING now."),
    ).toBeVisible();

    await decide(await viewCase(section, user, 902), user, "Reject", "No evidence");
    expect(
      await within(section).findByText("Case #902 arbitrated: it is CLOSED now."),
    ).toBeVisible();
    expect(within(section).getByRole("article", { name: "Case #902 details" })).toHaveTextContent(
      "Rejected by the platform",
    );

    expect(backend.callsTo("POST", "/admin/after-sales/900/arbitrate").map(requestJson)).toEqual([
      { decision: "FULL", note: "Both were cold" },
    ]);
    expect(backend.callsTo("POST", "/admin/after-sales/901/arbitrate").map(requestJson)).toEqual([
      { decision: "PARTIAL", amount: 6.39, note: "One was cold" },
    ]);
    expect(backend.callsTo("POST", "/admin/after-sales/902/arbitrate").map(requestJson)).toEqual([
      { decision: "REJECT", note: "No evidence" },
    ]);
    const keys = idempotencyKeys(backend.calls.filter((call) => call.path.endsWith("/arbitrate")));
    expect(new Set(keys).size).toBe(3);
    expect(keys).not.toContain(null);
  });

  it("blocks a blank note and a partial amount that is not part of the requested one", async () => {
    const backend = installFakeBackend({ user: demoUser });
    backend.state.afterSalesCases = [escalatedCase(900)];
    const user = renderApp();
    const details = await viewCase(await openAfterSales(user), user, 900);

    await decide(details, user, "Full refund", "");
    expect(within(details).getByRole("alert")).toHaveTextContent(
      "Write a note for the customer and the restaurant.",
    );
    for (const amount of ["12.78", "13", "0", "1.234", "abc"]) {
      await decide(details, user, "Partial refund", "Partly", amount);
      expect(within(details).getByRole("alert")).toHaveTextContent(
        "Enter an amount more than 0 and less than $12.78, with at most two decimals.",
      );
    }
    expect(backend.callsTo("POST", "/admin/after-sales/900/arbitrate")).toHaveLength(0);
  });

  it("takes only a whole partial amount for a case in a currency without minor unit", async () => {
    const jpy = currencyCode("JPY");
    const backend = installFakeBackend({ user: demoUser });
    backend.state.afterSalesCases = [
      escalatedCase(900, {
        requested_amount: 1900,
        currency: jpy,
        lines: [
          {
            order_item_id: 5,
            menu_item_name: "Whopper",
            quantity: 2,
            unit_price: 950,
            amount: 1900,
            currency: jpy,
          },
        ],
      }),
    ];
    const user = renderApp();
    const details = await viewCase(await openAfterSales(user), user, 900);

    // The backend refuses decimals the yen does not have (400).
    await decide(details, user, "Partial refund", "Partly", "950.50");
    expect(within(details).getByRole("alert")).toHaveTextContent(
      "Enter an amount more than 0 and less than ¥1,900, as a whole number.",
    );
    expect(backend.callsTo("POST", "/admin/after-sales/900/arbitrate")).toHaveLength(0);

    await decide(details, user, "Partial refund", "Partly", "950");
    expect(await screen.findByText("Case #900 arbitrated: it is REFUNDING now.")).toBeVisible();
    expect(backend.callsTo("POST", "/admin/after-sales/900/arbitrate").map(requestJson)).toEqual([
      { decision: "PARTIAL", amount: 950, note: "Partly" },
    ]);
  });

  it("retries a failed arbitration with its key and takes a new key for a changed payload", async () => {
    const backend = installFakeBackend({ user: demoUser });
    backend.state.afterSalesCases = [escalatedCase(900)];
    ["trace-0", "trace-1", "trace-2"].forEach((traceId) =>
      backend.on("POST", "/admin/after-sales/900/arbitrate", () =>
        apiError(503, "Arbitration unavailable", { traceId }),
      ),
    );
    const user = renderApp();
    const details = await viewCase(await openAfterSales(user), user, 900);

    await decide(details, user, "Partial refund", "One was cold", "6.39");
    expect(await within(details).findByRole("alert")).toHaveTextContent("Trace trace-0.");
    // The same decision again is the same attempt.
    await user.click(within(details).getByRole("button", { name: "Submit decision" }));
    expect(await within(details).findByText(/Trace trace-1\./)).toBeVisible();
    // Another amount is another request.
    await decide(details, user, "Partial refund", "One was cold", "5");
    expect(await within(details).findByText(/Trace trace-2\./)).toBeVisible();
    await user.click(within(details).getByRole("button", { name: "Submit decision" }));
    expect(await screen.findByText("Case #900 arbitrated: it is REFUNDING now.")).toBeVisible();

    const calls = backend.callsTo("POST", "/admin/after-sales/900/arbitrate");
    expect(calls.map(requestJson)).toEqual([
      { decision: "PARTIAL", amount: 6.39, note: "One was cold" },
      { decision: "PARTIAL", amount: 6.39, note: "One was cold" },
      { decision: "PARTIAL", amount: 5, note: "One was cold" },
      { decision: "PARTIAL", amount: 5, note: "One was cold" },
    ]);
    const [first, retry, changed, changedRetry] = idempotencyKeys(calls);
    expect(first).toEqual(expect.any(String));
    expect(retry).toBe(first);
    expect(changed).not.toBe(first);
    expect(changedRetry).toBe(changed);
  });

  it("keeps a failed arbitration's key per case while another case is arbitrated", async () => {
    const backend = installFakeBackend({ user: demoUser });
    backend.state.afterSalesCases = [escalatedCase(900), escalatedCase(901)];
    backend.on("POST", "/admin/after-sales/900/arbitrate", () =>
      apiError(503, "Arbitration unavailable", { traceId: "trace-900" }),
    );
    backend.on("POST", "/admin/after-sales/901/arbitrate", () =>
      apiError(503, "Arbitration unavailable", { traceId: "trace-901" }),
    );
    const user = renderApp();
    const section = await openAfterSales(user);

    // Both may have reached the server before they failed.
    const first = await viewCase(section, user, 900);
    await decide(first, user, "Full refund", "Both were cold");
    expect(await within(first).findByRole("alert")).toHaveTextContent("Trace trace-900.");
    const second = await viewCase(section, user, 901);
    await decide(second, user, "Reject", "No evidence");
    expect(await within(second).findByRole("alert")).toHaveTextContent("Trace trace-901.");

    // Each retry replays its own case's attempt, whatever was arbitrated in between.
    await decide(await viewCase(section, user, 900), user, "Full refund", "Both were cold");
    expect(
      await within(section).findByText("Case #900 arbitrated: it is REFUNDING now."),
    ).toBeVisible();
    await decide(await viewCase(section, user, 901), user, "Reject", "No evidence");
    expect(
      await within(section).findByText("Case #901 arbitrated: it is CLOSED now."),
    ).toBeVisible();

    const firstKeys = idempotencyKeys(backend.callsTo("POST", "/admin/after-sales/900/arbitrate"));
    const secondKeys = idempotencyKeys(backend.callsTo("POST", "/admin/after-sales/901/arbitrate"));
    expect(firstKeys).toEqual([expect.any(String), firstKeys[0]]);
    expect(secondKeys).toEqual([expect.any(String), secondKeys[0]]);
    expect(secondKeys[0]).not.toBe(firstKeys[0]);
  });

  it("retries a failed refund, and re-reads the list when it is no longer failed", async () => {
    const backend = installFakeBackend({ user: demoUser });
    backend.state.afterSalesCases = [failedRefundCase(905, 906), failedRefundCase(907, 908)];
    const user = renderApp();
    const section = await openAfterSales(user);
    const failed = within(section).getByRole("region", { name: "Failed refunds" });

    await user.click(await within(failed).findByRole("button", { name: "Retry refund #906" }));
    expect(
      await within(section).findByText("Refund #906 is PENDING again; the executor will retry it."),
    ).toBeVisible();
    expect(within(failed).queryByRole("row", { name: /Refund #906/ })).toBeNull();
    expect(
      backend.callsTo("POST", "/admin/refunds/906/retry")[0]?.headers.get("Idempotency-Key"),
    ).toBeNull();

    // Another admin's retry went through meanwhile and the refund succeeded.
    const other = backend.state.afterSalesCases.find((item) => item.id === 907)?.refund;
    if (other) {
      other.status = "SUCCEEDED";
    }
    const reads = backend.callsTo("GET", "/admin/refunds").length;
    await user.click(within(failed).getByRole("button", { name: "Retry refund #908" }));
    expect(await within(failed).findByRole("alert")).toHaveTextContent(
      "Refund #908: 409: Refund is SUCCEEDED; only FAILED refunds can be retried",
    );
    await waitFor(() => expect(backend.callsTo("GET", "/admin/refunds")).toHaveLength(reads + 1));
    expect(await within(failed).findByText("No failed refunds.")).toBeVisible();
  });

  it("drops a slow case response once another case is selected", async () => {
    const backend = installFakeBackend({ user: demoUser });
    backend.state.afterSalesCases = [escalatedCase(900), escalatedCase(901)];
    const slow = deferred();
    backend.on("GET", "/admin/after-sales/900", () => slow.promise);
    const user = renderApp();
    const section = await openAfterSales(user);

    await user.click(await within(section).findByRole("button", { name: "View case #900" }));
    expect(await within(section).findByText("Loading case #900...")).toBeVisible();
    await viewCase(section, user, 901);

    slow.resolve(json(escalatedCase(900, { status: "CLOSED", close_reason: "WITHDRAWN" })));
    await flushPromises();

    expect(within(section).getByRole("article", { name: "Case #901 details" })).toBeVisible();
    expect(within(section).queryByRole("article", { name: "Case #900 details" })).toBeNull();
    expect(within(section).getByRole("button", { name: "View case #901" })).toHaveAttribute(
      "aria-pressed",
      "true",
    );
  });

  it("keeps loading another case selected while an arbitration was answered", async () => {
    const backend = installFakeBackend({ user: demoUser });
    backend.state.afterSalesCases = [escalatedCase(900), escalatedCase(901)];
    const arbitration = deferred();
    const otherCase = deferred();
    const user = renderApp();
    const section = await openAfterSales(user);

    const details = await viewCase(section, user, 900);
    backend.on("POST", "/admin/after-sales/900/arbitrate", () => arbitration.promise);
    await decide(details, user, "Reject", "Not supported by the photos");
    backend.on("GET", "/admin/after-sales/901", () => otherCase.promise);
    await user.click(within(section).getByRole("button", { name: "View case #901" }));

    const queueReads = backend.callsTo("GET", "/admin/after-sales").length;
    arbitration.resolve(
      json(escalatedCase(900, { status: "CLOSED", close_reason: "ARBITRATION_REJECTED" })),
    );
    await flushPromises();
    otherCase.resolve(json(escalatedCase(901)));

    // The answer about #900 neither drops #901's read nor shows #900 again.
    expect(
      await within(section).findByRole("article", { name: "Case #901 details" }),
    ).toBeVisible();
    expect(within(section).queryByRole("article", { name: "Case #900 details" })).toBeNull();
    // The queue is read again: a read in flight may predate the decision.
    expect(backend.callsTo("GET", "/admin/after-sales")).toHaveLength(queueReads + 1);
  });
});
