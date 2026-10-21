import { type FormEvent, useId, useState } from "react";
import type { AfterSalesStatus, MerchantAfterSalesCase } from "../api/dto";
import {
  type MerchantAfterSalesAnswer,
  type MerchantAfterSalesCommand,
  caseOrderLabel,
} from "../hooks/useMerchantAfterSales";
import { useNow } from "../hooks/useNow";
import { formatDateTime, formatMoney, timeLeft } from "../lib/format";
import {
  amountInputExample,
  amountInputRule,
  isPartialAmount,
  parseAmountInput,
} from "../lib/forms";
import { AFTER_SALES_REASON_LABELS, AFTER_SALES_STATUS_LABELS } from "../lib/labels";
import { Alert } from "./Alert";
import { Countdown } from "./Countdown";

const NOTE_MAX = 500;

const pendingCopy: Readonly<Record<MerchantAfterSalesCommand, string>> = {
  approve: "Approving...",
  reject: "Rejecting...",
  offer: "Offering...",
};

/** Whose answer an open status waits for, as the countdown names it. */
const DEADLINE_LABELS: Readonly<Partial<Record<AfterSalesStatus, string>>> = {
  PENDING_MERCHANT: "Your answer due",
  COUNTER_OFFERED: "Customer's answer due",
  REJECTED: "Customer can escalate",
  IN_ARBITRATION: "Platform decision due",
};

type Mode = "answer" | "reject" | "offer";

interface MerchantAfterSalesCardProps {
  current: MerchantAfterSalesCase;
  pending: MerchantAfterSalesCommand | undefined;
  /** The account cannot act (deactivated): the answers are shown but disabled. */
  locked: boolean;
  error: string;
  onAnswer: (current: MerchantAfterSalesCase, answer: MerchantAfterSalesAnswer) => Promise<boolean>;
}

function MerchantAfterSalesCard({
  current,
  pending,
  locked,
  error,
  onAnswer,
}: MerchantAfterSalesCardProps) {
  const [mode, setMode] = useState<Mode>("answer");
  const [reason, setReason] = useState("");
  const [amount, setAmount] = useState("");
  const [note, setNote] = useState("");
  const [formError, setFormError] = useState("");
  const reasonId = useId();
  const amountId = useId();
  const noteId = useId();
  const now = useNow();
  const order = caseOrderLabel(current);
  const deadlineLabel = DEADLINE_LABELS[current.status];
  const waitingForMerchant = current.status === "PENDING_MERCHANT";
  // The backend refuses an answer from the deadline on (409 AFTER_SALES_DEADLINE_PASSED)
  // and approves the requested amount itself.
  const late = current.deadline_at !== null && timeLeft(current.deadline_at, now) === null;
  const busy = pending !== undefined || locked || late;

  const back = () => {
    setMode("answer");
    setFormError("");
  };

  const submitReject = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    const trimmed = reason.trim();
    if (!trimmed) {
      setFormError("Tell the customer why you reject the request.");
      return;
    }
    setFormError("");
    if (await onAnswer(current, { command: "reject", reason: trimmed })) {
      setMode("answer");
      setReason("");
    }
  };

  const submitOffer = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    const offered = parseAmountInput(amount, current.currency);
    if (offered === null) {
      setFormError(
        `Enter the amount ${amountInputRule(current.currency)}, like ${amountInputExample(current.currency)}.`,
      );
      return;
    }
    if (!isPartialAmount(offered, current.requested_amount)) {
      setFormError(
        `Offer more than ${formatMoney(0, current.currency)} and less than the requested ${formatMoney(current.requested_amount, current.currency)}.`,
      );
      return;
    }
    setFormError("");
    const trimmedNote = note.trim();
    if (
      await onAnswer(current, {
        command: "offer",
        amount: offered,
        note: trimmedNote === "" ? null : trimmedNote,
      })
    ) {
      setMode("answer");
      setAmount("");
      setNote("");
    }
  };

  return (
    <article className="order-card console-card" aria-label={`Refund request for ${order}`}>
      <div className="order-card__top">
        <div>
          <p className="section-kicker">{order}</p>
          <h3>{formatMoney(current.requested_amount, current.currency)} requested</h3>
        </div>
        <span className={`status-badge status-badge--${current.status}`}>
          {AFTER_SALES_STATUS_LABELS[current.status]}
        </span>
      </div>
      <p>
        {AFTER_SALES_REASON_LABELS[current.reason_code]}: {current.description}
      </p>
      <ul>
        {current.lines.map((line) => (
          <li key={line.order_item_id}>
            {line.quantity} x {line.menu_item_name} · {formatMoney(line.amount, line.currency)}
          </li>
        ))}
      </ul>
      <p className="quiet-copy">Requested {formatDateTime(current.opened_at)}</p>
      {current.deadline_at && deadlineLabel ? (
        <Countdown deadline={current.deadline_at} label={deadlineLabel} />
      ) : null}
      {current.offered_amount !== null ||
      current.approved_amount !== null ||
      current.merchant_note ? (
        <dl className="after-sales__amounts">
          {current.offered_amount !== null ? (
            <>
              <dt>Offered</dt>
              <dd>{formatMoney(current.offered_amount, current.currency)}</dd>
            </>
          ) : null}
          {current.approved_amount !== null ? (
            <>
              <dt>Refund</dt>
              <dd>{formatMoney(current.approved_amount, current.currency)}</dd>
            </>
          ) : null}
          {current.merchant_note ? (
            <>
              <dt>Your note</dt>
              <dd>{current.merchant_note}</dd>
            </>
          ) : null}
        </dl>
      ) : null}
      <Alert tone="danger">{error}</Alert>

      {waitingForMerchant && late ? (
        <p className="quiet-copy">
          The deadline passed: the requested amount is refunded automatically.
        </p>
      ) : null}

      {waitingForMerchant && mode === "answer" ? (
        <div className="after-sales__actions">
          <button
            className="primary-button"
            type="button"
            disabled={busy}
            onClick={() => onAnswer(current, { command: "approve" })}
          >
            {pending === "approve" ? pendingCopy.approve : "Approve refund"}
          </button>
          <button
            className="ghost-button"
            type="button"
            disabled={busy}
            onClick={() => setMode("offer")}
          >
            Offer partial refund
          </button>
          <button
            className="ghost-button"
            type="button"
            disabled={busy}
            onClick={() => setMode("reject")}
          >
            Reject
          </button>
        </div>
      ) : null}

      {waitingForMerchant && mode === "reject" ? (
        <form className="stack-form reject-form" onSubmit={submitReject} noValidate>
          <label htmlFor={reasonId}>Reason for rejecting the refund request of {order}</label>
          <textarea
            id={reasonId}
            value={reason}
            maxLength={NOTE_MAX}
            rows={3}
            disabled={busy}
            onChange={(event) => setReason(event.target.value)}
          />
          <Alert tone="danger">{formError}</Alert>
          <div className="after-sales__actions">
            <button className="ghost-button" type="button" disabled={busy} onClick={back}>
              Back
            </button>
            <button className="primary-button" type="submit" disabled={busy}>
              {pending === "reject" ? pendingCopy.reject : "Reject request"}
            </button>
          </div>
        </form>
      ) : null}

      {waitingForMerchant && mode === "offer" ? (
        <form className="stack-form reject-form" onSubmit={submitOffer} noValidate>
          <label htmlFor={amountId}>Partial refund for {order}</label>
          <input
            id={amountId}
            value={amount}
            inputMode="decimal"
            placeholder={`Less than ${formatMoney(current.requested_amount, current.currency)}`}
            disabled={busy}
            onChange={(event) => setAmount(event.target.value)}
          />
          <label htmlFor={noteId}>Note for the customer (optional)</label>
          <textarea
            id={noteId}
            value={note}
            maxLength={NOTE_MAX}
            rows={2}
            disabled={busy}
            onChange={(event) => setNote(event.target.value)}
          />
          <Alert tone="danger">{formError}</Alert>
          <div className="after-sales__actions">
            <button className="ghost-button" type="button" disabled={busy} onClick={back}>
              Back
            </button>
            <button className="primary-button" type="submit" disabled={busy}>
              {pending === "offer" ? pendingCopy.offer : "Send offer"}
            </button>
          </div>
        </form>
      ) : null}
    </article>
  );
}

interface MerchantAfterSalesListProps {
  cases: readonly MerchantAfterSalesCase[];
  pending: ReadonlyMap<number, MerchantAfterSalesCommand>;
  commandErrors: ReadonlyMap<number, string>;
  locked: boolean;
  onAnswer: (current: MerchantAfterSalesCase, answer: MerchantAfterSalesAnswer) => Promise<boolean>;
}

/** The restaurant's refund requests, each with its deadline and, while it waits, the answers. */
export function MerchantAfterSalesList({
  cases,
  pending,
  commandErrors,
  locked,
  onAnswer,
}: MerchantAfterSalesListProps) {
  return (
    <div className="order-list console__list">
      {cases.map((current) => (
        <MerchantAfterSalesCard
          key={current.case_id}
          current={current}
          pending={pending.get(current.case_id)}
          locked={locked}
          error={commandErrors.get(current.case_id) ?? ""}
          onAnswer={onAnswer}
        />
      ))}
    </div>
  );
}
