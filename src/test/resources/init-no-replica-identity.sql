ALTER USER cdc WITH REPLICATION;

CREATE TABLE public.orders (
    id          INTEGER PRIMARY KEY,
    customer_id INTEGER,
    status      TEXT,
    total       NUMERIC(10, 2)
);
ALTER TABLE public.orders REPLICA IDENTITY FULL;

CREATE TABLE public.customers (
    id          INTEGER PRIMARY KEY,
    email       TEXT,
    full_name   TEXT
);
-- INTENTIONALLY OMITTED: ALTER TABLE public.customers REPLICA IDENTITY FULL;
-- The Slice 4 ReplicaIdentityWarningTest asserts that EngineRunner emits a WARN
-- for this table on startup.
