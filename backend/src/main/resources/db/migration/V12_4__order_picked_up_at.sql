-- When the rider picked the order up, as the order module learned it from delivery.picked_up (docs/adr/0002-after-
-- sales-state-machine.md section 27, follow-up 17). A customer who cancels an ACCEPTED order after that gets only
-- app.after-sales.picked-up-cancel-refund-percent of its total back automatically. NULL until a pickup is recorded;
-- written once. A nullable column without a default changes no existing row.
ALTER TABLE orders
ADD COLUMN picked_up_at TIMESTAMP;
