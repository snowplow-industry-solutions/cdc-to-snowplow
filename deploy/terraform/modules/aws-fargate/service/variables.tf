variable "region" {
  type    = string
  default = "eu-west-1"
}
variable "service_name" {
  type    = string
  default = "cdc-service"
}
variable "container_image" {
  type        = string
  description = "Full image ref incl. tag or digest, e.g. <acct>.dkr.ecr.eu-west-1.amazonaws.com/cdc:0.1.0"
}

variable "deployment_mode" {
  type    = string
  default = "always_on"
  validation {
    condition     = contains(["always_on", "scheduled"], var.deployment_mode)
    error_message = "deployment_mode must be \"always_on\" or \"scheduled\"."
  }
}

# --- networking ---
variable "subnet_ids" { type = list(string) }
variable "vpc_id" { type = string }
variable "assign_public_ip" {
  type    = bool
  default = false
}

# --- secret ---
variable "db_password_secret_arn" {
  type        = string
  description = "Secrets Manager secret ARN holding the source DB password (value populated out-of-band)."
}

# --- sizing ---
variable "cpu" {
  type    = number
  default = 1024
}
variable "memory" {
  type    = number
  default = 2048
}
variable "log_retention_days" {
  type    = number
  default = 14
}

# --- schedule (only used when deployment_mode = scheduled) ---
variable "schedule_start_expression" {
  type    = string
  default = "cron(0 8 ? * MON-FRI *)"
}
variable "schedule_stop_expression" {
  type    = string
  default = "cron(0 19 ? * MON-FRI *)"
}
variable "schedule_timezone" {
  type    = string
  default = "Etc/UTC"
}

# --- config.yaml.tftpl values (identical set to the GCP module) ---
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
  type    = string
  default = <<-EOT
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

variable "system" {
  type    = string
  default = "snowplow-cdc"
}
variable "owner" {
  type    = string
  default = "CC017"
  validation {
    condition     = can(regex("^CC[0-9]{3}$", var.owner))
    error_message = "owner must be an uppercase cost-centre id like CC017."
  }
}
variable "environment" {
  type    = string
  default = "dev"
  validation {
    condition     = contains(["prod", "staging", "dev"], var.environment)
    error_message = "environment must be prod, staging, or dev."
  }
}
