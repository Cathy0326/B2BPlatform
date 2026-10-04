-- Catalog module: equipment listings.
-- Money is BIGINT cents (never FLOAT/DOUBLE). Rental "month" = 28 days (industry convention).
CREATE TABLE equipment (
    id                  TEXT PRIMARY KEY,
    title               TEXT        NOT NULL,
    category            TEXT        NOT NULL CHECK (category IN ('EXCAVATOR','BULLDOZER','WHEEL_LOADER','SKID_STEER','CRANE','BACKHOE')),
    make                TEXT        NOT NULL,
    model               TEXT        NOT NULL,
    year                INT         NOT NULL CHECK (year BETWEEN 1950 AND 2100),
    hours               INT         NOT NULL CHECK (hours >= 0),
    location            TEXT        NOT NULL,
    listing_type        TEXT        NOT NULL CHECK (listing_type IN ('SALE','RENT','BOTH')),
    sale_price_cents    BIGINT      CHECK (sale_price_cents > 0),
    daily_rate_cents    BIGINT      CHECK (daily_rate_cents > 0),
    weekly_rate_cents   BIGINT      CHECK (weekly_rate_cents > 0),
    monthly_rate_cents  BIGINT      CHECK (monthly_rate_cents > 0),
    description         TEXT        NOT NULL DEFAULT '',
    specs               JSONB       NOT NULL DEFAULT '[]'::jsonb,   -- ordered [{"name":..,"value":..}]
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),

    -- A listing's prices must match what it is listed for.
    CONSTRAINT sale_price_matches_type CHECK ((listing_type = 'RENT') = (sale_price_cents IS NULL)),
    CONSTRAINT rates_match_type CHECK (
        (listing_type = 'SALE') = (daily_rate_cents IS NULL AND weekly_rate_cents IS NULL AND monthly_rate_cents IS NULL)
    )
);

CREATE INDEX equipment_category_idx ON equipment (category);
