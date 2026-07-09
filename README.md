# cdc-to-snowplow

A prototype service that captures row-level changes from Postgres (via Debezium) and emits them as Snowplow self-describing events. Single binary, three subcommands: `run`, `scaffold`, `validate`.

This is a **V0.1 incubator prototype** - it streams changes and feeds Snowplow's tracking-plan model; it is not a managed connector. See [Operating notes](#operating-notes) for the production gaps you should know about before relying on it.

## Quickstart

You do not need to build from source to run the service. Grab a release artifact and go.

**Option A - fat JAR** (needs JDK 21, nothing else):

```bash
# Download cdc-service.jar from the latest GitHub Release, then:
java -jar cdc-service.jar --help
java -jar cdc-service.jar run --config config.yaml
```

**Option B - Docker image** (no JDK needed):

```bash
docker pull ghcr.io/snowplow-industry-solutions/cdc-to-snowplow:0.1.0
docker run --rm \
  -v "$PWD/config.yaml:/etc/cdc-service/config.yaml:ro" \
  -v cdc-offsets:/var/lib/cdc \
  ghcr.io/snowplow-industry-solutions/cdc-to-snowplow:0.1.0    # default args: run --config /etc/cdc-service/config.yaml
```

> Prefer an offline install? Download `jib-image.tar` from the same GitHub Release, then `docker load < jib-image.tar` - it loads the same image locally, tagged `cdc-service:local`.

Don't have a config yet? Generate one with the [scaffold](#scaffold---generate-starter-config--iglu-schemas) subcommand, or copy the annotated [`examples/config.yaml`](examples/config.yaml). Prefer to build it yourself? See [Building from source](#building-from-source).

## Prerequisites

- **Fat JAR:** JDK 21.
- **Docker image + demo:** Docker and Docker Compose, with a running Docker daemon.
- **Postgres source:** a database with logical replication configured. See [Replication & Postgres setup](#replication--postgres-setup).

## Subcommands

Three subcommands, runnable from the fat JAR or the Docker image.

### `run` - stream changes

```bash
# Running via Fat JAR
java -jar cdc-service.jar run --config examples/config.yaml

# Running via Docker (mount the config and a writable offset dir)
docker run --rm \
  -v "$PWD/examples/config.yaml:/etc/cdc-service/config.yaml:ro" \
  -v cdc-offsets:/var/lib/cdc \
  ghcr.io/snowplow-industry-solutions/cdc-to-snowplow:0.1.0    # default args: run --config /etc/cdc-service/config.yaml
```

**Sourcing config from an environment variable (cloud deployments)**

Instead of `--config <file>`, pass `--config-env <VARNAME>` to load the entire YAML
config *body* from an environment variable — handy on platforms where mounting a file
is awkward (Cloud Run, ECS Fargate). The two forms are mutually exclusive.

```bash
export CDC_CONFIG="$(cat config.yaml)"   # the var holds the YAML body, not a path
java -jar cdc-service.jar run --config-env CDC_CONFIG
```

`${ENV_VAR}` interpolation still applies to the body, so secrets stay in their own
env vars (e.g. `POSTGRES_PASSWORD`). `--config <file>` remains the default for local use.

### `scaffold` - generate starter config + Iglu schemas

```bash
# Running via Fat JAR
java -jar cdc-service.jar scaffold \
  --connection postgres://cdc@localhost:5432/orders_db \
  --vendor com.example.cdc \
  --tables public.orders,public.customers \
  --output ./scaffold-out          # PGPASSWORD env var supplies the password

# Running via Docker (mount an output dir)
docker run --rm -e PGPASSWORD \
  -v "$PWD/scaffold-out:/out" \
  ghcr.io/snowplow-industry-solutions/cdc-to-snowplow:0.1.0 scaffold \
  --connection postgres://cdc@host.docker.internal:5432/orders_db \
  --vendor com.example.cdc --tables public.orders --output /out
```

`scaffold` reads the live database schema and writes a starter `config.yaml` plus per-table Iglu JSON schemas. The output is a **starting point** - generated schemas have no business descriptions or constraints and must be reviewed before use. It writes to a fresh directory and never overwrites existing files; to update, re-run against a new path and diff manually.

### `validate` - pre-deploy / CI drift check

```bash
# Running via Fat JAR
java -jar cdc-service.jar validate --config examples/config.yaml

# Running via Docker
docker run --rm \
  -v "$PWD/examples/config.yaml:/etc/cdc-service/config.yaml:ro" \
  ghcr.io/snowplow-industry-solutions/cdc-to-snowplow:0.1.0 validate --config /etc/cdc-service/config.yaml
```

Exit codes: `0` clean, `1` drift errors detected, `2` the command could not run (bad config / DB unreachable).

## Configuration

One YAML file. Secrets are injected via `${ENV_VAR}` interpolation, so credentials are never committed. Top-level sections:

```
service.*        app id, global service settings
source.*         Postgres connection (hostname/port/db/user/password)
debezium.*       embedded-engine knobs (offset file, snapshot mode, heartbeat, publication mode)
snowplow.*       collector URL + emitter buffer settings
tables[].*       per-table primary key, column whitelist, renames, transforms
observability.*  HTTP port (/health, /ready), heartbeat interval
```

- **Full annotated example:** [`examples/config.yaml`](examples/config.yaml)
- **Authoritative schema:** [`schemas/cdc-service-config.schema.json`](schemas/cdc-service-config.schema.json)

`examples/config.yaml` carries a `yaml-language-server` directive pointing at that schema, so editors with the Red Hat YAML extension (VS Code, Cursor, Zed) validate the config in-editor and flag mistakes - like under-indented column directives - before runtime.

## Replication & Postgres setup

### Postgres prerequisites

The service connects as a Debezium logical-replication client. Before it can stream, the source database must satisfy:

- **`wal_level=logical`** on the Postgres server.
- The connector account has **`REPLICATION`** privilege and **`SELECT`** on captured tables.
- A **publication** and a **replication slot** exist. You can create them yourself, or let the service create the publication via `debezium.publication_autocreate_mode`:
  - `filtered` (default) - a publication scoped to the configured tables.
  - `all_tables` - a publication of every table in the database.
  - `disabled` - the service never creates one; you must provision it.
- Each captured table has **`REPLICA IDENTITY FULL`**. This is what makes the `before` field of `UPDATE` and `DELETE` events carry full row state rather than just the primary key. At startup the service queries `pg_class.relreplident` for each captured table and emits a loud warning if any table is not `FULL` - it does **not** refuse to start, leaving you to decide whether PK-only `before` is acceptable for your use case.

### Snapshot vs. streaming (`snapshot_mode`)

`debezium.snapshot_mode` controls whether the service reads existing rows before it begins streaming:

| Mode | Behaviour |
|---|---|
| `never` (default) | Stream new changes only, from the moment the service first connects. No historical backfill. |
| `initial` | Snapshot all captured tables once, then stream. |
| `initial_only` | Snapshot once, then stop (no streaming). |
| `when_needed` | Snapshot only if the stored offset is missing or invalid; otherwise stream. |

Snapshot rows arrive as `op=r` events with `cdc_source.is_snapshot=true`, so analysts can filter them downstream. **Historical backfill is out of scope** for this prototype - for a full historical sync, use a dedicated tool (e.g. Fivetran). Rationale and alternatives: design doc §1 and §9.

## Schema evolution

Schema evolution is treated as a **tracking-plan concern, not a CDC-tool concern**: Snowplow already models it with Iglu SchemaVer + bad rows, and this service feeds that model rather than competing with it. The behaviour is **detection only** - the service never auto-versions schemas:

- **The YAML column whitelist is the contract.** New DB columns are invisible until you add them to `tables[].columns`; dropped columns appear as `null` until you remove them.
- **A source-schema fingerprint** rides on every event in `cdc_source.column_fingerprint` - a stable hash of the source column metadata. When the source DDL changes, the fingerprint changes: a queryable downstream signal of drift.
- **DDL-change detection logging.** When an event's fingerprint differs from the last-seen fingerprint for that table, the service emits a structured `source_schema_change` WARN log line (table, old/new fingerprint, added/removed columns) for your existing log alerting to catch.
- **No automatic Iglu versioning.** `scaffold` output is a starting point; there is no `evolve` mode, no live registry sync, no auto-bumping of schema versions.

Operator action per kind of DDL change:

| DB change | What you do |
|---|---|
| Add a column you want to emit | Bump the Iglu schema (`1-0-0` → `1-0-1` optional / `2-0-0` required), add it to `columns:`, bump `iglu_schema:`, restart |
| Add a column you don't want | Nothing - the column is invisible to the service |
| Remove a column you were emitting | Bump the Iglu schema (`2-0-0`, breaking), remove from `columns:`, bump `iglu_schema:`, restart |
| Rename a column | Treat as remove + add |
| Change a column type | If the Iglu schema accepts both, nothing; otherwise bump the schema and restart |
| Add a captured table | Add a `tables:` entry, restart |
| Drop a captured table | Remove the `tables:` entry, restart at a convenient time |

Full model and known gaps: see [Operating notes](#operating-notes).

## Demo

The Compose stack boots Postgres (with logical replication + seed schema), Snowplow Micro, and this service:

```bash
make up             # build the Jib image, then `docker compose up -d`
make demo           # run a sample INSERT against Postgres
make demo-update    # run a sample UPDATE against Postgres
make demo-delete    # run a sample DELETE against Postgres
make demo-all       # run a sample INSERT, UPDATE, and DELETE against Postgres

make events         # curl Snowplow Micro and show the captured events
make down           # tear the stack down
```

Validated events appear in Snowplow Micro at `http://localhost:9090` (`/micro/good`). See the `Makefile` for the full set of demo targets (`demo-update`, `demo-delete`, `demo-all`, `events-summary`, `logs`).

### Scaffold demo

A second walkthrough that showcases the `scaffold` subcommand: instead of using the committed config and schemas, you shape a database by hand, let `scaffold` generate the starter config + Iglu schemas from the live schema, make a few edits, then stream live changes. The Compose stack selects a replication-only Postgres init (via the `PG_INIT` env var) so the tables are created by your manual seed, not at boot.

```bash
make scaffold-db     # fresh Postgres, replication-only init (no tables)
make seed            # manually create + populate customers and orders (docker/seed.sql)
make scaffold        # generate scaffold-out/{config.yaml, schemas/, cdc-service-config.schema.json}
#                    # ...now edit scaffold-out/config.yaml — see the checklist below...
make scaffold-up     # boot Micro + service against the scaffolded config + schemas
make demo            # live INSERT/UPDATE/DELETE on orders (op=c/u/d)
make demo-customer   # live INSERT + UPDATE on customers (shows the edited transform)
make events          # see the events in Micro
make down            # tear down
```

`scaffold` runs on the Compose network and connects as `postgres://cdc@postgres:5432/...`, so the generated `source.hostname` is already `postgres` — the same name the in-Compose service uses. No hostname edit needed.

**Edit checklist for `scaffold-out/config.yaml`** (the generated file is a deliberately bare starting point):

1. **`snowplow.emitter.batch_size: 50` → `1`** — so single events flush to Micro immediately during the demo rather than waiting for a full batch.
2. **`customers.columns`** — remove `email` (PII) and `created_at` (noise); the YAML whitelist is the contract, so dropped columns simply never leave the source.
3. **`customers.columns`** — give `country_code` an `uppercase` transform:
   ```yaml
         - country_code:
             transforms: [uppercase]
   ```
4. **`orders.columns`** — give `status` a `trim` transform (the seed rows are padded with whitespace):
   ```yaml
         - status:
             transforms: [trim]
   ```

Transforms apply to TEXT columns only — a transform on a non-string column is a fatal startup error, by design. `scaffold` writes to a fresh directory and refuses to overwrite, so `make scaffold` clears `scaffold-out/` first; re-run it any time to regenerate from the live schema.

**Running the changes by hand.** `make demo` / `make demo-customer` fire the CRUD for you, but for a presentation you can step through `docker/demo-walkthrough.sql` one statement at a time so the audience watches each event land. Open a session and paste a line at a time:

```bash
docker compose exec postgres psql -U cdc -d orders_db
```

The script walks a single `orders` row through `c → u → d`, then a `customers` row, with comments calling out what to look for in Micro (trimmed `status`, uppercased `country_code`, the absent `email`/`created_at`, and an update to a non-emitted column that still fires an event).

## Operating notes

A few things to know before running this in anger - see design doc §10 for the full list.

- **Persist the offset file.** The service stores its WAL offset at `debezium.offset_store.file_path` (when `offset_store.type` is `file`). Mount it on a durable volume (the examples use `-v cdc-offsets:/var/lib/cdc`). Losing it means a re-snapshot (`snapshot_mode=initial`) or skipped events (`snapshot_mode=never`), depending on configuration. Production deployments should migrate to a JDBC-backed offset store.
- **For ephemeral filesystems, use the JDBC offset store.** Set `debezium.offset_store.type: jdbc` with a `jdbc` block (`username`, `password`, optional `table_name`, default `cdc_offset_storage`). The offset is then stored in a table **in the source database** (the connection is derived from `source.hostname/port/database`), so it survives container recycles without a persistent disk.
  - The table is **auto-created on first connection**, so the offset user needs `CREATE` on the schema plus DML on the table. On Postgres 15+ the `public` schema no longer grants `CREATE` by default — grant it explicitly (`GRANT CREATE ON SCHEMA public TO <offset_user>`).
  - Use a **dedicated offset user**, separate from the least-privilege replication user.
  - The store writes roughly once per second — modest, steady load on the source instance.
- **Switching offset backends is a fresh start, not a migration.** Offsets are not copied between the file and JDBC stores; on switch, Debezium behaves per `snapshot_mode` and the existing replication slot, exactly as a new deployment would. Quiesce writes during the cutover if you need a zero-gap switch.
- **Backpressure surfaces as replication-slot lag.** The emitter blocks on overflow, so a slow or down collector propagates backpressure through Debezium to the Postgres WAL - visible as growing replication-slot lag. Monitor slot lag with your standard Postgres ops alerting; that alert is the service's failure-mode signal.
- **Schema violations go to bad rows.** Events that don't validate against their Iglu schema land in Snowplow's bad-rows pipeline downstream - the safety net for source drift you haven't reconciled yet.

## Building from source

The contributor / dev path. End users should prefer the [Quickstart](#quickstart) release artifacts.

**Fat JAR** (runnable anywhere with JDK 21):

```bash
./gradlew shadowJar
# -> build/libs/cdc-service.jar
```

**Docker image** (via [Jib](https://github.com/GoogleContainerTools/jib) - builds the image with **no Dockerfile and no `docker build`**; Jib assembles the layers itself):

```bash
./gradlew jibDockerBuild
# -> loads cdc-service:local into your local Docker daemon
```

For a fully daemonless build (CI / air-gapped), use `./gradlew jibBuildTar` (writes `build/jib-image.tar`, which you `docker load` later - this is the same artifact attached to the release).

## License

Apache 2.0 - see [`LICENSE`](LICENSE).
