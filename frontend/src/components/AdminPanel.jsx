import { Alert } from "./Alert";

const ORDER_STATUSES = ["PLACED", "PAID", "ACCEPTED", "PREPARING", "COMPLETED", "CANCELLED"];

const readField = (event, name) => String(new FormData(event.currentTarget).get(name) || "").trim();

export function AdminPanel({ admin }) {
  const { pending, error, orderResult, replayResult, updateStatus, replay } = admin;

  const handleStatusSubmit = (event) => {
    event.preventDefault();
    updateStatus(readField(event, "order_id"), readField(event, "status"));
  };

  const handleReplaySubmit = (event) => {
    event.preventDefault();
    replay(readField(event, "dead_letter_id"));
  };

  return (
    <section className="panel-body" aria-label="Manual admin tools">
      <div className="panel-heading">
        <p className="section-kicker">Manual admin tools</p>
        <h2>Use IDs from backend logs or database records</h2>
      </div>
      <p className="quiet-copy">
        The session endpoint does not expose roles, and the backend does not expose all-order or
        dead-letter list endpoints. These forms intentionally require manual IDs and let the backend
        enforce ROLE_ADMIN.
      </p>
      <Alert tone="danger">{error}</Alert>

      <form className="stack-form admin-form" onSubmit={handleStatusSubmit}>
        <h3>Order status transition</h3>
        <div className="two-fields">
          <label>
            Order ID
            <input name="order_id" inputMode="numeric" placeholder="101" />
          </label>
          <label>
            Target status
            <select name="status" defaultValue="ACCEPTED">
              {ORDER_STATUSES.map((status) => (
                <option key={status} value={status}>
                  {status}
                </option>
              ))}
            </select>
          </label>
        </div>
        <button className="primary-button" type="submit" disabled={pending === "status"}>
          {pending === "status" ? "Updating..." : "Update status"}
        </button>
        {orderResult ? (
          <div className="result-box">
            Order #{orderResult.id} is {orderResult.status}
          </div>
        ) : null}
      </form>

      <form className="stack-form admin-form" onSubmit={handleReplaySubmit}>
        <h3>Dead-letter replay</h3>
        <label>
          Dead-letter event ID
          <input name="dead_letter_id" inputMode="numeric" placeholder="7" />
        </label>
        <button className="primary-button" type="submit" disabled={pending === "replay"}>
          {pending === "replay" ? "Replaying..." : "Replay event"}
        </button>
        {replayResult ? (
          <div className="result-box">
            Replay #{replayResult.id}: {replayResult.replay_status}
          </div>
        ) : null}
      </form>
    </section>
  );
}
