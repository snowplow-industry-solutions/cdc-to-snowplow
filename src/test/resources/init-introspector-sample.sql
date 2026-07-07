CREATE TYPE public.order_status_enum AS ENUM ('pending', 'shipped', 'delivered');

CREATE TABLE public.orders (
    id          INTEGER PRIMARY KEY,
    customer_id INTEGER NOT NULL,
    status      public.order_status_enum NOT NULL,
    total       NUMERIC(10, 2)
);
ALTER TABLE public.orders REPLICA IDENTITY FULL;

CREATE TABLE public.customers (
    id          INTEGER PRIMARY KEY,
    email       TEXT NOT NULL,
    nickname    TEXT
);
-- Leave customers at default replica identity (DEFAULT — PK only).
