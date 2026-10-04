-- Auction module.
-- `auctions` holds the CURRENT state (one row per lot). Every bid runs in a transaction that
-- first does SELECT ... FOR UPDATE on this row, so bids on the same lot are serialized
-- while bids on different lots proceed in parallel.
CREATE TABLE auctions (
    id                    TEXT PRIMARY KEY,
    equipment_id          TEXT        NOT NULL REFERENCES equipment (id),
    starting_price_cents  BIGINT      NOT NULL CHECK (starting_price_cents > 0),
    reserve_price_cents   BIGINT      CHECK (reserve_price_cents > 0),
    deposit_cents         BIGINT      NOT NULL CHECK (deposit_cents >= 0),
    starts_at             TIMESTAMPTZ NOT NULL,
    ends_at               TIMESTAMPTZ NOT NULL,
    soft_close_seconds    INT         NOT NULL DEFAULT 120 CHECK (soft_close_seconds > 0),
    current_price_cents   BIGINT      NOT NULL,
    leader_id             TEXT,
    leader_max_cents      BIGINT,                 -- SECRET: never returned to non-leaders
    extensions            INT         NOT NULL DEFAULT 0,
    created_at            TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT ends_after_start CHECK (ends_at > starts_at),
    CONSTRAINT leader_consistent CHECK ((leader_id IS NULL) = (leader_max_cents IS NULL)),
    CONSTRAINT price_within_max CHECK (leader_max_cents IS NULL OR current_price_cents <= leader_max_cents)
);
CREATE INDEX auctions_equipment_idx ON auctions (equipment_id);
CREATE INDEX auctions_ends_at_idx ON auctions (ends_at);

-- Visible bid history (what everyone sees, including automatic proxy bids).
CREATE TABLE auction_bids (
    id            BIGSERIAL PRIMARY KEY,
    auction_id    TEXT        NOT NULL REFERENCES auctions (id),
    bidder_id     TEXT        NOT NULL,
    amount_cents  BIGINT      NOT NULL CHECK (amount_cents > 0),
    auto          BOOLEAN     NOT NULL,
    created_at    TIMESTAMPTZ NOT NULL
);
CREATE INDEX auction_bids_auction_idx ON auction_bids (auction_id, id);

-- Every submitted max, accepted or not (audit trail; Phase 3 hash-chains these events).
CREATE TABLE bid_submissions (
    id            BIGSERIAL PRIMARY KEY,
    auction_id    TEXT        NOT NULL REFERENCES auctions (id),
    bidder_id     TEXT        NOT NULL,
    max_cents     BIGINT      NOT NULL,
    outcome       TEXT        NOT NULL,           -- ACCEPTED | TOO_LOW | ENDED | ...
    created_at    TIMESTAMPTZ NOT NULL
);

-- Registration = refundable deposit hold. Phase 3 links this to the ledger and Stripe.
CREATE TABLE auction_registrations (
    auction_id    TEXT        NOT NULL REFERENCES auctions (id),
    bidder_id     TEXT        NOT NULL,
    deposit_cents BIGINT      NOT NULL,
    status        TEXT        NOT NULL DEFAULT 'HELD' CHECK (status IN ('HELD','RELEASED','APPLIED')),
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (auction_id, bidder_id)          -- registering twice is impossible (idempotent)
);
