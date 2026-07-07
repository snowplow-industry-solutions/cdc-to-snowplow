# Terraform deployments (example modules)

Opinionated, **example-grade** Terraform for running `cdc-service` on GCP Cloud Run
or AWS ECS Fargate. Fork and adapt — these are templates, not managed infrastructure.

**What these are NOT:** they do not commit Terraform state and do not commit secrets.
State uses the local backend by default; for real use, configure a remote backend
(GCS / S3) yourself (commented examples below). Secret *containers* are created by
`bootstrap`; their *values* are populated out-of-band.

**Permissions:** deploying creates real infrastructure and needs a non-trivial set
of cloud roles. See [PERMISSIONS.md](./PERMISSIONS.md) for the exact GCP roles / AWS
policies per phase — handy for getting unblocked in a locked-down sandbox.

## Layout

- `modules/<cloud>/bootstrap/` — optional, turn-key: source Postgres (logical
  replication enabled), VPC, image registry, and an empty secret container.
- `modules/<cloud>/service/` — the runtime. `deployment_mode` selects the posture:
  - `always_on` (Approach B): one instance, always running.
  - `scheduled` (Approach A): scheduler flips the instance up/down on a cron.

## Two postures

| | A — `scheduled` | B — `always_on` |
|---|---|---|
| Idle cost | ~0 off-hours | runs continuously |
| Mechanism (GCP) | Cloud Scheduler flips min instance count 1↔0 (max stays 1) | min=max=1, CPU always allocated |
| Mechanism (AWS) | EventBridge Scheduler flips desiredCount 1↔0 | desiredCount=1 |
| Caveat | "stopped" = pipeline paused; safe only if the source DB is idle off-hours | none |

Both postures use the **JDBC offset store into the source DB**, so neither needs a
persistent volume.

## Deploying on GCP (end-to-end)

The `bootstrap` layer creates the VPC, a Cloud SQL Postgres instance with an
**empty database**, an Artifact Registry repo, and an **empty** password-secret
container. It deliberately does **not** create tables or set the DB password —
those are the out-of-band steps below. Skip bootstrap if you point at an existing DB.

```bash
CHDIR=modules/gcp-cloudrun
```

**1. Bootstrap (optional).** Creates infra; note the outputs — `db_private_ip`,
`network`, `subnetwork`, `registry_url` — you feed them to the service tfvars.

```bash
terraform -chdir=$CHDIR/bootstrap init
terraform -chdir=$CHDIR/bootstrap apply -var="project_id=my-gcp-project"
```

**2. Set the DB password and mirror it into the secret.** The SQL user's password
and the secret's value **must match** — the service authenticates as the user with
the value it reads from the secret.

```bash
export DB_PASSWORD="$(openssl rand -base64 24 | tr -d '/+=' | cut -c1-24)"
gcloud sql users set-password cdc --instance=cdc-pg --password="$DB_PASSWORD"
printf '%s' "$DB_PASSWORD" | gcloud secrets versions add cdc-db-password --data-file=-
```

**3. Seed the source database.** Bootstrap leaves the DB empty, so the service's
startup preflight fails with `table not found` until the captured tables exist.
Adapt [`examples/seed.sql`](./examples/seed.sql) to the tables in your `tables_yaml`
and run it (you need network reach to the DB — see *Admin DB access* below, or use
the Cloud SQL Auth Proxy):

```bash
PGPASSWORD="$DB_PASSWORD" psql \
  "host=<DB_PUBLIC_IP> port=5432 dbname=orders_db user=cdc sslmode=require" \
  -f examples/seed.sql
```

Every captured table needs `REPLICA IDENTITY FULL` (before-images for transforms) —
`seed.sql` sets this. The replication user also needs the `REPLICATION` attribute;
`seed.sql` no longer does this (it must run cleanly on RDS too), so grant it
explicitly after seeding:

```bash
PGPASSWORD="$DB_PASSWORD" psql \
  "host=<DB_PUBLIC_IP> port=5432 dbname=orders_db user=cdc sslmode=require" \
  -c "ALTER USER cdc WITH REPLICATION;"
```

On Cloud SQL this succeeds even run as `cdc` itself (Cloud SQL grants its users the
`cloudsqlsuperuser` role). Debezium auto-creates the publication.

**4. Push the image and apply the service.**

```bash
./gradlew jib -Djib.to.image=<registry_url>/cdc-service:0.1.0
terraform -chdir=$CHDIR/service init
terraform -chdir=$CHDIR/service apply -var-file=../../examples/gcp-B.tfvars
```

The container runs `run --config-env CDC_CONFIG`; the module renders `config.yaml`
from your variables and injects it as `CDC_CONFIG`. The DB password is injected
separately from the secret store and interpolated into the YAML at runtime.
`terraform apply` blocks until the Cloud Run revision is healthy, so a green apply
already means the startup preflight passed.

**5. Verify.** `gcloud run services logs read` shows only platform logs — the app
logs as JSON under `jsonPayload`. Watch the `heartbeat` line's counters:

```bash
gcloud logging read \
  'resource.type="cloud_run_revision" AND resource.labels.service_name="cdc-service" AND jsonPayload.message="heartbeat"' \
  --limit=10 --freshness=1h --order=desc \
  --format='table(timestamp, jsonPayload.received, jsonPayload.emitted, jsonPayload.buffer_used, jsonPayload.ready)'
```

`emitted` climbing = events reached the collector (a 2xx — not that they *validated*;
schema/bad-row truth lives in the Snowplow account). **Note:** `snapshot_mode: never`
(the default) means pre-existing rows are **not** emitted — only writes after the
replication slot exists. Test with a *fresh* insert, not the seed rows.

### Admin DB access (optional)

The bootstrap DB is **private-IP only by default** (reachable from Cloud Run over
Direct VPC egress). To reach it from a workstation (e.g. DataGrip, or to run the
seed above), set in your bootstrap tfvars:

```hcl
enable_public_ip      = true
admin_authorized_cidr = "203.0.113.4/32"   # your workstation, as a /32
```

This adds a public IP **locked to that CIDR**; the private IP stays on for Cloud
Run. Leave it off unless you need direct access. `gcloud sql instances describe`
returns three IPs (PRIMARY public / PRIVATE / OUTGOING) — PRIMARY is the one for DataGrip.

## Deploying on AWS (end-to-end)

The `bootstrap` layer creates a VPC, an RDS Postgres instance with an **empty
database**, an ECR repo, and an **empty** password-secret container. It
deliberately does **not** create tables or set the DB password — those are the
out-of-band steps below. Skip bootstrap if you point at an existing DB.
Validated end-to-end against the Snowplow eng sandbox: bootstrap + service
applied, a fresh write emitted to the collector, then torn down cleanly.

```bash
CHDIR=modules/aws-fargate
```

**1. Bootstrap (optional).** Creates infra; note the outputs — `vpc_id`,
`subnet_ids`, `db_address`, `registry_url`, `db_password_secret_arn` — you feed
them to the service tfvars (see *Wiring bootstrap → service* below).

```bash
terraform -chdir=$CHDIR/bootstrap init
terraform -chdir=$CHDIR/bootstrap apply \
  -var-file="$(pwd)/deploy/terraform/examples/aws-bootstrap.tfvars"
```

**Var-file gotcha:** under `-chdir=<module>`, a `-var-file` path resolves
relative to the *module* directory, not the repo root — `../../examples/...`
lands inside `modules/examples` and 404s. Run `terraform` from the repo root
and pass an absolute path (`$(pwd)/deploy/terraform/examples/...`, as above),
or count directories correctly from `modules/aws-fargate/bootstrap`
(`../../../examples/...`).

**2. Set the DB password and mirror it into the secret.** The RDS master
password and the secret's value **must match** — the service authenticates as
the user with the value it reads from the secret.

```bash
export DB_PASSWORD="$(openssl rand -base64 24 | tr -d '/+=' | cut -c1-24)"
aws rds modify-db-instance \
  --db-instance-identifier cdc-pg \
  --master-user-password "$DB_PASSWORD" \
  --apply-immediately
aws secretsmanager put-secret-value \
  --secret-id "$(terraform -chdir=$CHDIR/bootstrap output -raw db_password_secret_arn)" \
  --secret-string "$DB_PASSWORD"
```

**3. Wait for the password change to apply, then reboot for logical
replication.** Bootstrap sets `rds.logical_replication = 1` on the DB
parameter group, but RDS applies that parameter `pending-reboot` — logical
replication is dead until the instance reboots. There's no GCP equivalent of
this step, so it's easy to forget. Ordering also matters: `--apply-immediately`
above briefly puts the instance into `modifying`, and a reboot mid-modify is
**rejected**, so wait for `available` first:

```bash
aws rds wait db-instance-available --db-instance-identifier cdc-pg
aws rds reboot-db-instance --db-instance-identifier cdc-pg
aws rds wait db-instance-available --db-instance-identifier cdc-pg
```

**4. Seed the source database.** You need network reach to the DB — see
*Admin DB access* below (RDS is private-IP-only by default).

```bash
DB_ADDRESS="$(terraform -chdir=$CHDIR/bootstrap output -raw db_address)"
PGPASSWORD="$DB_PASSWORD" psql \
  "host=$DB_ADDRESS port=5432 dbname=orders_db user=cdc sslmode=require" \
  -f deploy/terraform/examples/seed.sql
```

Every captured table needs `REPLICA IDENTITY FULL` (before-images for
transforms) — `seed.sql` sets this.

**5. Grant replication.** RDS forbids `ALTER USER … WITH REPLICATION` (unlike
Cloud SQL) — grant the managed role instead:

```bash
PGPASSWORD="$DB_PASSWORD" psql \
  "host=$DB_ADDRESS port=5432 dbname=orders_db user=cdc sslmode=require" \
  -c "GRANT rds_replication TO cdc;"
```

Debezium auto-creates the publication.

**6. Push the image.**

```bash
REGISTRY_URL="$(terraform -chdir=$CHDIR/bootstrap output -raw registry_url)"
aws ecr get-login-password --region eu-west-1 | \
  docker login --username AWS --password-stdin "${REGISTRY_URL%%/*}"
./gradlew jib -Djib.to.image="${REGISTRY_URL}:0.1.0"
```

**Gotcha:** no space after `-Djib.to.image=` — a space makes Gradle parse the
image ref as a second task name and fail.

**7. Apply the service.**

```bash
terraform -chdir=$CHDIR/service init
terraform -chdir=$CHDIR/service apply \
  -var-file="$(pwd)/deploy/terraform/examples/aws-B.tfvars"
```

Because `wait_for_steady_state = true`, apply **blocks** until the ECS task is
healthy — a failing task makes apply hang until it times out and then errors,
which surfaces failure rather than hiding it. The service tfvars need
`assign_public_ip = true` (bootstrap's VPC has an IGW and a public subnet, but
no NAT). The deploying principal needs `iam:CreateRole` / `iam:PassRole` — the
service module creates the ECS execution + task roles itself (see
[PERMISSIONS.md](./PERMISSIONS.md) for the full policy list; this was allowed
in the validated eng-sandbox run).

**8. Verify.** ECS tasks log to CloudWatch, not Cloud Logging — watch the
`heartbeat` line's `emitted` counter:

```bash
aws logs tail /ecs/cdc-service --since 5m --region eu-west-1 | grep heartbeat
```

(`--follow` streams live but never exits; drop it for a one-shot snapshot, as
above.) `emitted` climbing = events reached the collector (a 2xx — not that
they *validated*; schema/bad-row truth lives in the Snowplow account). As on
GCP, `snapshot_mode: never` (the default) means the seed rows never emit — test
with a *fresh* insert:

```bash
PGPASSWORD="$DB_PASSWORD" psql \
  "host=$DB_ADDRESS port=5432 dbname=orders_db user=cdc sslmode=require" \
  -c "INSERT INTO public.orders (id, customer_id, status, total) VALUES (1001, 1, '  fresh  ', 42.00);"
```

### Admin DB access (optional)

The bootstrap DB is **private-IP only by default**. To reach it from a
workstation (e.g. DataGrip, or to run the seed above), set in your bootstrap
tfvars:

```hcl
enable_public_access  = true
admin_authorized_cidr = "203.0.113.4/32"   # your workstation, as a /32
```

This opens a security-group ingress rule locked to that CIDR. The bootstrap
VPC has `enable_dns_hostnames` enabled, which RDS requires for a
`publicly_accessible` instance — a custom VPC defaults this off, so it's set
explicitly in the module. Leave `enable_public_access` off unless you need
direct access.

### Teardown

```bash
terraform -chdir=$CHDIR/service destroy \
  -var-file="$(pwd)/deploy/terraform/examples/aws-B.tfvars"
terraform -chdir=$CHDIR/bootstrap destroy \
  -var-file="$(pwd)/deploy/terraform/examples/aws-bootstrap.tfvars"
```

The ECR repo has `force_delete = true`, so a fresh deploy tears down cleanly.
**Gotcha:** `force_delete` only takes effect if it was in state *at destroy
time* — a repo created before that flag was added wedges destroy with
`RepositoryNotEmptyException`. Fix it out-of-band (don't `apply` first if the
rest of bootstrap is already destroyed — that would just recreate everything):

```bash
aws ecr delete-repository --repository-name cdc --force
```

then re-run `bootstrap destroy`. Secrets Manager schedules a 7-day delete
(sets `DeletedDate`) rather than deleting the secret immediately.

## Wiring bootstrap → service

If you applied the optional `bootstrap` layer first, map its outputs onto the
service module's input variables (the names differ — bootstrap describes the DB,
the service describes its *source*):

| GCP bootstrap output | AWS bootstrap output | → service variable |
|---|---|---|
| `network` | — | `network` |
| `subnetwork` | — | `subnetwork` |
| — | `vpc_id` | `vpc_id` |
| — | `subnet_ids` | `subnet_ids` |
| `db_private_ip` | `db_address` | `source_hostname` |
| `db_name` | `db_name` | `source_database` |
| `db_user` | `db_user` | `source_username` / `offset_username` |
| `db_password_secret_id` | `db_password_secret_arn` | `db_password_secret_id` / `db_password_secret_arn` |
| `registry_url` | `registry_url` | (push the image here; feed the full ref to `container_image`) |

The example tfvars in `examples/` show plausible literal values for each.

## Remote backend (configure for real use)

```hcl
# GCP — put in a backend.tf you add yourself:
# terraform { backend "gcs" { bucket = "my-tf-state"; prefix = "cdc/gcp" } }
# AWS:
# terraform { backend "s3"  { bucket = "my-tf-state"; key = "cdc/aws.tfstate"; region = "eu-west-1" } }
```
