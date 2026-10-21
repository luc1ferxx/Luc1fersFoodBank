-- The claims of a refund that failed in a row after the claim, before any gateway call (docs/adr/0002-after-sales-
-- state-machine.md section 27, follow-up 9). Such a claim backs off without using up an attempt; once
-- app.after-sales.refund.max-claim-failures of them failed in a row the executor gives the refund up (FAILED) for an
-- admin to retry, instead of backing it off forever. Every other step of the refund starts the count over.
ALTER TABLE refunds
ADD COLUMN claim_failures INTEGER DEFAULT 0 NOT NULL;

ALTER TABLE refunds
ADD CONSTRAINT chk_refund_claim_failures CHECK (claim_failures >= 0);
