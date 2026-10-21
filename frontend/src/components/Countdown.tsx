import { useNow } from "../hooks/useNow";
import { formatDateTime, timeLeft, utcTimestamp } from "../lib/format";

/**
 * The deadline of an after-sales step: the time left, ticking, with the deadline
 * itself; "Deadline passed" once it is over (the backend then refuses a late answer
 * with 409 AFTER_SALES_DEADLINE_PASSED, or its timeout takes over).
 */
export function Countdown({ deadline, label = "Due" }: { deadline: string; label?: string }) {
  const left = timeLeft(deadline, useNow());

  return (
    <span className={left ? "countdown" : "countdown countdown--passed"}>
      {left ? `${label} in ${left}` : "Deadline passed"}{" "}
      <time dateTime={utcTimestamp(deadline)}>({formatDateTime(deadline)})</time>
    </span>
  );
}
