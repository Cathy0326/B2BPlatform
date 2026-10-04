-- ============================================================================
-- Phase 3: double-entry ledger, payments, idempotency, webhooks, escrow, audit.
-- ============================================================================

-- ---------- Ledger ----------------------------------------------------------
-- Rules enforced BY THE DATABASE (not just by Java):
--   1. every line is either a debit or a credit, never both, never negative
--   2. every journal entry balances: sum(debits) = sum(credits)   (checked at COMMIT)
--   3. journal tables are append-only: no UPDATE / DELETE / TRUNCATE
--   4. the same business event cannot be posted twice: UNIQUE (kind, reference)
-- There is NO balance column anywhere. A balance is always SUM(lines).

CREATE TABLE ledger_accounts (
    code        TEXT PRIMARY KEY,              -- e.g. platform_cash, escrow:deal-au-2002, seller_payable:seller-17
    type        TEXT NOT NULL CHECK (type IN ('ASSET','LIABILITY','REVENUE','EXPENSE','EQUITY')),
    name        TEXT NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE journal_entries (
    id          BIGSERIAL PRIMARY KEY,
    kind        TEXT NOT NULL,                 -- DEPOSIT_CAPTURED, BALANCE_RECEIVED, ESCROW_RELEASED, SELLER_PAYOUT
    reference   TEXT NOT NULL,                 -- business id, e.g. the deal id
    description TEXT NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT journal_entry_once UNIQUE (kind, reference)
);

CREATE TABLE journal_lines (
    id            BIGSERIAL PRIMARY KEY,
    entry_id      BIGINT NOT NULL REFERENCES journal_entries (id),
    account_code  TEXT   NOT NULL REFERENCES ledger_accounts (code),
    debit_cents   BIGINT NOT NULL DEFAULT 0 CHECK (debit_cents >= 0),
    credit_cents  BIGINT NOT NULL DEFAULT 0 CHECK (credit_cents >= 0),
    CONSTRAINT one_side_only CHECK ((debit_cents = 0) <> (credit_cents = 0))
);
CREATE INDEX journal_lines_entry_idx ON journal_lines (entry_id);
CREATE INDEX journal_lines_account_idx ON journal_lines (account_code);

CREATE FUNCTION check_journal_entry_balanced() RETURNS trigger AS $$
DECLARE d BIGINT; c BIGINT;
BEGIN
    SELECT COALESCE(SUM(debit_cents), 0), COALESCE(SUM(credit_cents), 0) INTO d, c
    FROM journal_lines WHERE entry_id = NEW.entry_id;
    IF d <> c THEN
        RAISE EXCEPTION 'journal entry % is unbalanced: debits % <> credits %', NEW.entry_id, d, c
            USING ERRCODE = 'check_violation';
    END IF;
    RETURN NULL;
END $$ LANGUAGE plpgsql;

-- DEFERRABLE INITIALLY DEFERRED: runs at COMMIT, after all lines of the entry are inserted.
CREATE CONSTRAINT TRIGGER journal_entry_balanced
    AFTER INSERT ON journal_lines
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION check_journal_entry_balanced();

CREATE FUNCTION forbid_mutation() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION '% is append-only', TG_TABLE_NAME USING ERRCODE = 'insufficient_privilege';
END $$ LANGUAGE plpgsql;

CREATE TRIGGER journal_entries_append_only BEFORE UPDATE OR DELETE ON journal_entries
    FOR EACH ROW EXECUTE FUNCTION forbid_mutation();
CREATE TRIGGER journal_lines_append_only BEFORE UPDATE OR DELETE ON journal_lines
    FOR EACH ROW EXECUTE FUNCTION forbid_mutation();
CREATE TRIGGER journal_entries_no_truncate BEFORE TRUNCATE ON journal_entries
    FOR EACH STATEMENT EXECUTE FUNCTION forbid_mutation();
CREATE TRIGGER journal_lines_no_truncate BEFORE TRUNCATE ON journal_lines
    FOR EACH STATEMENT EXECUTE FUNCTION forbid_mutation();

INSERT INTO ledger_accounts (code, type, name) VALUES
    ('platform_cash',    'ASSET',   'Platform bank account (funds received from payment processor)'),
    ('platform_revenue', 'REVENUE', 'Marketplace fees earned');

-- ---------- Sellers ---------------------------------------------------------
ALTER TABLE equipment ADD COLUMN seller_id TEXT NOT NULL DEFAULT 'quipmarket-consignment';

-- ---------- Payments --------------------------------------------------------
CREATE TABLE payments (
    id             TEXT PRIMARY KEY,                    -- our id (pay_...)
    provider       TEXT NOT NULL CHECK (provider IN ('SIMULATED','STRIPE')),
    provider_ref   TEXT UNIQUE,                         -- Stripe PaymentIntent id (pi_...)
    purpose        TEXT NOT NULL CHECK (purpose IN ('DEPOSIT','BALANCE')),
    auction_id     TEXT NOT NULL,
    payer_id       TEXT NOT NULL,
    amount_cents   BIGINT NOT NULL CHECK (amount_cents > 0),
    currency       TEXT NOT NULL DEFAULT 'usd',
    status         TEXT NOT NULL CHECK (status IN ('PROCESSING','REQUIRES_ACTION','AUTHORIZED','CAPTURED','CANCELED','FAILED')),
    -- PROCESSING = row written, provider not yet answered. We always record intent BEFORE calling the provider.
    client_secret  TEXT,                                -- only for REQUIRES_ACTION (3-D Secure) on the payer's device
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX payments_auction_idx ON payments (auction_id);

-- Registration now points at the deposit authorization. PENDING = waiting for the card authorization.
ALTER TABLE auction_registrations ADD COLUMN payment_id TEXT REFERENCES payments (id);
ALTER TABLE auction_registrations DROP CONSTRAINT auction_registrations_status_check;
ALTER TABLE auction_registrations ADD CONSTRAINT auction_registrations_status_check
    CHECK (status IN ('PENDING','HELD','RELEASED','APPLIED'));

-- ---------- Idempotency -----------------------------------------------------
-- One row per (caller, Idempotency-Key). The request hash detects a key reused for a DIFFERENT request.
CREATE TABLE idempotency_keys (
    user_id       TEXT NOT NULL,
    key           TEXT NOT NULL,
    operation     TEXT NOT NULL,
    request_hash  TEXT NOT NULL,
    status        TEXT NOT NULL CHECK (status IN ('IN_PROGRESS','COMPLETED')),
    response      TEXT,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (user_id, key)
);

-- ---------- Webhooks --------------------------------------------------------
-- Providers deliver AT LEAST once. The primary key makes processing exactly-once.
CREATE TABLE webhook_events (
    event_id     TEXT PRIMARY KEY,
    provider     TEXT NOT NULL,
    type         TEXT NOT NULL,
    received_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- ---------- Escrow ----------------------------------------------------------
ALTER TABLE auctions ADD COLUMN settled_at TIMESTAMPTZ;

CREATE TABLE escrow_deals (
    id                  TEXT PRIMARY KEY,               -- deal-<auction id>
    auction_id          TEXT NOT NULL UNIQUE,
    equipment_id        TEXT NOT NULL,
    buyer_id            TEXT NOT NULL,
    seller_id           TEXT NOT NULL,
    hammer_cents        BIGINT NOT NULL CHECK (hammer_cents > 0),
    deposit_cents       BIGINT NOT NULL CHECK (deposit_cents >= 0),
    fee_cents           BIGINT NOT NULL CHECK (fee_cents >= 0),
    deposit_payment_id  TEXT REFERENCES payments (id),
    balance_payment_id  TEXT REFERENCES payments (id),
    state               TEXT NOT NULL CHECK (state IN ('AWAITING_DEPOSIT_CAPTURE','AWAITING_BALANCE','FUNDED','RELEASED','PAID_OUT')),
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT deposit_not_above_hammer CHECK (deposit_cents <= hammer_cents),
    CONSTRAINT fee_not_above_hammer CHECK (fee_cents <= hammer_cents)
);
CREATE INDEX escrow_deals_buyer_idx ON escrow_deals (buyer_id);

-- ---------- Audit trail (tamper-evident hash chain) -------------------------
-- hash = SHA-256(prev_hash | event_type | subject | actor | created_at | payload)
-- payload is TEXT, not JSONB: JSONB re-orders keys and drops whitespace, so the exact
-- bytes that were hashed would be lost and verification would fail.
CREATE TABLE audit_log (
    seq         BIGSERIAL PRIMARY KEY,
    event_type  TEXT NOT NULL,
    subject     TEXT NOT NULL,
    actor       TEXT NOT NULL,
    payload     TEXT NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL,
    prev_hash   TEXT NOT NULL,
    hash        TEXT NOT NULL UNIQUE
);
CREATE TRIGGER audit_log_append_only BEFORE UPDATE OR DELETE ON audit_log
    FOR EACH ROW EXECUTE FUNCTION forbid_mutation();
CREATE TRIGGER audit_log_no_truncate BEFORE TRUNCATE ON audit_log
    FOR EACH STATEMENT EXECUTE FUNCTION forbid_mutation();
