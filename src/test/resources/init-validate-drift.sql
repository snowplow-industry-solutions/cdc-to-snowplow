-- A clean table — should produce one [OK] line.
CREATE TABLE public.clean_table (
    id        BIGINT PRIMARY KEY,
    name      TEXT NOT NULL
);
ALTER TABLE public.clean_table REPLICA IDENTITY FULL;

-- A table with a configured column that doesn't exist — [ERROR] missing-column.
-- Also has an extra column not in config — [WARN] unconfigured-but-present.
CREATE TABLE public.missing_and_extra (
    id           BIGINT PRIMARY KEY,
    email        TEXT NOT NULL,
    created_at   TIMESTAMPTZ NOT NULL
);
ALTER TABLE public.missing_and_extra REPLICA IDENTITY FULL;

-- A table with REPLICA IDENTITY default — [WARN] replica-identity.
-- And a transform-on-int — [ERROR] type-mismatch.
CREATE TABLE public.replica_and_type (
    id           BIGINT PRIMARY KEY,
    amount_cents INTEGER NOT NULL
);
-- REPLICA IDENTITY left at the default ('d')
