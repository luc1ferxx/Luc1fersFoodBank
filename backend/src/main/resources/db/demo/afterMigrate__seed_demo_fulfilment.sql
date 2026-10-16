-- DEMO ONLY. Seed data for the fulfilment flow, loaded only when FLYWAY_LOCATIONS includes classpath:db/demo
-- (local/demo runs, the h2 profile and tests). Never ship it to production: the merchant logins below use well-known
-- plain-text ({noop}) passwords. Idempotent, like afterMigrate__seed_demo_data.sql, which Flyway runs before this
-- file (callbacks of one event run in name order) so the demo restaurants already exist.
--
-- One merchant login (ROLE_MERCHANT) per demo restaurant, password merchant123:
--   merchant.burgerking@laifood.com -> Burger King
--   merchant.sgdtofu@laifood.com    -> SGD Tofu House
--   merchant.fashionwok@laifood.com -> Fashion Wok
-- and three simulated riders for the dispatch module (at the end of this file).


INSERT INTO customers (email, enabled, password, first_name, last_name)
SELECT 'merchant.burgerking@laifood.com', TRUE, '{noop}merchant123', 'Burger King', 'Merchant'
WHERE NOT EXISTS (
    SELECT 1 FROM customers WHERE LOWER(email) = LOWER('merchant.burgerking@laifood.com')
);


INSERT INTO customers (email, enabled, password, first_name, last_name)
SELECT 'merchant.sgdtofu@laifood.com', TRUE, '{noop}merchant123', 'SGD Tofu House', 'Merchant'
WHERE NOT EXISTS (
    SELECT 1 FROM customers WHERE LOWER(email) = LOWER('merchant.sgdtofu@laifood.com')
);


INSERT INTO customers (email, enabled, password, first_name, last_name)
SELECT 'merchant.fashionwok@laifood.com', TRUE, '{noop}merchant123', 'Fashion Wok', 'Merchant'
WHERE NOT EXISTS (
    SELECT 1 FROM customers WHERE LOWER(email) = LOWER('merchant.fashionwok@laifood.com')
);


INSERT INTO authorities (email, authority)
SELECT customers.email, 'ROLE_MERCHANT'
FROM customers
WHERE LOWER(customers.email) IN (
        'merchant.burgerking@laifood.com',
        'merchant.sgdtofu@laifood.com',
        'merchant.fashionwok@laifood.com'
    )
  AND NOT EXISTS (
    SELECT 1 FROM authorities WHERE authorities.email = customers.email AND authority = 'ROLE_MERCHANT'
);


INSERT INTO merchant_accounts (customer_id, restaurant_id, created_at)
SELECT customers.id, restaurants.id, CURRENT_TIMESTAMP
FROM customers, restaurants
WHERE LOWER(customers.email) = LOWER('merchant.burgerking@laifood.com')
  AND restaurants.name = 'Burger King'
  AND NOT EXISTS (
    SELECT 1 FROM merchant_accounts WHERE merchant_accounts.customer_id = customers.id
);


INSERT INTO merchant_accounts (customer_id, restaurant_id, created_at)
SELECT customers.id, restaurants.id, CURRENT_TIMESTAMP
FROM customers, restaurants
WHERE LOWER(customers.email) = LOWER('merchant.sgdtofu@laifood.com')
  AND restaurants.name = 'SGD Tofu House'
  AND NOT EXISTS (
    SELECT 1 FROM merchant_accounts WHERE merchant_accounts.customer_id = customers.id
);


INSERT INTO merchant_accounts (customer_id, restaurant_id, created_at)
SELECT customers.id, restaurants.id, CURRENT_TIMESTAMP
FROM customers, restaurants
WHERE LOWER(customers.email) = LOWER('merchant.fashionwok@laifood.com')
  AND restaurants.name = 'Fashion Wok'
  AND NOT EXISTS (
    SELECT 1 FROM merchant_accounts WHERE merchant_accounts.customer_id = customers.id
);


-- Simulated riders for the dispatch module. The demo flow moves them with the ADMIN endpoints
-- POST /admin/deliveries/{id}/picked-up and /delivered.
INSERT INTO riders (name, phone, active, active_deliveries)
SELECT 'Demo Rider Alice', '(408) 555-0101', TRUE, 0
WHERE NOT EXISTS (SELECT 1 FROM riders WHERE name = 'Demo Rider Alice');


INSERT INTO riders (name, phone, active, active_deliveries)
SELECT 'Demo Rider Bob', '(408) 555-0102', TRUE, 0
WHERE NOT EXISTS (SELECT 1 FROM riders WHERE name = 'Demo Rider Bob');


INSERT INTO riders (name, phone, active, active_deliveries)
SELECT 'Demo Rider Carol', '(408) 555-0103', TRUE, 0
WHERE NOT EXISTS (SELECT 1 FROM riders WHERE name = 'Demo Rider Carol');
