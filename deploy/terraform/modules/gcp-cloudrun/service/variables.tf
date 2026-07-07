variable "project_id" { type = string }
variable "region" {
  type    = string
  default = "europe-west1"
}
variable "service_name" {
  type    = string
  default = "cdc-service"
}
variable "container_image" {
  type        = string
  description = "Full image ref incl. tag or digest, e.g. europe-west1-docker.pkg.dev/PROJ/cdc/cdc-service:0.1.0"
}

variable "deployment_mode" {
  type    = string
  default = "always_on"
  validation {
    condition     = contains(["always_on", "scheduled"], var.deployment_mode)
    error_message = "deployment_mode must be \"always_on\" or \"scheduled\"."
  }
}

# --- networking (Direct VPC egress to reach the private Cloud SQL instance) ---
variable "network" { type = string }
variable "subnetwork" { type = string }

# --- secret ---
variable "db_password_secret_id" {
  type        = string
  description = "Secret Manager secret id holding the source DB password (value populated out-of-band)."
}

# --- sizing ---
variable "cpu" {
  type    = string
  default = "1"
}
variable "memory" {
  type    = string
  default = "1Gi"
}

# --- schedule (only used when deployment_mode = scheduled) ---
variable "schedule_start_cron" {
  type    = string
  default = "0 8 * * 1-5"
}
variable "schedule_stop_cron" {
  type    = string
  default = "0 19 * * 1-5"
}
variable "schedule_timezone" {
  type    = string
  default = "Etc/UTC"
}

# --- config.yaml.tftpl values ---
variable "app_id" { type = string }
variable "source_hostname" { type = string }
variable "source_port" {
  type    = number
  default = 5432
}
variable "source_database" { type = string }
variable "source_username" { type = string }
variable "slot_name" {
  type    = string
  default = "snowplow_cdc"
}
variable "publication_name" {
  type    = string
  default = "snowplow_cdc_pub"
}
variable "snapshot_mode" {
  type    = string
  default = "never"
}
variable "publication_autocreate_mode" {
  type    = string
  default = "filtered"
}
variable "provide_transaction_metadata" {
  type    = bool
  default = false
}
variable "heartbeat_interval_ms" {
  type    = number
  default = 30000
}
variable "offset_username" { type = string }
variable "offset_table_name" {
  type    = string
  default = "debezium_offsets"
}
variable "collector_url" { type = string }
variable "cdc_source_schema" {
  type    = string
  default = "iglu:com.snowplowanalytics/cdc_source/jsonschema/1-0-0"
}
variable "emitter_batch_size" {
  type    = number
  default = 1
}
variable "emitter_buffer_capacity" {
  type    = number
  default = 1000
}
variable "observability_http_port" {
  type    = number
  default = 8080
}
variable "heartbeat_log_interval_ms" {
  type    = number
  default = 60000
}
variable "tables_yaml" {
  type        = string
  description = "The YAML body of the tables: list, indented 2 spaces (paste your scaffold output)."
  default     = <<-EOT
    - name: orders
      schema: public
      iglu_schema: iglu:com.example/orders_change/jsonschema/1-0-0
      primary_key: [id]
      columns:
        - id
        - customer_id
        - status
        - total
  EOT
}
