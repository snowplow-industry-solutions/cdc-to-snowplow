-- ============================================================================
-- Hand-runnable demo walkthrough for the scaffold demo (customers + orders).
--
-- This is NOT meant to be run all at once. Open an interactive psql session and
-- paste ONE statement at a time, pausing after each so the audience can watch the
-- matching event appear in Snowplow Micro (http://localhost:9090/micro/good, or
-- `make events` / `make events-summary`). Each statement is a single line and runs
-- in its own transaction, so it gets a distinct LSN/txid and a distinct event_id.
--
-- Open the session with:
--   docker compose exec postgres psql -U cdc -d orders_db
--
-- Assumes the scaffold-demo seed (docker/seed.sql) and the edit checklist from the
-- README "Scaffold demo" are in place, i.e. the running config:
--   * trims orders.status
--   * uppercases customers.country_code
--   * drops customers.email from the emitted payload
-- The IDs below (orders 1001, customers 5001) are chosen not to collide with the
-- seed rows so you can track a single row's whole lifecycle.
-- ============================================================================


-- ── Part 1: orders — a single row's c → u → d lifecycle ─────────────────────

-- (c) INSERT. Status is padded with spaces on purpose; the emitted after.status
--     should arrive TRIMMED ("pending"), proving the trim transform.
INSERT INTO public.orders (id, customer_id, status, total) VALUES (1001, 1, '  pending  ', 42.42);

-- (u) UPDATE. Event carries before + after (orders has REPLICA IDENTITY FULL).
--     before.status = "pending", after.status = "shipped" (both trimmed).
UPDATE public.orders SET status = '  shipped  ', total = 50.00 WHERE id = 1001;

-- (d) DELETE. Event carries before only (no after); before holds the last row state.
DELETE FROM public.orders WHERE id = 1001;


-- ── Part 2: customers — uppercase transform + dropped PII column (email) ─────

-- (c) INSERT. country_code is lowercase "jp"; the emitted after.country_code should
--     arrive UPPERCASE ("JP"). email is set here but must NOT appear in the event
--     payload — it is not on the YAML whitelist. (created_at IS emitted — it stays
--     on the whitelist.)
INSERT INTO public.customers (id, email, first_name, last_name, country_code) VALUES (5001, 'secret@acme.com', 'Demo', 'User', 'jp');

-- (u) UPDATE a whitelisted column. before.country_code = "JP", after = "KR".
UPDATE public.customers SET country_code = 'kr' WHERE id = 5001;

-- (u) BONUS — UPDATE a column that is NOT emitted (email). Debezium still sees the
--     row change and emits an op=u event, but the whitelisted fields are unchanged,
--     so before/after look identical for the columns you actually send. Good moment
--     to explain that the whitelist controls payload shape, not whether an event fires.
UPDATE public.customers SET email = 'rotated@acme.com' WHERE id = 5001;

-- (d) DELETE. Event carries before only.
DELETE FROM public.customers WHERE id = 5001;
