import { type FormEvent, useId, useState } from "react";
import {
  AFTER_SALES_REASONS,
  type AfterSalesReason,
  type AfterSalesScope,
  type Order,
} from "../api/dto";
import type { AfterSalesDraft } from "../hooks/useAfterSales";
import { formatMoney, lineTotal } from "../lib/format";
import { withEntry } from "../lib/maps";
import { AFTER_SALES_REASON_LABELS, orderLabel } from "../lib/labels";
import { Alert } from "./Alert";

const DESCRIPTION_MAX = 1000;

/**
 * A refund request for a completed order: the whole order, or chosen items with a
 * quantity each (at most the quantity ordered), a reason and a description. The amount
 * preview is the sum of unit price x quantity, as the backend computes it.
 */
export function AfterSalesRequestForm({
  order,
  attemptNo,
  pending,
  error,
  onSubmit,
  onCancel,
}: {
  order: Order;
  attemptNo: number;
  pending: boolean;
  error: string;
  onSubmit: (draft: AfterSalesDraft) => void;
  onCancel: () => void;
}) {
  const [scope, setScope] = useState<AfterSalesScope>("WHOLE_ORDER");
  const [quantities, setQuantities] = useState<ReadonlyMap<number, number>>(new Map());
  const [reason, setReason] = useState<AfterSalesReason | null>(null);
  const [description, setDescription] = useState("");
  const [validation, setValidation] = useState("");
  const reasonId = useId();
  const descriptionId = useId();
  const label = orderLabel(order);

  const quantityOf = (itemId: number, ordered: number) =>
    scope === "WHOLE_ORDER" ? ordered : (quantities.get(itemId) ?? 0);
  const refundCents = order.items.reduce(
    (sum, item) =>
      sum + Math.round(lineTotal(item.price, quantityOf(item.id, item.quantity)) * 100),
    0,
  );

  const changeQuantity = (itemId: number, quantity: number) => {
    setQuantities((current) => withEntry(current, itemId, quantity));
  };

  const submit = (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    if (scope === "ITEMS" && ![...quantities.values()].some((quantity) => quantity > 0)) {
      setValidation("Choose at least one item.");
      return;
    }
    if (!reason) {
      setValidation("Choose a reason.");
      return;
    }
    if (!description.trim()) {
      setValidation("Describe what went wrong.");
      return;
    }
    setValidation("");
    onSubmit({ scope, quantities, reason, description });
  };

  return (
    <form
      className="stack-form after-sales-form"
      aria-label={`Refund request for ${label}`}
      onSubmit={submit}
      noValidate
    >
      {attemptNo > 1 ? (
        <p className="quiet-copy">This is the last refund request this order can get.</p>
      ) : null}
      <fieldset className="after-sales-form__scope">
        <legend>What should be refunded?</legend>
        <label className="toggle-row">
          <input
            type="radio"
            name={`scope-${order.id}`}
            checked={scope === "WHOLE_ORDER"}
            onChange={() => setScope("WHOLE_ORDER")}
          />
          Whole order
        </label>
        <label className="toggle-row">
          <input
            type="radio"
            name={`scope-${order.id}`}
            checked={scope === "ITEMS"}
            onChange={() => setScope("ITEMS")}
          />
          Selected items
        </label>
      </fieldset>
      {scope === "ITEMS" ? (
        <ul className="after-sales-form__items">
          {order.items.map((item) => {
            const quantity = quantities.get(item.id) ?? 0;
            return (
              <li key={item.id}>
                <span>
                  {item.menu_item_name} (ordered {item.quantity})
                </span>
                <div className="stepper" aria-label={`${item.menu_item_name} refund quantity`}>
                  <button
                    type="button"
                    disabled={quantity === 0}
                    onClick={() => changeQuantity(item.id, quantity - 1)}
                    aria-label={`Fewer ${item.menu_item_name}`}
                  >
                    -
                  </button>
                  <strong>{quantity}</strong>
                  <button
                    type="button"
                    disabled={quantity >= item.quantity}
                    onClick={() => changeQuantity(item.id, quantity + 1)}
                    aria-label={`More ${item.menu_item_name}`}
                  >
                    +
                  </button>
                </div>
              </li>
            );
          })}
        </ul>
      ) : null}
      <label htmlFor={reasonId}>Reason</label>
      <select
        id={reasonId}
        value={reason ?? ""}
        onChange={(event) =>
          setReason(AFTER_SALES_REASONS.find((code) => code === event.target.value) ?? null)
        }
      >
        <option value="" disabled>
          Choose a reason
        </option>
        {AFTER_SALES_REASONS.map((code) => (
          <option key={code} value={code}>
            {AFTER_SALES_REASON_LABELS[code]}
          </option>
        ))}
      </select>
      <label htmlFor={descriptionId}>What went wrong?</label>
      <textarea
        id={descriptionId}
        value={description}
        maxLength={DESCRIPTION_MAX}
        rows={3}
        onChange={(event) => setDescription(event.target.value)}
      />
      <p className="after-sales-form__preview">
        Refund amount <strong>{formatMoney(refundCents / 100, order.currency)}</strong>
      </p>
      <Alert tone="danger">{validation || error}</Alert>
      <div className="after-sales__actions">
        <button className="primary-button" type="submit" disabled={pending}>
          {pending ? "Submitting..." : "Submit request"}
        </button>
        <button className="ghost-button" type="button" disabled={pending} onClick={onCancel}>
          Cancel
        </button>
      </div>
    </form>
  );
}
