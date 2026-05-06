-- pricing-service initial schema and seed data.
-- Owns the `prices` table inside `pricing_schema`. Pricing rules (weekend uplift,
-- holiday uplift, loyalty discount) are NOT stored here -- they are applied at
-- query time in service code so the platform can evolve rules without migrations.

CREATE TABLE prices (
    property_id  UUID         PRIMARY KEY,
    base_price   NUMERIC(10,2) NOT NULL CHECK (base_price >= 0),
    tax_rate     NUMERIC(6,4)  NOT NULL CHECK (tax_rate >= 0 AND tax_rate <= 1),
    currency     CHAR(3)       NOT NULL DEFAULT 'USD',
    season       VARCHAR(20)   NOT NULL DEFAULT 'STANDARD',
    created_at   TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT prices_season_chk CHECK (season IN ('STANDARD','PEAK','SHOULDER','OFF'))
);

-- ----------------------------------------------------------------------------
-- Seed: one row per property. Property UUIDs match property-service seed.
-- Base prices vary by tier; tax rate uses a placeholder 12% across the board.
-- ----------------------------------------------------------------------------

-- Austin
INSERT INTO prices (property_id, base_price, tax_rate, currency, season) VALUES
('11111111-1111-1111-1111-000000000001', 320.00, 0.1200, 'USD', 'PEAK'),
('11111111-1111-1111-1111-000000000002', 245.00, 0.1200, 'USD', 'STANDARD'),
('11111111-1111-1111-1111-000000000003', 180.00, 0.1200, 'USD', 'STANDARD'),
('11111111-1111-1111-1111-000000000004', 290.00, 0.1200, 'USD', 'PEAK');

-- Seattle
INSERT INTO prices (property_id, base_price, tax_rate, currency, season) VALUES
('11111111-1111-1111-1111-000000000005', 350.00, 0.1200, 'USD', 'PEAK'),
('11111111-1111-1111-1111-000000000006', 310.00, 0.1200, 'USD', 'STANDARD'),
('11111111-1111-1111-1111-000000000007', 275.00, 0.1200, 'USD', 'STANDARD'),
('11111111-1111-1111-1111-000000000008', 230.00, 0.1200, 'USD', 'SHOULDER');

-- Denver
INSERT INTO prices (property_id, base_price, tax_rate, currency, season) VALUES
('11111111-1111-1111-1111-000000000009', 295.00, 0.1200, 'USD', 'PEAK'),
('11111111-1111-1111-1111-000000000010', 260.00, 0.1200, 'USD', 'STANDARD'),
('11111111-1111-1111-1111-000000000011', 240.00, 0.1200, 'USD', 'STANDARD'),
('11111111-1111-1111-1111-000000000012', 215.00, 0.1200, 'USD', 'SHOULDER');

-- Portland
INSERT INTO prices (property_id, base_price, tax_rate, currency, season) VALUES
('11111111-1111-1111-1111-000000000013', 250.00, 0.1200, 'USD', 'STANDARD'),
('11111111-1111-1111-1111-000000000014', 220.00, 0.1200, 'USD', 'STANDARD'),
('11111111-1111-1111-1111-000000000015', 195.00, 0.1200, 'USD', 'SHOULDER'),
('11111111-1111-1111-1111-000000000016', 270.00, 0.1200, 'USD', 'STANDARD');

-- Nashville
INSERT INTO prices (property_id, base_price, tax_rate, currency, season) VALUES
('11111111-1111-1111-1111-000000000017', 380.00, 0.1200, 'USD', 'PEAK'),
('11111111-1111-1111-1111-000000000018', 285.00, 0.1200, 'USD', 'STANDARD'),
('11111111-1111-1111-1111-000000000019', 240.00, 0.1200, 'USD', 'STANDARD'),
('11111111-1111-1111-1111-000000000020', 210.00, 0.1200, 'USD', 'SHOULDER');
