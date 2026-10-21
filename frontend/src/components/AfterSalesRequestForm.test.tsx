import { render, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it, vi } from "vitest";
import { type AfterSalesDraft, openRequestOf } from "../hooks/useAfterSales";
import { EUR, orderFixture } from "../test/fakeBackend";
import { AfterSalesRequestForm } from "./AfterSalesRequestForm";

const order = orderFixture({
  id: 7,
  status: "COMPLETED",
  total_price: 2.3,
  currency: EUR,
  items: [
    {
      id: 5,
      menu_item_id: 30,
      restaurant_id: 3,
      price: 0.1,
      currency: EUR,
      quantity: 3,
      menu_item_name: "Fish Taco",
      menu_item_description: null,
      menu_item_image_url: null,
    },
    {
      id: 6,
      menu_item_id: 31,
      restaurant_id: 3,
      price: 1,
      currency: EUR,
      quantity: 2,
      menu_item_name: "Churro",
      menu_item_description: null,
      menu_item_image_url: null,
    },
  ],
});

const renderForm = ({ attemptNo = 1 } = {}) => {
  const onSubmit = vi.fn((_draft: AfterSalesDraft) => undefined);
  const user = userEvent.setup();
  render(
    <AfterSalesRequestForm
      order={order}
      attemptNo={attemptNo}
      pending={false}
      error=""
      onSubmit={onSubmit}
      onCancel={() => undefined}
    />,
  );
  return { user, onSubmit };
};

const submittedBody = (onSubmit: ReturnType<typeof renderForm>["onSubmit"]) => {
  const draft = onSubmit.mock.calls[0]?.[0];
  if (!draft) {
    throw new Error("The form was not submitted");
  }
  return openRequestOf(draft);
};

describe("AfterSalesRequestForm", () => {
  it("asks for the whole order by default, previewing its total", async () => {
    const { user, onSubmit } = renderForm();

    // 3 x €0.10 + 2 x €1.00, summed in cents.
    expect(screen.getByText("€2.30")).toBeInTheDocument();
    await user.selectOptions(screen.getByLabelText("Reason"), "LATE_DELIVERY");
    await user.type(screen.getByLabelText("What went wrong?"), "  An hour late  ");
    await user.click(screen.getByRole("button", { name: "Submit request" }));

    expect(submittedBody(onSubmit)).toEqual({
      scope: "WHOLE_ORDER",
      lines: [],
      reason_code: "LATE_DELIVERY",
      description: "An hour late",
    });
  });

  it("caps each item's quantity at the quantity ordered and previews the chosen lines", async () => {
    const { user, onSubmit } = renderForm();

    await user.click(screen.getByLabelText("Selected items"));
    expect(screen.getByText("€0.00")).toBeInTheDocument();
    const tacos = screen.getByLabelText("Fish Taco refund quantity");
    const more = within(tacos).getByRole("button", { name: "More Fish Taco" });
    expect(within(tacos).getByRole("button", { name: "Fewer Fish Taco" })).toBeDisabled();
    await user.click(more);
    await user.click(more);
    await user.click(more);
    expect(within(tacos).getByText("3")).toBeInTheDocument();
    expect(more).toBeDisabled();
    // 3 x 0.10 in cents, not 0.30000000000000004.
    expect(screen.getByText("€0.30")).toBeInTheDocument();
    await user.click(within(tacos).getByRole("button", { name: "Fewer Fish Taco" }));
    await user.click(screen.getByRole("button", { name: "More Churro" }));
    expect(screen.getByText("€1.20")).toBeInTheDocument();

    await user.selectOptions(screen.getByLabelText("Reason"), "MISSING_ITEM");
    await user.type(screen.getByLabelText("What went wrong?"), "Missing");
    await user.click(screen.getByRole("button", { name: "Submit request" }));

    expect(submittedBody(onSubmit)).toEqual({
      scope: "ITEMS",
      lines: [
        { order_item_id: 5, quantity: 2 },
        { order_item_id: 6, quantity: 1 },
      ],
      reason_code: "MISSING_ITEM",
      description: "Missing",
    });
  });

  it("needs an item, a reason and a description before it submits", async () => {
    const { user, onSubmit } = renderForm({ attemptNo: 2 });
    const submit = screen.getByRole("button", { name: "Submit request" });

    expect(
      screen.getByText("This is the last refund request this order can get."),
    ).toBeInTheDocument();
    await user.click(screen.getByLabelText("Selected items"));
    await user.click(submit);
    expect(screen.getByRole("alert")).toHaveTextContent("Choose at least one item.");
    await user.click(screen.getByRole("button", { name: "More Churro" }));
    await user.click(submit);
    expect(screen.getByRole("alert")).toHaveTextContent("Choose a reason.");
    await user.selectOptions(screen.getByLabelText("Reason"), "OTHER");
    await user.type(screen.getByLabelText("What went wrong?"), "   ");
    await user.click(submit);
    expect(screen.getByRole("alert")).toHaveTextContent("Describe what went wrong.");
    expect(onSubmit).not.toHaveBeenCalled();
  });
});
