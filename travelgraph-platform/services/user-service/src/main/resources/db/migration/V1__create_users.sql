-- user-service initial schema and seed data.
-- Owns the `users` table inside `user_schema`. The `saved_property_ids` column is
-- a Postgres uuid[] array since the size is bounded and reads are always for the
-- whole list -- a join table would be overkill.

CREATE TABLE users (
    id                   UUID         PRIMARY KEY,
    name                 VARCHAR(200) NOT NULL,
    email                VARCHAR(320) NOT NULL UNIQUE,
    loyalty_status       VARCHAR(20)  NOT NULL,
    preferred_currency   CHAR(3)      NOT NULL DEFAULT 'USD',
    saved_property_ids   UUID[]       NOT NULL DEFAULT ARRAY[]::UUID[],
    created_at           TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at           TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT users_loyalty_chk CHECK (loyalty_status IN ('BRONZE','SILVER','GOLD','PLATINUM'))
);

CREATE INDEX idx_users_loyalty ON users (loyalty_status);

-- ----------------------------------------------------------------------------
-- Seed: 10 users with mixed loyalty tiers. UUIDs use the 33333333- prefix so
-- they are visually distinct from property (11111111-) and other UUIDs.
-- saved_property_ids reference the 20 property UUIDs from property-service.
-- ----------------------------------------------------------------------------

INSERT INTO users (id, name, email, loyalty_status, preferred_currency, saved_property_ids) VALUES
('33333333-3333-3333-3333-000000000001', 'Avery Patel',     'avery.patel@example.com',   'PLATINUM', 'USD', ARRAY['11111111-1111-1111-1111-000000000001','11111111-1111-1111-1111-000000000005','11111111-1111-1111-1111-000000000017']::uuid[]),
('33333333-3333-3333-3333-000000000002', 'Jordan Kim',      'jordan.kim@example.com',    'GOLD',     'USD', ARRAY['11111111-1111-1111-1111-000000000004','11111111-1111-1111-1111-000000000010']::uuid[]),
('33333333-3333-3333-3333-000000000003', 'Sam Rivera',      'sam.rivera@example.com',    'GOLD',     'USD', ARRAY['11111111-1111-1111-1111-000000000007']::uuid[]),
('33333333-3333-3333-3333-000000000004', 'Riley Chen',      'riley.chen@example.com',    'SILVER',   'USD', ARRAY['11111111-1111-1111-1111-000000000003','11111111-1111-1111-1111-000000000016']::uuid[]),
('33333333-3333-3333-3333-000000000005', 'Morgan Davis',    'morgan.davis@example.com',  'SILVER',   'USD', ARRAY[]::uuid[]),
('33333333-3333-3333-3333-000000000006', 'Casey Nguyen',    'casey.nguyen@example.com',  'SILVER',   'EUR', ARRAY['11111111-1111-1111-1111-000000000002']::uuid[]),
('33333333-3333-3333-3333-000000000007', 'Taylor Brooks',   'taylor.brooks@example.com', 'BRONZE',   'USD', ARRAY['11111111-1111-1111-1111-000000000013','11111111-1111-1111-1111-000000000014']::uuid[]),
('33333333-3333-3333-3333-000000000008', 'Jamie Singh',     'jamie.singh@example.com',   'BRONZE',   'GBP', ARRAY[]::uuid[]),
('33333333-3333-3333-3333-000000000009', 'Drew Martinez',   'drew.martinez@example.com', 'BRONZE',   'USD', ARRAY['11111111-1111-1111-1111-000000000020']::uuid[]),
('33333333-3333-3333-3333-000000000010', 'Quinn Yamamoto',  'quinn.yamamoto@example.com','PLATINUM', 'JPY', ARRAY['11111111-1111-1111-1111-000000000006','11111111-1111-1111-1111-000000000018','11111111-1111-1111-1111-000000000009']::uuid[]);
