.PHONY: build image up demo demo-update demo-delete demo-all demo-replay events events-summary down logs

# Fat-JAR distribution artefact -> build/libs/cdc-service.jar. Standalone: NOT on the `up`
# path, since Jib builds the image from compiled classes and does not consume the fat JAR.
build:
	./gradlew shadowJar

# Docker image via Jib -> loads cdc-service:local into the local Docker daemon.
image:
	./gradlew jibDockerBuild

up: image
	docker compose up -d

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

logs:
	docker compose logs -f cdc-service
