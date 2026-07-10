.PHONY: build image db-up micro-up demo demo-update demo-delete demo-all demo-replay events events-summary down \
        scaffold-db seed demo-customer

# Fat-JAR distribution artefact -> build/libs/cdc-service.jar. Standalone: NOT on the `up`
# path, since Jib builds the image from compiled classes and does not consume the fat JAR.
build:
	./gradlew shadowJar

# Docker image via Jib -> loads cdc-service:local into the local Docker daemon.
image:
	./gradlew jibDockerBuild

# Boot Postgres with the default demo init (auto-creates the orders table) and wait for it.
# Host-run demo: replaces the postgres half of the old `up` target.
db-up:
	docker compose up -d postgres
	@echo "waiting for postgres..."
	@until docker compose exec -T postgres pg_isready -U cdc -d orders_db >/dev/null 2>&1; do sleep 1; done
	@echo "postgres ready — run 'make micro-up', then the host 'run' command (see README Demo)."

# Boot Snowplow Micro. SCHEMAS_DIR is overridable so the scaffold demo can point it at
# ./scaffold-out/schemas (e.g. `SCHEMAS_DIR=./scaffold-out/schemas make micro-up`).
micro-up:
	docker compose up -d snowplow-micro
	@echo "micro up on http://localhost:9090 (schemas: $${SCHEMAS_DIR:-./schemas})"

# Single INSERT — emits one event with op=c.
demo:
	docker compose exec postgres psql -U cdc -d orders_db -c \
	  "INSERT INTO public.orders (id, customer_id, status, total) VALUES ($$RANDOM, 999, 'pending', 42.42)"

# INSERT then UPDATE on a fresh row — emits op=c followed by op=u (with before+after maps,
# since orders has REPLICA IDENTITY FULL).
demo-update:
	@ID=$$RANDOM; \
	  docker compose exec postgres psql -U cdc -d orders_db -c \
	    "INSERT INTO public.orders (id, customer_id, status, total) VALUES ($$ID, 1, 'pending', 99.00)"; \
	  docker compose exec postgres psql -U cdc -d orders_db -c \
	    "UPDATE public.orders SET status='shipped' WHERE id=$$ID"

# INSERT then DELETE on a fresh row — emits op=c followed by op=d (delete carries before only,
# no after key).
demo-delete:
	@ID=$$RANDOM; \
	  docker compose exec postgres psql -U cdc -d orders_db -c \
	    "INSERT INTO public.orders (id, customer_id, status, total) VALUES ($$ID, 7, 'cancelled', 0.00)"; \
	  docker compose exec postgres psql -U cdc -d orders_db -c \
	    "DELETE FROM public.orders WHERE id=$$ID"

# Full c->u->d sequence on a single row id. Each statement runs in its own transaction so the
# events get distinct LSNs and txids — the deterministic eids would only collide if Debezium
# replayed the same WAL event twice (see demo-replay).
demo-all:
	@ID=$$RANDOM; \
	  echo "-->INSERT id=$$ID (op=c)"; \
	  docker compose exec postgres psql -U cdc -d orders_db -c \
	    "INSERT INTO public.orders (id, customer_id, status, total) VALUES ($$ID, 1, 'pending', 99.00)"; \
	  echo "-->UPDATE id=$$ID (op=u)"; \
	  docker compose exec postgres psql -U cdc -d orders_db -c \
	    "UPDATE public.orders SET status='shipped' WHERE id=$$ID"; \
	  echo "-->DELETE id=$$ID (op=d)"; \
	  docker compose exec postgres psql -U cdc -d orders_db -c \
	    "DELETE FROM public.orders WHERE id=$$ID"; \
	  echo ""; \
	  echo "Run 'make events-summary' to see the three events with op / eid / lsn."

# Demonstrates that PayloadAssembler stamps a replay-stable UUIDv5 eid: the same envelope
# (same connector / db / schema / table / op / lsn / txid / pk) yields the same eid on
# every delivery, which is what folds Debezium re-deliveries downstream in Snowplow.
#
# Why this isn't a live SIGKILL demo against the running cdc-service: pgoutput acks the
# replication slot synchronously with streaming (independent of offset.flush.interval.ms),
# so on restart Debezium logs "Last recorded offset is no longer available on the server"
# and resumes from the slot's current position — there's nothing left in the WAL to replay.
# The unit test below exercises the property directly, calling PayloadAssembler.assemble()
# twice on one envelope and asserting eid equality. It is the property under test.
demo-replay:
	@echo "Running PayloadAssemblerTest's idempotency case (the property under test):"
	@./gradlew --console=plain test \
	    --tests "com.snowplowanalytics.cdc.transform.PayloadAssemblerTest.assembling the same envelope twice yields the same eid on both payloads" \
	    --rerun-tasks 2>&1 \
	  | grep -E "PASSED|FAILED|tests completed" \
	  | head -5
	@echo
	@echo "Real eid from this stack (sample shape — UUIDv5, not v4):"
	@curl -s http://localhost:9090/micro/good \
	  | jq -r '.[0].event.event_id // "no events in Micro yet — run \"make demo\" first"'

# Raw enriched events from Snowplow Micro.
events:
	curl -s http://localhost:9090/micro/good | jq

# One row per event with the slice-4 fields surfaced: eid, op, table, lsn, status before/after.
# Tweak the jq if your Micro version returns a different shape — `.event.unstruct_event.data.data`
# is the per-table change payload (op / before / after); `.event.contexts.data[]` is the entity array.
events-summary:
	@curl -s http://localhost:9090/micro/good | jq '.[] | {                                  \
	  eid: .event.event_id,                                                                  \
	  op:  .event.unstruct_event.data.data.op,                                                \
	  table: ([.event.contexts.data[]? | select(.schema | contains("cdc_source")) | .data.table] | first), \
	  lsn:   ([.event.contexts.data[]? | select(.schema | contains("cdc_source")) | .data.lsn]   | first), \
	  before_status: (.event.unstruct_event.data.data.before.status // null),                 \
	  after_status:  (.event.unstruct_event.data.data.after.status  // null)                  \
	}'

down:
	docker compose down -v

# ---- Scaffold demo --------------------------------------------------------
# Showcases the `scaffold` subcommand end to end: shape a Postgres DB by hand,
# let scaffold generate starter config + Iglu schemas from the live schema, make
# a few edits, then stream live changes into Micro. Full walkthrough + the edit
# checklist live in README "Scaffold demo". Ordered: scaffold-db -> seed ->
# scaffold (host: java -jar … scaffold) -> edit scaffold-out/config.yaml ->
# micro-up (SCHEMAS_DIR=./scaffold-out/schemas) -> run (host) -> demo* -> events.

VENDOR       ?= com.example
SCAFFOLD_OUT ?= scaffold-out

# 1. Fresh Postgres booted with the replication-only init (no tables created).
scaffold-db:
	docker compose down -v
	PG_INIT=./docker/postgres-init-replication-only.sql docker compose up -d postgres
	@echo "waiting for postgres..."
	@until docker compose exec -T postgres pg_isready -U cdc -d orders_db >/dev/null 2>&1; do sleep 1; done
	@echo "postgres ready (no tables yet) — run 'make seed'."

# 2. Manually seed the customers + orders tables and sample rows.
seed:
	docker compose exec -T postgres psql -U cdc -d orders_db < docker/seed.sql
	@echo "seeded customers + orders — run the host 'scaffold' command (see README Scaffold demo)."

# Live INSERT then UPDATE on the seeded customers table. Shows the edited transform
# (country_code uppercased) and the dropped columns (email/created_at absent from the event).
demo-customer:
	@ID=$$RANDOM; \
	  echo "-->INSERT customer id=$$ID (op=c)"; \
	  docker compose exec postgres psql -U cdc -d orders_db -c \
	    "INSERT INTO public.customers (id, email, first_name, last_name, country_code) VALUES ($$ID, 'new@example.com', 'New', 'Customer', 'fr')"; \
	  echo "-->UPDATE customer id=$$ID country_code fr->de (op=u)"; \
	  docker compose exec postgres psql -U cdc -d orders_db -c \
	    "UPDATE public.customers SET country_code='de' WHERE id=$$ID"; \
	  echo ""; \
	  echo "Run 'make events' — country_code should be UPPERCASE and email/created_at absent."
