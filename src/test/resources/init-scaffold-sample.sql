CREATE TYPE order_status_enum AS ENUM ('pending', 'shipped', 'delivered');

-- single-column PK
CREATE TABLE orders (
    id            integer PRIMARY KEY,
    customer_id   integer NOT NULL,
    status        order_status_enum NOT NULL,
    total         numeric(10,2),
    tags          text[],
    meta          jsonb,
    created_at    timestamptz NOT NULL
);

-- composite PK: order matters (tenant_id first)
CREATE TABLE line_items (
    tenant_id  integer NOT NULL,
    id         integer NOT NULL,
    sku        varchar(64) NOT NULL,
    PRIMARY KEY (tenant_id, id)
);

-- no PK, but a UNIQUE constraint on email
CREATE TABLE contacts (
    email      text NOT NULL,
    name       text,
    CONSTRAINT contacts_email_key UNIQUE (email)
);

-- no PK, no unique index at all
CREATE TABLE events_log (
    payload    jsonb,
    logged_at  timestamptz NOT NULL
);
