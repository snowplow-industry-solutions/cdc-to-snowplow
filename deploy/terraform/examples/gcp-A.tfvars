# Approach A (GCP): serverless, scheduled on/off. Pass with:
#   terraform -chdir=modules/gcp-cloudrun/service apply -var-file=../../examples/gcp-A.tfvars
deployment_mode = "scheduled"

project_id      = "my-gcp-project"
region          = "europe-west1"
container_image = "europe-west1-docker.pkg.dev/my-gcp-project/cdc/cdc-service:0.1.0"

network               = "projects/my-gcp-project/global/networks/cdc-vpc"
subnetwork            = "projects/my-gcp-project/regions/europe-west1/subnetworks/cdc-subnet"
db_password_secret_id = "cdc-db-password"

app_id          = "orders-cdc"
source_hostname = "10.20.0.3"
source_database = "orders_db"
source_username = "cdc"
offset_username = "cdc"
collector_url   = "https://collector.example.com"

schedule_start_cron = "0 8 * * 1-5"
schedule_stop_cron  = "0 19 * * 1-5"
schedule_timezone   = "Europe/London"
