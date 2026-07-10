-- Manual seed for the scaffold demo. Run AFTER Postgres is up with the
-- replication-only init (see README "Scaffold demo" / `make seed`), e.g.:
--
--   docker compose exec -T postgres psql -U cdc -d orders_db < docker/seed.sql
--
-- The two tables are shaped to make the scaffold edit story obvious:
--   * customers carries a PII column (email) you DROP from the generated config, plus
--     country_code (TEXT) you add an `uppercase` transform to.
--   * orders.status (TEXT) is seeded padded with whitespace, so an added `trim`
--     transform has a visible effect downstream.
--
-- Both tables get REPLICA IDENTITY FULL so UPDATE/DELETE events carry the full
-- before-image rather than just the primary key.
--
-- Note: with snapshot_mode=never (the scaffold default), these seed rows are NOT
-- emitted — they exist as realistic content and as targets for the live UPDATE/DELETE
-- you run during the demo. The events you see in Micro are the changes you make after
-- the service is up.

CREATE TABLE IF NOT EXISTS public.customers (
    id           INTEGER PRIMARY KEY,
    email        TEXT,
    first_name   TEXT,
    last_name    TEXT,
    country_code TEXT,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);
ALTER TABLE public.customers REPLICA IDENTITY FULL;

CREATE TABLE IF NOT EXISTS public.orders (
    id          INTEGER PRIMARY KEY,
    customer_id INTEGER,
    status      TEXT,
    total       NUMERIC(10, 2)
);
ALTER TABLE public.orders REPLICA IDENTITY FULL;

INSERT INTO public.customers (id, email, first_name, last_name, country_code) VALUES
    (1, 'ada@example.com',    'Ada',    'Lovelace', 'gb'),
    (2, 'grace@example.com',  'Grace',  'Hopper',   'us'),
    (3, 'edsger@example.com', 'Edsger', 'Dijkstra', 'nl')
ON CONFLICT DO NOTHING;

INSERT INTO public.orders (id, customer_id, status, total) VALUES
    (1, 1, '  pending  ', 25.00),
    (2, 2, 'shipped',     99.99),
    (3, 1, '  pending  ', 12.50)
ON CONFLICT DO NOTHING;
