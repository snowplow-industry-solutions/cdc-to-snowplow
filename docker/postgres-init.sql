-- Runs as the postgres superuser on the orders_db database (POSTGRES_DB env var).
-- The 'cdc' user is created via POSTGRES_USER env var; we add REPLICATION here.
ALTER USER cdc WITH REPLICATION;

CREATE TABLE IF NOT EXISTS public.orders (
    id          INTEGER PRIMARY KEY,
    customer_id INTEGER,
    status      TEXT,
    total       NUMERIC(10, 2)
);

ALTER TABLE public.orders REPLICA IDENTITY FULL;

INSERT INTO public.orders (id, customer_id, status, total) VALUES
    (1, 100, 'pending', 25.00),
    (2, 101, 'shipped', 99.99),
    (3, 102, 'pending', 12.50)
ON CONFLICT DO NOTHING;
