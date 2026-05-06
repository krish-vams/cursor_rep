-- Initialize TravelGraph Platform schemas.
-- This script runs once on first container start (placed in /docker-entrypoint-initdb.d/).
-- Each subgraph owns exactly one schema. No cross-schema foreign keys — entities
-- are joined at the federation layer via @key, not at the database layer.

CREATE SCHEMA IF NOT EXISTS property_schema;
CREATE SCHEMA IF NOT EXISTS pricing_schema;
CREATE SCHEMA IF NOT EXISTS booking_schema;
CREATE SCHEMA IF NOT EXISTS user_schema;
CREATE SCHEMA IF NOT EXISTS review_schema;

COMMENT ON SCHEMA property_schema IS 'Owned by property-service subgraph';
COMMENT ON SCHEMA pricing_schema  IS 'Owned by pricing-service subgraph';
COMMENT ON SCHEMA booking_schema  IS 'Owned by booking-service subgraph';
COMMENT ON SCHEMA user_schema     IS 'Owned by user-service subgraph';
COMMENT ON SCHEMA review_schema   IS 'Owned by review-service subgraph';
