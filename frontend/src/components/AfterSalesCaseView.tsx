import { type FormEvent, Fragment, useId, useState } from "react";
import { AFTER_SALES_OPEN_STATUSES, type AfterSalesCase, type AfterSalesStatus } from "../api/dto";
import { type AfterSalesCommand, useAfterSalesCaseDetail } from "../hooks/useAfterSales";
import { useNow } from "../hooks/useNow";
import { formatDateTime, formatMoney, timeLeft } from "../lib/format";
import {
  ACTOR_LABELS,
  AFTER_SALES_ACTION_LABELS,
  AFTER_SALES_CLOSE_REASON_LABELS,
  AFTER_SALES_REASON_LABELS,
  AFTER_SALES_RESOLUTION_LABELS,
  AFTER_SALES_STATUS_LABELS,
  REFUND_STATUS_LABELS,
} from "../lib/labels";
import { Alert } from "./Alert";
import { ConfirmDialog } from "./ConfirmDialog";
import { Countdown } from "./Countdown";

/** What the deadline of each open status decides when nobody answers (ADR 0002 section 5.5). */
const DEADLINE_LABELS: Readonly<Partial<Record<AfterSalesStatus, string>>> = {
  PENDING_MERCHANT: "Restaurant answers",
  COUNTER_OFFERED: "Offer accepted automatically",
  REJECTED: "Closes",
  IN_ARBITRATION: "Platform decides",
};

/** "Automatic refund $12.78: Refunding" for the refund of a paid order that was cancelled. */
export function AutomaticRefund({ afterSalesCase }: { afterSalesCase: AfterSalesCase }) {
  const amount = afterSalesCase.approved_amount ?? afterSalesCase.requested_amount;
  return (
    <p className="after-sales after-sales--automatic">
      Automatic refund {formatMoney(amount, afterSalesCase.currency)}:{" "}
      {AFTER_SALES_STATUS_LABELS[afterSalesCase.status]}
    </p>
  );
}

function AfterSalesHistory({
  id,
  afterSalesCase,
  label,
}: {
  id: string;
  afterSalesCase: AfterSalesCase;
  label: string;
}) {
  const detail = useAfterSalesCaseDetail(
    afterSalesCase.id,
    `${afterSalesCase.status}:${afterSalesCase.updated_at}`,
  );

  return (
    <section className="order-history" id={id} aria-label={`Refund history of ${label}`}>
      <div className="order-history__heading">
        <h4>Refund history</h4>
        <button
          className="ghost-button"
          type="button"
          disabled={detail.loading}
          onClick={detail.reload}
        >
          {detail.loading ? "Loading..." : "Refresh history"}
        </button>
      </div>
      <Alert tone="danger">{detail.error}</Alert>
      {detail.history.length ? (
        <ol className="history-list">
          {detail.history.map((entry, index) => (
            <li key={`${entry.occurred_at}:${entry.action}:${index}`}>
              <strong>{AFTER_SALES_ACTION_LABELS[entry.action]}</strong>
              <span>
                by {ACTOR_LABELS[entry.actor_type]}
                {entry.amount !== null ? ` · ${formatMoney(entry.amount, entry.currency)}` : ""}
              </span>
              {entry.note ? <span>Note: {entry.note}</span> : null}
              <time dateTime={entry.occurred_at}>{formatDateTime(entry.occurred_at)}</time>
            </li>
          ))}
        </ol>
      ) : null}
    </section>
  );
}

/**
 * A customer's refund request: its status, amounts, deadline, the restaurant's and the
 * platform's notes, its refund, and the buttons its status allows. Answering an offer
 * and escalating are refused by the backend after the deadline, so they are disabled
 * then; withdrawing works until a timeout moves the case.
 */
export function AfterSalesCaseView({
  afterSalesCase,
  label,
  pending,
  error,
  onAcceptOffer,
  onDeclineOffer,
  onEscalate,
  onWithdraw,
}: {
  afterSalesCase: AfterSalesCase;
  /** The order's name for people ("Order #<order number>"). */
  label: string;
  pending: AfterSalesCommand | undefined;
  error: string;
  onAcceptOffer: () => void;
  onDeclineOffer: () => void;
  onEscalate: (note: string) => Promise<boolean>;
  onWithdraw: () => Promise<boolean>;
}) {
  const now = useNow();
  const [showHistory, setShowHistory] = useState(false);
  const [escalating, setEscalating] = useState(false);
  const [note, setNote] = useState("");
  const [confirmingWithdraw, setConfirmingWithdraw] = useState(false);
  const historyId = useId();
  const noteId = useId();
  const { status, currency } = afterSalesCase;
  const open = AFTER_SALES_OPEN_STATUSES.has(status);
  const answerable =
    afterSalesCase.deadline_at !== null && timeLeft(afterSalesCase.deadline_at, now) !== null;
  const busy = pending !== undefined;
  const deadlineLabel = DEADLINE_LABELS[status];

  const submitEscalation = (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    void onEscalate(note).then((done) => {
      if (done) {
        setEscalating(false);
        setNote("");
      }
    });
  };

  const amounts: [string, number | null][] = [
    ["Requested", afterSalesCase.requested_amount],
    ["Offered", afterSalesCase.offered_amount],
    ["Approved", afterSalesCase.approved_amount],
    ["Refunded", afterSalesCase.refunded_amount],
  ];

  return (
    <section className="after-sales" aria-label={`Refund request for ${label}`}>
      <div className="order-card__top">
        <h4>{afterSalesCase.attempt_no > 1 ? "Second refund request" : "Refund request"}</h4>
        <span className={`status-badge status-badge--${status}`}>
          {AFTER_SALES_STATUS_LABELS[status]}
        </span>
      </div>
      {afterSalesCase.reason_code ? (
        <p className="quiet-copy">
          {AFTER_SALES_REASON_LABELS[afterSalesCase.reason_code]}
          {afterSalesCase.description ? `: ${afterSalesCase.description}` : ""}
        </p>
      ) : null}
      <ul>
        {afterSalesCase.lines.map((line) => (
          <li key={line.order_item_id}>
            {line.quantity} x {line.menu_item_name} · {formatMoney(line.amount, line.currency)}
          </li>
        ))}
      </ul>
      <dl className="after-sales__amounts">
        {amounts.map(([name, amount]) =>
          amount === null ? null : (
            <Fragment key={name}>
              <dt>{name}</dt>
              <dd>{formatMoney(amount, currency)}</dd>
            </Fragment>
          ),
        )}
      </dl>
      {open && afterSalesCase.deadline_at && deadlineLabel ? (
        <p>
          <Countdown deadline={afterSalesCase.deadline_at} label={deadlineLabel} />
        </p>
      ) : null}
      {afterSalesCase.merchant_note ? (
        <p>
          {afterSalesCase.merchant_decision === "REJECTED"
            ? "Restaurant's reason"
            : "Restaurant's note"}
          : {afterSalesCase.merchant_note}
        </p>
      ) : null}
      {afterSalesCase.admin_note ? <p>Platform's note: {afterSalesCase.admin_note}</p> : null}
      {afterSalesCase.resolution ? (
        <p>{AFTER_SALES_RESOLUTION_LABELS[afterSalesCase.resolution]}</p>
      ) : null}
      {afterSalesCase.close_reason ? (
        <p>Closed: {AFTER_SALES_CLOSE_REASON_LABELS[afterSalesCase.close_reason]}</p>
      ) : null}
      {afterSalesCase.refund ? (
        <p>
          Refund of {formatMoney(afterSalesCase.refund.amount, afterSalesCase.refund.currency)}:{" "}
          {REFUND_STATUS_LABELS[afterSalesCase.refund.status]}
        </p>
      ) : null}
      {confirmingWithdraw ? null : <Alert tone="danger">{error}</Alert>}

      <div className="after-sales__actions">
        {status === "COUNTER_OFFERED" && afterSalesCase.offered_amount !== null ? (
          <>
            <button
              className="primary-button"
              type="button"
              disabled={busy || !answerable}
              onClick={onAcceptOffer}
            >
              {pending === "accept-offer"
                ? "Accepting..."
                : `Accept ${formatMoney(afterSalesCase.offered_amount, currency)}`}
            </button>
            <button
              className="ghost-button"
              type="button"
              disabled={busy || !answerable}
              onClick={onDeclineOffer}
            >
              {pending === "decline-offer" ? "Declining..." : "Decline offer"}
            </button>
          </>
        ) : null}
        {status === "REJECTED" && !escalating ? (
          <button
            className="primary-button"
            type="button"
            disabled={busy || !answerable}
            onClick={() => setEscalating(true)}
          >
            Ask the platform to step in
          </button>
        ) : null}
        {open ? (
          <button
            className="ghost-button"
            type="button"
            disabled={busy}
            onClick={() => setConfirmingWithdraw(true)}
          >
            Withdraw request
          </button>
        ) : null}
        <button
          className="ghost-button"
          type="button"
          aria-expanded={showHistory}
          aria-controls={showHistory ? historyId : undefined}
          onClick={() => setShowHistory((shown) => !shown)}
        >
          {showHistory ? "Hide refund history" : "Refund history"}
        </button>
      </div>

      {status === "REJECTED" && escalating ? (
        <form
          className="stack-form after-sales-form"
          aria-label={`Ask the platform about ${label}`}
          onSubmit={submitEscalation}
          noValidate
        >
          <label htmlFor={noteId}>Note for the platform (optional)</label>
          <textarea
            id={noteId}
            value={note}
            maxLength={1000}
            rows={2}
            onChange={(event) => setNote(event.target.value)}
          />
          <div className="after-sales__actions">
            <button className="primary-button" type="submit" disabled={busy || !answerable}>
              {pending === "escalate" ? "Sending..." : "Send to the platform"}
            </button>
            <button
              className="ghost-button"
              type="button"
              disabled={busy}
              onClick={() => setEscalating(false)}
            >
              Back
            </button>
          </div>
        </form>
      ) : null}

      {showHistory ? (
        <AfterSalesHistory id={historyId} afterSalesCase={afterSalesCase} label={label} />
      ) : null}

      {confirmingWithdraw ? (
        <ConfirmDialog
          title="Withdraw this refund request?"
          confirmLabel="Withdraw"
          pendingLabel="Withdrawing..."
          cancelLabel="Keep request"
          pending={pending === "withdraw"}
          error={error}
          onConfirm={() => {
            void onWithdraw().then((done) => {
              if (done) {
                setConfirmingWithdraw(false);
              }
            });
          }}
          onCancel={() => setConfirmingWithdraw(false)}
        >
          <p>
            {afterSalesCase.attempt_no > 1
              ? "This is the order's second request: it cannot get another one."
              : "You can request a refund for this order once more while its request window is open."}
          </p>
        </ConfirmDialog>
      ) : null}
    </section>
  );
}
