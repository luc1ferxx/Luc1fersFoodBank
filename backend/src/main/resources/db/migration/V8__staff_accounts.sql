-- Merchant and rider logins provisioned by admins (POST /admin/merchants, POST /admin/riders; README "Order
-- fulfilment"). A login is a customers row with ROLE_MERCHANT or ROLE_RIDER; the merchant and dispatch modules bind
-- it to their own rows below. Deactivating either keeps its history and takes effect on the account's next request:
-- the module checks the active flag on every merchant or rider request, so no session has to be invalidated.


-- Merchant module. A restaurant counts as staffed while it has at least one active merchant account; only orders of
-- a staffed restaurant get a merchant accept deadline. Existing accounts (the demo seed, SQL-provisioned ones) stay
-- active.
ALTER TABLE merchant_accounts
ADD COLUMN active BOOLEAN DEFAULT TRUE NOT NULL;

ALTER TABLE merchant_accounts
ADD COLUMN updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL;


-- Dispatch module. customer_id is the rider's login (NULL for riders created before this migration, which can still
-- be moved through the admin delivery endpoints); one login is bound to at most one rider. on_duty is set by the
-- rider (PUT /rider/me/availability) and dispatch only assigns riders that are active and on duty. A new rider starts
-- off duty; riders that existed before this migration were dispatchable, so active ones start on duty.
ALTER TABLE riders
ADD COLUMN customer_id BIGINT;

ALTER TABLE riders
ADD COLUMN on_duty BOOLEAN DEFAULT FALSE NOT NULL;

UPDATE riders
SET on_duty = active;

ALTER TABLE riders
ADD CONSTRAINT uq_rider_customer UNIQUE (customer_id);

-- SET NULL rather than CASCADE: deliveries reference the rider, so the rider row must outlive its login.
ALTER TABLE riders
ADD CONSTRAINT fk_rider_customer FOREIGN KEY (customer_id) REFERENCES customers (id) ON DELETE SET NULL;


-- The dispatch query filters on both flags now (WHERE active AND on_duty ORDER BY active_deliveries, id).
DROP INDEX idx_riders_active_load_id;

CREATE INDEX idx_riders_dispatchable_load_id
ON riders (active, on_duty, active_deliveries, id);
