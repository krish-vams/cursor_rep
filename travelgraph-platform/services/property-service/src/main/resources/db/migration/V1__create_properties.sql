-- property-service initial schema and seed data.
-- Owns the `properties` table inside `property_schema`.

CREATE TABLE properties (
    id           UUID PRIMARY KEY,
    name         VARCHAR(200)  NOT NULL,
    description  TEXT          NOT NULL,
    location     VARCHAR(300)  NOT NULL,
    city         VARCHAR(100)  NOT NULL,
    country      VARCHAR(100)  NOT NULL,
    rating       REAL          NOT NULL CHECK (rating >= 0 AND rating <= 5),
    amenities    JSONB         NOT NULL DEFAULT '[]'::jsonb,
    created_at   TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ   NOT NULL DEFAULT now()
);

CREATE INDEX idx_properties_city    ON properties (city);
CREATE INDEX idx_properties_country ON properties (country);
CREATE INDEX idx_properties_rating  ON properties (rating);

-- ----------------------------------------------------------------------------
-- Seed: 20 properties across 5 US cities (4 per city). UUIDs are deterministic
-- so pricing-service and booking-service can reference them in their seed data.
-- ----------------------------------------------------------------------------

-- Austin, TX
INSERT INTO properties (id, name, description, location, city, country, rating, amenities) VALUES
('11111111-1111-1111-1111-000000000001', 'The Driskill',         'A historic 1886 hotel anchoring downtown Austin.',                          '604 Brazos Street',         'Austin', 'United States', 4.6, '["wifi","pool","spa","restaurant","bar","gym"]'),
('11111111-1111-1111-1111-000000000002', 'Hotel San Jose',       'A converted 1930s motor lodge on South Congress.',                          '1316 South Congress Ave',   'Austin', 'United States', 4.4, '["wifi","pool","bar","pet-friendly","courtyard"]'),
('11111111-1111-1111-1111-000000000003', 'Austin Motel',         'Iconic neon-sign motel with a kidney-shaped pool.',                         '1220 South Congress Ave',   'Austin', 'United States', 4.3, '["wifi","pool","cafe","pet-friendly"]'),
('11111111-1111-1111-1111-000000000004', 'South Congress Hotel', 'Boutique design hotel on South Congress with rooftop dining.',              '1603 South Congress Ave',   'Austin', 'United States', 4.5, '["wifi","pool","restaurant","bar","gym","spa"]');

-- Seattle, WA
INSERT INTO properties (id, name, description, location, city, country, rating, amenities) VALUES
('11111111-1111-1111-1111-000000000005', 'Fairmont Olympic Hotel', 'Italian Renaissance landmark hotel in the heart of downtown Seattle.',     '411 University Street',     'Seattle', 'United States', 4.7, '["wifi","pool","spa","restaurant","bar","gym","concierge"]'),
('11111111-1111-1111-1111-000000000006', 'The Edgewater',          'The only over-water hotel on Elliott Bay.',                                '2411 Alaskan Way Pier 67',  'Seattle', 'United States', 4.5, '["wifi","restaurant","bar","gym","fireplace","waterfront"]'),
('11111111-1111-1111-1111-000000000007', 'Thompson Seattle',       'Modern boutique tower with views of Puget Sound.',                         '110 Stewart Street',        'Seattle', 'United States', 4.4, '["wifi","gym","restaurant","bar","rooftop"]'),
('11111111-1111-1111-1111-000000000008', 'Hotel Theodore',         'Bookish boutique hotel near Pike Place with curated local art.',           '1531 7th Avenue',           'Seattle', 'United States', 4.3, '["wifi","gym","restaurant","bar","library"]');

-- Denver, CO
INSERT INTO properties (id, name, description, location, city, country, rating, amenities) VALUES
('11111111-1111-1111-1111-000000000009', 'Brown Palace Hotel',     'Triangular 1892 luxury hotel with afternoon tea tradition.',               '321 17th Street',           'Denver', 'United States', 4.6, '["wifi","spa","restaurant","bar","gym","afternoon-tea"]'),
('11111111-1111-1111-1111-000000000010', 'The Crawford Hotel',     'Boutique hotel inside the historic Denver Union Station.',                 '1701 Wynkoop Street',       'Denver', 'United States', 4.5, '["wifi","gym","restaurant","bar","train-access"]'),
('11111111-1111-1111-1111-000000000011', 'Halcyon Cherry Creek',   'Modern lifestyle hotel in the upscale Cherry Creek neighborhood.',         '245 Columbine Street',      'Denver', 'United States', 4.4, '["wifi","pool","gym","rooftop","restaurant","bar","spa"]'),
('11111111-1111-1111-1111-000000000012', 'The Maven Hotel',        'Art-forward boutique hotel in Dairy Block.',                               '1850 Wazee Street',         'Denver', 'United States', 4.3, '["wifi","gym","restaurant","bar","art-gallery"]');

-- Portland, OR
INSERT INTO properties (id, name, description, location, city, country, rating, amenities) VALUES
('11111111-1111-1111-1111-000000000013', 'The Heathman Hotel',     'Historic hotel with a literary heritage and 24-hour library.',             '1001 SW Broadway',          'Portland', 'United States', 4.4, '["wifi","library","restaurant","bar","gym","tea-service"]'),
('11111111-1111-1111-1111-000000000014', 'Sentinel Hotel',         'Two historic buildings combined into one boutique hotel.',                 '614 SW 11th Avenue',        'Portland', 'United States', 4.3, '["wifi","gym","restaurant","bar","pet-friendly"]'),
('11111111-1111-1111-1111-000000000015', 'Hotel Lucia',            'Modern art-filled boutique hotel downtown.',                               '400 SW Broadway',           'Portland', 'United States', 4.2, '["wifi","gym","restaurant","art-collection","bicycles"]'),
('11111111-1111-1111-1111-000000000016', 'Kimpton RiverPlace',     'Waterfront boutique hotel on the Willamette River.',                       '1510 SW Harbor Way',        'Portland', 'United States', 4.5, '["wifi","gym","restaurant","bar","pet-friendly","waterfront"]');

-- Nashville, TN
INSERT INTO properties (id, name, description, location, city, country, rating, amenities) VALUES
('11111111-1111-1111-1111-000000000017', 'The Hermitage Hotel',    'AAA Five-Diamond Beaux-Arts hotel from 1910.',                             '231 6th Avenue North',      'Nashville', 'United States', 4.8, '["wifi","spa","restaurant","bar","gym","concierge","pet-friendly"]'),
('11111111-1111-1111-1111-000000000018', 'Union Station Hotel',    'Romanesque Revival hotel inside Nashville''s former train station.',       '1001 Broadway',             'Nashville', 'United States', 4.4, '["wifi","gym","restaurant","bar","historic-architecture"]'),
('11111111-1111-1111-1111-000000000019', 'Noelle Nashville',       'Boutique hotel in a 1930 Art Deco building.',                              '200 4th Avenue North',      'Nashville', 'United States', 4.3, '["wifi","gym","restaurant","bar","rooftop","cafe"]'),
('11111111-1111-1111-1111-000000000020', 'Bobby Hotel',            'Music-row-adjacent boutique with a vintage tour bus on the rooftop.',      '230 4th Avenue North',      'Nashville', 'United States', 4.2, '["wifi","pool","gym","rooftop-bar","restaurant"]');
