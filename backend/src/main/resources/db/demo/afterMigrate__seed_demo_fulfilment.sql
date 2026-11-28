-- DEMO ONLY. Seed data for the fulfilment flow, loaded only when FLYWAY_LOCATIONS includes classpath:db/demo
-- (local/demo runs, the h2 profile and tests). Never ship it to production: the merchant and rider logins below use
-- well-known plain-text ({noop}) passwords. Idempotent, like afterMigrate__seed_demo_data.sql, which Flyway runs
-- before this file (callbacks of one event run in name order) so the demo restaurants already exist.
--
-- One merchant login (ROLE_MERCHANT) per demo restaurant, password merchant123:
--   merchant.burgerking@example.com -> Burger King
--   merchant.sgdtofu@example.com    -> SGD Tofu House
--   merchant.fashionwok@example.com -> Fashion Wok
-- and three riders for the dispatch module, each with a rider login (ROLE_RIDER), password rider123:
--   rider.alice@example.com -> Demo Rider Alice
--   rider.bob@example.com   -> Demo Rider Bob
--   rider.carol@example.com -> Demo Rider Carol
--
-- Roles and bindings only go to logins this seed created, recognised by their seeded {noop} password. On an existing
-- database one of these emails may already belong to someone who signed up with it (POST /signup stores a BCrypt
-- password): that account keeps its own role and is never made a merchant or rider.


INSERT INTO customers (email, enabled, password, first_name, last_name)
SELECT 'merchant.burgerking@example.com', TRUE, '{noop}merchant123', 'Burger King', 'Merchant'
WHERE NOT EXISTS (
    SELECT 1 FROM customers WHERE LOWER(email) = LOWER('merchant.burgerking@example.com')
);


INSERT INTO customers (email, enabled, password, first_name, last_name)
SELECT 'merchant.sgdtofu@example.com', TRUE, '{noop}merchant123', 'SGD Tofu House', 'Merchant'
WHERE NOT EXISTS (
    SELECT 1 FROM customers WHERE LOWER(email) = LOWER('merchant.sgdtofu@example.com')
);


INSERT INTO customers (email, enabled, password, first_name, last_name)
SELECT 'merchant.fashionwok@example.com', TRUE, '{noop}merchant123', 'Fashion Wok', 'Merchant'
WHERE NOT EXISTS (
    SELECT 1 FROM customers WHERE LOWER(email) = LOWER('merchant.fashionwok@example.com')
);


INSERT INTO authorities (email, authority)
SELECT customers.email, 'ROLE_MERCHANT'
FROM customers
WHERE LOWER(customers.email) IN (
        'merchant.burgerking@example.com',
        'merchant.sgdtofu@example.com',
        'merchant.fashionwok@example.com'
    )
  AND customers.password = '{noop}merchant123'
  AND NOT EXISTS (
    SELECT 1 FROM authorities WHERE authorities.email = customers.email AND authority = 'ROLE_MERCHANT'
);


INSERT INTO merchant_accounts (customer_id, restaurant_id, created_at)
SELECT customers.id, restaurants.id, CURRENT_TIMESTAMP
FROM customers, restaurants
WHERE LOWER(customers.email) = LOWER('merchant.burgerking@example.com')
  AND customers.password = '{noop}merchant123'
  AND restaurants.name = 'Burger King'
  AND NOT EXISTS (
    SELECT 1 FROM merchant_accounts WHERE merchant_accounts.customer_id = customers.id
);


INSERT INTO merchant_accounts (customer_id, restaurant_id, created_at)
SELECT customers.id, restaurants.id, CURRENT_TIMESTAMP
FROM customers, restaurants
WHERE LOWER(customers.email) = LOWER('merchant.sgdtofu@example.com')
  AND customers.password = '{noop}merchant123'
  AND restaurants.name = 'SGD Tofu House'
  AND NOT EXISTS (
    SELECT 1 FROM merchant_accounts WHERE merchant_accounts.customer_id = customers.id
);


INSERT INTO merchant_accounts (customer_id, restaurant_id, created_at)
SELECT customers.id, restaurants.id, CURRENT_TIMESTAMP
FROM customers, restaurants
WHERE LOWER(customers.email) = LOWER('merchant.fashionwok@example.com')
  AND customers.password = '{noop}merchant123'
  AND restaurants.name = 'Fashion Wok'
  AND NOT EXISTS (
    SELECT 1 FROM merchant_accounts WHERE merchant_accounts.customer_id = customers.id
);


-- Riders for the dispatch module. They start on duty so the demo flow dispatches at once; afterwards only the rider
-- (PUT /rider/me/availability) changes that, a restart does not. The admin endpoints
-- POST /admin/deliveries/{id}/picked-up and /delivered still move any delivery for ops and tests.
INSERT INTO riders (name, phone, active, on_duty, active_deliveries)
SELECT 'Demo Rider Alice', '(408) 555-0101', TRUE, TRUE, 0
WHERE NOT EXISTS (SELECT 1 FROM riders WHERE name = 'Demo Rider Alice');


INSERT INTO riders (name, phone, active, on_duty, active_deliveries)
SELECT 'Demo Rider Bob', '(408) 555-0102', TRUE, TRUE, 0
WHERE NOT EXISTS (SELECT 1 FROM riders WHERE name = 'Demo Rider Bob');


INSERT INTO riders (name, phone, active, on_duty, active_deliveries)
SELECT 'Demo Rider Carol', '(408) 555-0103', TRUE, TRUE, 0
WHERE NOT EXISTS (SELECT 1 FROM riders WHERE name = 'Demo Rider Carol');


INSERT INTO customers (email, enabled, password, first_name, last_name)
SELECT 'rider.alice@example.com', TRUE, '{noop}rider123', 'Alice', 'Rider'
WHERE NOT EXISTS (
    SELECT 1 FROM customers WHERE LOWER(email) = LOWER('rider.alice@example.com')
);


INSERT INTO customers (email, enabled, password, first_name, last_name)
SELECT 'rider.bob@example.com', TRUE, '{noop}rider123', 'Bob', 'Rider'
WHERE NOT EXISTS (
    SELECT 1 FROM customers WHERE LOWER(email) = LOWER('rider.bob@example.com')
);


INSERT INTO customers (email, enabled, password, first_name, last_name)
SELECT 'rider.carol@example.com', TRUE, '{noop}rider123', 'Carol', 'Rider'
WHERE NOT EXISTS (
    SELECT 1 FROM customers WHERE LOWER(email) = LOWER('rider.carol@example.com')
);


INSERT INTO authorities (email, authority)
SELECT customers.email, 'ROLE_RIDER'
FROM customers
WHERE LOWER(customers.email) IN (
        'rider.alice@example.com',
        'rider.bob@example.com',
        'rider.carol@example.com'
    )
  AND customers.password = '{noop}rider123'
  AND NOT EXISTS (
    SELECT 1 FROM authorities WHERE authorities.email = customers.email AND authority = 'ROLE_RIDER'
);


-- Binds each demo rider to its seeded login, once: a rider that already has a login, a login already bound to another
-- rider, or an account the seed did not create (see the top of this file) is left alone.
UPDATE riders
SET customer_id = (
    SELECT customers.id
    FROM customers
    WHERE LOWER(customers.email) = 'rider.alice@example.com'
      AND customers.password = '{noop}rider123'
)
WHERE name = 'Demo Rider Alice'
  AND customer_id IS NULL
  AND EXISTS (
    SELECT 1
    FROM customers
    WHERE LOWER(customers.email) = 'rider.alice@example.com'
      AND customers.password = '{noop}rider123'
)
  AND NOT EXISTS (
    SELECT 1
    FROM riders bound
    JOIN customers ON customers.id = bound.customer_id
    WHERE LOWER(customers.email) = 'rider.alice@example.com'
);


UPDATE riders
SET customer_id = (
    SELECT customers.id
    FROM customers
    WHERE LOWER(customers.email) = 'rider.bob@example.com'
      AND customers.password = '{noop}rider123'
)
WHERE name = 'Demo Rider Bob'
  AND customer_id IS NULL
  AND EXISTS (
    SELECT 1
    FROM customers
    WHERE LOWER(customers.email) = 'rider.bob@example.com'
      AND customers.password = '{noop}rider123'
)
  AND NOT EXISTS (
    SELECT 1
    FROM riders bound
    JOIN customers ON customers.id = bound.customer_id
    WHERE LOWER(customers.email) = 'rider.bob@example.com'
);


UPDATE riders
SET customer_id = (
    SELECT customers.id
    FROM customers
    WHERE LOWER(customers.email) = 'rider.carol@example.com'
      AND customers.password = '{noop}rider123'
)
WHERE name = 'Demo Rider Carol'
  AND customer_id IS NULL
  AND EXISTS (
    SELECT 1
    FROM customers
    WHERE LOWER(customers.email) = 'rider.carol@example.com'
      AND customers.password = '{noop}rider123'
)
  AND NOT EXISTS (
    SELECT 1
    FROM riders bound
    JOIN customers ON customers.id = bound.customer_id
    WHERE LOWER(customers.email) = 'rider.carol@example.com'
);
