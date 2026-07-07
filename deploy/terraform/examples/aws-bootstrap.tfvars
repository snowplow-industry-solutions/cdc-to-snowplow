# Bootstrap (AWS): source RDS + VPC + ECR + empty secret container.
region      = "eu-west-1"
name_prefix = "cdc"

# Snowplow baseline tags (see CLAUDE.md policy). owner is uppercase on AWS.
system              = "snowplow-cdc"
owner               = "CC017"
environment         = "dev"
data_classification = "2-internal"

# Admin access to the DB from a workstation (psql/DataGrip) for seeding.
# Off by default — the DB is private-IP-only unless you flip this.
enable_public_access = false
# admin_authorized_cidr = "203.0.113.4/32"   # your workstation, as a /32
