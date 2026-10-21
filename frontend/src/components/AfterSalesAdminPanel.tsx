import { type FormEvent, useEffect, useId, useState } from "react";
import {
  AFTER_SALES_STATUSES,
  type AfterSalesCase,
  ARBITRATION_DECISIONS,
  type ArbitrationDecision,
  parseAfterSalesStatus,
} from "../api/dto";
import { ARBITRATION_NOTE_MAX, type AfterSalesAdmin } from "../hooks/useAfterSalesAdmin";
import { formatDateTime, formatMoney } from "../lib/format";
import {
  ACTOR_LABELS,
  AFTER_SALES_ACTION_LABELS,
  AFTER_SALES_CLOSE_REASON_LABELS,
  AFTER_SALES_REASON_LABELS,
  AFTER_SALES_RESOLUTION_LABELS,
  AFTER_SALES_STATUS_LABELS,
  REFUND_STATUS_LABELS,
  orderLabel,
} from "../lib/labels";
import { Alert } from "./Alert";
import { Countdown } from "./Countdown";

const DECISION_LABELS: Readonly<Record<ArbitrationDecision, string>> = {
  FULL: "Full refund",
  PARTIAL: "Partial refund",
  REJECT: "Reject",
};

/** Staff see the public order number and the internal ids next to it. */
const caseOrderLabel = (current: { order_id: number; order_no: string | null }) =>
  `${orderLabel({ id: current.order_id, order_no: current.order_no })} (id ${current.order_id})`;

function StatusBadge({ status, label }: { status: string; label: string }) {
  return <span className={`status-badge status-badge--${status}`}>{label}</span>;
}

/** The decision on an escalated case; the hook keeps its Idempotency-Key per payload. */
function ArbitrationForm({ current, admin }: { current: AfterSalesCase; admin: AfterSalesAdmin }) {
  const [decision, setDecision] = useState<ArbitrationDecision>("FULL");
  const [amount, setAmount] = useState("");
  const [note, setNote] = useState("");
  const amountId = useId();
  const noteId = useId();

  const submit = (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    void admin.arbitrate(current, decision, amount, note);
  };

  return (
    <form
      className="stack-form admin-form"
      aria-label={`Arbitrate case #${current.id}`}
      noValidate
      onSubmit={submit}
    >
      <h3>Arbitration</h3>
      <fieldset className="decision-options">
        <legend>Decision</legend>
        {ARBITRATION_DECISIONS.map((option) => (
          <label key={option} className="toggle-row">
            <input
              type="radio"
              name={`decision-${current.id}`}
              value={option}
              checked={decision === option}
              onChange={() => setDecision(option)}
            />
            {DECISION_LABELS[option]}
          </label>
        ))}
      </fieldset>
      {decision === "PARTIAL" ? (
        <label htmlFor={amountId}>
          Refund amount ({current.currency}, less than{" "}
          {formatMoney(current.requested_amount, current.currency)})
          <input
            id={amountId}
            inputMode="decimal"
            value={amount}
            placeholder="0.00"
            onChange={(event) => setAmount(event.target.value)}
          />
        </label>
      ) : null}
      <label htmlFor={noteId}>
        Note for the customer and the restaurant
        <textarea
          id={noteId}
          value={note}
          maxLength={ARBITRATION_NOTE_MAX}
          rows={3}
          onChange={(event) => setNote(event.target.value)}
        />
      </label>
      <Alert tone="danger">{admin.arbitrateError}</Alert>
      <button className="primary-button" type="submit" disabled={admin.arbitrating}>
        {admin.arbitrating ? "Submitting..." : "Submit decision"}
      </button>
    </form>
  );
}

function CaseDetails({ current, admin }: { current: AfterSalesCase; admin: AfterSalesAdmin }) {
  const money = (amount: number | null) =>
    amount === null ? "-" : formatMoney(amount, current.currency);

  return (
    <article className="order-card" aria-label={`Case #${current.id} details`}>
      <div className="order-card__top">
        <div>
          <p className="section-kicker">
            Case #{current.id} · {caseOrderLabel(current)}
          </p>
          <h3>{money(current.requested_amount)} requested</h3>
        </div>
        <StatusBadge status={current.status} label={AFTER_SALES_STATUS_LABELS[current.status]} />
      </div>
      <p>
        {current.kind === "ORDER_CANCELLED"
          ? "Automatic refund of a cancelled order"
          : `Customer request, attempt ${current.attempt_no} of 2`}
        {current.reason_code ? ` · ${AFTER_SALES_REASON_LABELS[current.reason_code]}` : ""}
      </p>
      {current.description ? <p className="quiet-copy">“{current.description}”</p> : null}
      {current.deadline_at ? <Countdown deadline={current.deadline_at} /> : null}
      <ul>
        {current.lines.map((line) => (
          <li key={line.order_item_id}>
            {line.quantity} x {line.menu_item_name} · {formatMoney(line.amount, line.currency)}
          </li>
        ))}
      </ul>
      <dl className="after-sales__amounts">
        <dt>Requested</dt>
        <dd>{money(current.requested_amount)}</dd>
        <dt>Offered</dt>
        <dd>{money(current.offered_amount)}</dd>
        <dt>Approved</dt>
        <dd>{money(current.approved_amount)}</dd>
        <dt>Refunded</dt>
        <dd>{money(current.refunded_amount)}</dd>
        {current.merchant_decision ? (
          <>
            <dt>Restaurant</dt>
            <dd>
              {current.merchant_decision}
              {current.merchant_note ? `: ${current.merchant_note}` : ""}
            </dd>
          </>
        ) : null}
        {current.resolution ? (
          <>
            <dt>Resolution</dt>
            <dd>{AFTER_SALES_RESOLUTION_LABELS[current.resolution]}</dd>
          </>
        ) : null}
        {current.close_reason ? (
          <>
            <dt>Closed</dt>
            <dd>{AFTER_SALES_CLOSE_REASON_LABELS[current.close_reason]}</dd>
          </>
        ) : null}
        {current.admin_note ? (
          <>
            <dt>Platform note</dt>
            <dd>{current.admin_note}</dd>
          </>
        ) : null}
      </dl>

      {current.refund ? (
        <div className="after-sales" aria-label={`Refund #${current.refund.id}`}>
          <h4>
            Refund #{current.refund.id} ·{" "}
            <StatusBadge
              status={current.refund.status}
              label={REFUND_STATUS_LABELS[current.refund.status]}
            />
          </h4>
          <p>
            {formatMoney(current.refund.amount, current.refund.currency)} ·{" "}
            {current.refund.attempts} attempt(s)
            {current.refund.next_attempt_at && current.refund.status === "PENDING"
              ? ` · next ${formatDateTime(current.refund.next_attempt_at)}`
              : ""}
          </p>
          {current.refund.last_error ? (
            <p className="quiet-copy">Last error: {current.refund.last_error}</p>
          ) : null}
        </div>
      ) : null}

      <div className="order-history">
        <div className="order-history__heading">
          <h4>History</h4>
        </div>
        <ol className="history-list" aria-label={`History of case #${current.id}`}>
          {current.history.map((entry, index) => (
            <li key={`${entry.occurred_at}-${index}`}>
              <strong>{AFTER_SALES_ACTION_LABELS[entry.action]}</strong>
              <span>
                {ACTOR_LABELS[entry.actor_type]}
                {entry.amount !== null ? ` · ${formatMoney(entry.amount, entry.currency)}` : ""}
                {entry.note ? ` · ${entry.note}` : ""}
              </span>
              <time dateTime={entry.occurred_at}>{formatDateTime(entry.occurred_at)}</time>
            </li>
          ))}
        </ol>
      </div>

      {current.status === "IN_ARBITRATION" ? (
        <ArbitrationForm key={current.id} current={current} admin={admin} />
      ) : null}
    </article>
  );
}

/**
 * ROLE_ADMIN: the after-sales section of the admin console. The arbitration queue (or
 * the cases of another status), a case's details with its full history and refund, the
 * decision on an escalated case, and the refunds the executor gave up, to retry.
 */
export function AfterSalesAdminPanel({ admin }: { admin: AfterSalesAdmin }) {
  const { loadQueue, loadRefunds, select } = admin;
  const { queue, detail, refunds } = admin;

  useEffect(() => {
    void loadQueue();
    void loadRefunds();
  }, [loadQueue, loadRefunds]);

  const refresh = () => {
    void loadQueue();
    void loadRefunds();
    if (admin.selectedId !== null) {
      void select(admin.selectedId);
    }
  };

  return (
    <section className="panel-body" aria-label="After-sales">
      <div className="panel-heading">
        <div>
          <p className="section-kicker">After-sales</p>
          <h2>Refund requests</h2>
        </div>
        <button className="ghost-button" type="button" disabled={queue.loading} onClick={refresh}>
          Refresh
        </button>
      </div>
      <Alert tone="success">{admin.notice}</Alert>

      <div className="console__toolbar">
        <label>
          Status
          <select
            value={admin.status}
            onChange={(event) => {
              const status = parseAfterSalesStatus(event.target.value);
              if (status) {
                void loadQueue(status);
              }
            }}
          >
            {AFTER_SALES_STATUSES.map((status) => (
              <option key={status} value={status}>
                {AFTER_SALES_STATUS_LABELS[status]}
              </option>
            ))}
          </select>
        </label>
      </div>

      <div className="console--split after-sales-admin">
        <div>
          <Alert tone="danger">{queue.error}</Alert>
          {queue.data === null ? (
            queue.error ? null : (
              <div className="loading-row">Loading refund requests...</div>
            )
          ) : queue.data.length === 0 ? (
            <div className="empty-state">
              No {AFTER_SALES_STATUS_LABELS[admin.status].toLowerCase()} requests.
            </div>
          ) : (
            <ul className="order-list" aria-label="Refund requests">
              {queue.data.map((item) => (
                <li key={item.id}>
                  <article className="order-card" aria-label={`Case #${item.id}`}>
                    <div className="order-card__top">
                      <div>
                        <p className="section-kicker">
                          Case #{item.id} · {caseOrderLabel(item)}
                        </p>
                        <h3>{formatMoney(item.requested_amount, item.currency)}</h3>
                      </div>
                      <StatusBadge
                        status={item.status}
                        label={AFTER_SALES_STATUS_LABELS[item.status]}
                      />
                    </div>
                    <p>
                      {item.reason_code
                        ? AFTER_SALES_REASON_LABELS[item.reason_code]
                        : "Automatic refund"}
                    </p>
                    {item.deadline_at ? <Countdown deadline={item.deadline_at} /> : null}
                    <div className="order-card__actions">
                      <button
                        className="ghost-button"
                        type="button"
                        aria-pressed={admin.selectedId === item.id}
                        onClick={() => void select(item.id)}
                      >
                        View case #{item.id}
                      </button>
                    </div>
                  </article>
                </li>
              ))}
            </ul>
          )}
        </div>

        <section aria-label="Case details">
          {admin.selectedId === null ? (
            <div className="empty-state">Choose a case to see its history.</div>
          ) : (
            <>
              <Alert tone="danger">{detail.error}</Alert>
              {detail.data ? (
                <CaseDetails current={detail.data} admin={admin} />
              ) : detail.error ? null : (
                <div className="loading-row">Loading case #{admin.selectedId}...</div>
              )}
            </>
          )}
        </section>
      </div>

      <section className="after-sales" aria-label="Failed refunds">
        <h3>Failed refunds</h3>
        <Alert tone="danger">{refunds.error || admin.refundError}</Alert>
        {refunds.data === null ? (
          refunds.error ? null : (
            <div className="loading-row">Loading failed refunds...</div>
          )
        ) : refunds.data.length === 0 ? (
          <div className="empty-state">No failed refunds.</div>
        ) : (
          <div className="table-scroll">
            <table className="accounts-table">
              <caption className="visually-hidden">Failed refunds</caption>
              <thead>
                <tr>
                  <th scope="col">Refund</th>
                  <th scope="col">Amount</th>
                  <th scope="col">Attempts</th>
                  <th scope="col">Last error</th>
                  <th scope="col">
                    <span className="visually-hidden">Actions</span>
                  </th>
                </tr>
              </thead>
              <tbody>
                {refunds.data.map((refund) => (
                  <tr key={refund.id}>
                    <td>
                      <strong>Refund #{refund.id}</strong>
                      <span>
                        Case #{refund.case_id} · {caseOrderLabel(refund)}
                      </span>
                    </td>
                    <td>{formatMoney(refund.amount, refund.currency)}</td>
                    <td>{refund.attempts}</td>
                    <td>{refund.last_error ?? "-"}</td>
                    <td>
                      <button
                        className="ghost-button"
                        type="button"
                        aria-label={`Retry refund #${refund.id}`}
                        disabled={admin.retrying.has(refund.id)}
                        onClick={() => void admin.retryRefund(refund)}
                      >
                        {admin.retrying.has(refund.id) ? "Retrying..." : "Retry"}
                      </button>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </section>
    </section>
  );
}
