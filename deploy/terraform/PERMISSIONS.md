# Permissions to deploy the demo

The example modules create real cloud infrastructure, so the identity running
Terraform (and the Jib image push) needs a fair set of permissions. This page
lists exactly what — useful both for getting unblocked in a locked-down sandbox
and for deciding what to request from a cloud admin.

Grant these to **the identity Terraform actually runs as**:

- `gcloud auth application-default login` → your **user** account (`user:you@org`).
- A service-account key / impersonation → that **service account** (`serviceAccount:…`).
- An assumed AWS role / profile → that principal.

Two phases, possibly two different operators: **bootstrap** (creates the source
DB, network, registry, secret container) and **service** (deploys the runtime).
They can be the same person.

---

## GCP

`roles/owner` on the project covers everything below. If you can't get Owner
(typical in a corporate sandbox), use the least-privilege roles that follow.

### APIs to enable

Needs `roles/serviceusage.serviceUsageAdmin`:

```bash
gcloud services enable \
  compute.googleapis.com \
  servicenetworking.googleapis.com \
  sqladmin.googleapis.com \
  run.googleapis.com \
  artifactregistry.googleapis.com \
  secretmanager.googleapis.com \
  cloudscheduler.googleapis.com \
  iam.googleapis.com \
  cloudresourcemanager.googleapis.com
```

### Phase 1 — bootstrap operator

| Role | Why |
|---|---|
| `roles/serviceusage.serviceUsageAdmin` | enable the APIs above |
| `roles/compute.networkAdmin` | VPC, subnet, reserved PSA address |
| `roles/servicenetworking.networksAdmin` | the Private Service Access peering for private Cloud SQL |
| `roles/cloudsql.admin` | Cloud SQL instance/db/user + `gcloud sql users set-password` |
| `roles/artifactregistry.admin` | create the repo (`roles/artifactregistry.writer` alone is enough for the Jib push) |
| `roles/secretmanager.admin` | create the secret + add the password version |

### Phase 2 — service operator

| Role | Why |
|---|---|
| `roles/run.admin` | deploy the Cloud Run service **and** set its service-level IAM (the scheduler binding) |
| `roles/iam.serviceAccountAdmin` | create the runtime + scheduler service accounts |
| `roles/iam.serviceAccountUser` | `actAs` those SAs (Cloud Run runs *as* the runtime SA; the scheduler signs OAuth tokens *as* the scheduler SA) |
| `roles/cloudscheduler.admin` | the start/stop jobs — **scheduled mode only** |
| `roles/secretmanager.admin` *(or `roles/secretmanager.secretVersionManager`)* | the module grants the runtime SA `secretAccessor` on the secret (a `setIamPolicy` on the secret) |

> **No `roles/resourcemanager.projectIamAdmin` is required.** The scheduler SA is
> granted `roles/run.developer` **on the Cloud Run service itself**, not project-wide,
> so the binding is covered by the deployer's `roles/run.admin`. (Earlier revisions
> used a project-level binding that did need Project IAM Admin — the single hardest
> role to obtain in a sandbox. It's been scoped down.)

### Always-on vs scheduled

Approach **B (`always_on`)** needs neither `roles/cloudscheduler.admin` nor the
service-level IAM binding — it deploys with just the core service roles. If your
sandbox is very restrictive, always-on is the lower-permission path.

### Per-resource appendix

Every Terraform resource → the permission it exercises (for auditing a custom role):

| Resource / step | Permission family |
|---|---|
| `google_compute_network` / `_subnetwork` / `_global_address` | `compute.networks.*`, `compute.subnetworks.*`, `compute.globalAddresses.*` |
| `google_service_networking_connection` | `servicenetworking.services.addPeering` |
| `google_sql_database_instance` / `_database` / `_user` | `cloudsql.instances.*`, `cloudsql.databases.*`, `cloudsql.users.*` |
| `google_artifact_registry_repository` + Jib push | `artifactregistry.repositories.*` (+ `.uploadArtifacts` to push) |
| `google_secret_manager_secret` + version + IAM member | `secretmanager.secrets.*`, `…versions.add`, `…secrets.setIamPolicy` |
| `google_service_account` ×2 | `iam.serviceAccounts.create` |
| Cloud Run runs *as* runtime SA; scheduler OAuth *as* scheduler SA | `iam.serviceAccounts.actAs` |
| `google_cloud_run_v2_service` | `run.services.create` / `.update` |
| `google_cloud_run_v2_service_iam_member` (scheduler → run.developer, service-scoped) | `run.services.setIamPolicy` |
| `google_cloud_scheduler_job` ×2 | `cloudscheduler.jobs.*` |

---

## AWS

`AdministratorAccess` covers everything. Least-privilege managed-policy
equivalents follow; the spicy part on AWS is **IAM role creation + `iam:PassRole`**,
which sandboxes often restrict.

### Phase 1 — bootstrap operator

| Managed policy (or equivalent) | Why |
|---|---|
| `AmazonVPCFullAccess` | VPC, subnets, DB subnet group |
| `AmazonRDSFullAccess` | RDS instance, DB parameter group (`rds.logical_replication`) |
| `AmazonEC2ContainerRegistryFullAccess` | create the ECR repo + push the image (`PowerUser` suffices for push) |
| `SecretsManagerReadWrite` | create the secret + put the password value |

### Phase 2 — service operator

| Managed policy (or equivalent) | Why |
|---|---|
| `AmazonECS_FullAccess` | cluster, task definition, service |
| `CloudWatchLogsFullAccess` | the `/ecs/<service>` log group |
| `AmazonVPCFullAccess` (or scoped EC2 SG perms) | the egress security group |
| EventBridge Scheduler: `scheduler:CreateSchedule` / `…GetSchedule` / `…DeleteSchedule` | start/stop schedules — **scheduled mode only** |
| ⚠️ **IAM**: `iam:CreateRole`, `iam:AttachRolePolicy`, `iam:PutRolePolicy`, `iam:PassRole` | create the ECS execution/task roles (+ scheduler role) and pass them to ECS / EventBridge. **This is the commonly-restricted one.** |

> The execution role needs `iam:PassRole` so ECS can assume it; the scheduler role
> is passed to EventBridge Scheduler. If your sandbox forbids `iam:CreateRole`, ask
> an admin to pre-create the three roles and feed their ARNs in as variables (a small
> module change — open an issue if you need this path).

### Always-on vs scheduled

Approach **B (`always_on`)** skips the EventBridge Scheduler permissions and the
scheduler IAM role entirely.
