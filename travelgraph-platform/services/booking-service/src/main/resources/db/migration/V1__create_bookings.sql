-- booking-service initial schema.
-- Owns the `bookings` table inside `booking_schema`. Cross-subgraph references
-- (propertyId, userId) are stored as opaque UUIDs -- joins happen at the
-- federation layer via @key, not at the database layer.

CREATE TABLE bookings (
    id               UUID         PRIMARY KEY,
    property_id      UUID         NOT NULL,
    user_id          UUID         NOT NULL,
    check_in         DATE         NOT NULL,
    check_out        DATE         NOT NULL,
    status           VARCHAR(20)  NOT NULL,
    total_amount     NUMERIC(10,2) NOT NULL CHECK (total_amount >= 0),
    currency         CHAR(3)      NOT NULL DEFAULT 'USD',
    idempotency_key  VARCHAR(128) NOT NULL,
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT bookings_status_chk    CHECK (status IN ('CONFIRMED','CANCELLED','PENDING')),
    CONSTRAINT bookings_dates_chk     CHECK (check_out > check_in),
    CONSTRAINT bookings_idem_unique   UNIQUE (idempotency_key)
);

CREATE INDEX idx_bookings_user      ON bookings (user_id);
CREATE INDEX idx_bookings_property  ON bookings (property_id);
CREATE INDEX idx_bookings_dates     ON bookings (property_id, check_in, check_out);
