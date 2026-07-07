-- Example seed for the cdc-service source database.
--
-- The bootstrap layer creates the database but NO tables, so the service's
-- startup preflight fails with "table not found" until the captured tables
-- exist with the right shape. Adapt this to the tables in your service module's
-- `tables_yaml`, then run (you need network reach to the DB — enable admin
-- access, see README "Admin DB access", or use the Cloud SQL Auth Proxy):
--
--   PGPASSWORD=... psql \
--     "host=<DB_IP> port=5432 dbname=orders_db user=cdc sslmode=require" \
--     -f examples/seed.sql
--
-- The two tables are shaped to make the transform story visible (mirrors the
-- local docker-compose scaffold demo, so both stay in sync):
--   * customers has a PII column (email) you can DROP from tables_yaml, and a
--     country_code (TEXT) you can give an `uppercase` transform.
--   * orders.status is seeded padded with whitespace, so a `trim` transform has
--     a visible effect downstream.
-- The default module `tables_yaml` captures orders only; add customers (with the
-- transforms) to capture both.

-- NOTE: the replication privilege is granted as a separate, cloud-specific step
-- (see README "Deploying on GCP/AWS") — Cloud SQL uses ALTER USER … WITH
-- REPLICATION, RDS uses GRANT rds_replication. It is intentionally NOT in this
-- file so the same seed runs cleanly on both.

CREATE TABLE IF NOT EXISTS public.customers (
    id           INTEGER PRIMARY KEY,
    email        TEXT,
    first_name   TEXT,
    last_name    TEXT,
    country_code TEXT,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);
-- FULL so UPDATE/DELETE carry a complete before-image (needed by column transforms).
ALTER TABLE public.customers REPLICA IDENTITY FULL;

CREATE TABLE IF NOT EXISTS public.orders (
    id          INTEGER PRIMARY KEY,
    customer_id INTEGER,
    status      TEXT,
    total       NUMERIC(10, 2)
);
ALTER TABLE public.orders REPLICA IDENTITY FULL;

-- Baseline rows. NOTE: with `snapshot_mode: never` (the default) these are NOT
-- emitted — only INSERT/UPDATE/DELETE after the replication slot exists produce
-- events. Test the pipeline with a *fresh* write, not these rows. status is
-- padded on purpose so a `trim` transform is visibly proven.
INSERT INTO public.customers (id, email, first_name, last_name, country_code) VALUES
    (1, 'ada@example.com',    'Ada',    'Lovelace', 'gb'),
    (2, 'grace@example.com',  'Grace',  'Hopper',   'us'),
    (3, 'edsger@example.com', 'Edsger', 'Dijkstra', 'nl')
ON CONFLICT (id) DO NOTHING;

INSERT INTO public.orders (id, customer_id, status, total) VALUES
    (1, 1, '  pending  ', 25.00),
    (2, 2, 'shipped',     99.99),
    (3, 1, '  pending  ', 12.50)
ON CONFLICT (id) DO NOTHING;
