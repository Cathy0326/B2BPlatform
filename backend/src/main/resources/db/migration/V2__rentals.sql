-- Rental module: bookings with database-enforced "no double booking".
--
-- daterange '[)' = half-open, exactly like the frontend: [pick-up day, return day).
-- The EXCLUDE constraint makes PostgreSQL itself reject two rows for the same equipment
-- whose periods overlap (&&). It is checked atomically inside the insert, so two
-- concurrent transactions can never both succeed - no application lock needed.
-- btree_gist lets a GiST index combine "=" on text with "&&" on ranges.
CREATE EXTENSION IF NOT EXISTS btree_gist;

CREATE TABLE bookings (
    id            BIGSERIAL PRIMARY KEY,
    equipment_id  TEXT        NOT NULL REFERENCES equipment (id),
    renter_id     TEXT        NOT NULL,
    period        DATERANGE   NOT NULL CHECK (NOT isempty(period) AND lower_inc(period) AND NOT upper_inc(period)),
    total_cents   BIGINT      NOT NULL CHECK (total_cents >= 0),
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT bookings_no_overlap EXCLUDE USING gist (equipment_id WITH =, period WITH &&)
);
