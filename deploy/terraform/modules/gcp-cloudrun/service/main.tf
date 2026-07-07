locals {
  is_scheduled = var.deployment_mode == "scheduled"

  cdc_config = templatefile("${path.module}/config.yaml.tftpl", {
    app_id                       = var.app_id
    source_hostname              = var.source_hostname
    source_port                  = var.source_port
    source_database              = var.source_database
    source_username              = var.source_username
    slot_name                    = var.slot_name
    publication_name             = var.publication_name
    snapshot_mode                = var.snapshot_mode
    publication_autocreate_mode  = var.publication_autocreate_mode
    provide_transaction_metadata = var.provide_transaction_metadata
    heartbeat_interval_ms        = var.heartbeat_interval_ms
    offset_username              = var.offset_username
    offset_table_name            = var.offset_table_name
    collector_url                = var.collector_url
    cdc_source_schema            = var.cdc_source_schema
    emitter_batch_size           = var.emitter_batch_size
    emitter_buffer_capacity      = var.emitter_buffer_capacity
    observability_http_port      = var.observability_http_port
    heartbeat_log_interval_ms    = var.heartbeat_log_interval_ms
    tables_yaml                  = var.tables_yaml
  })
}

resource "google_service_account" "runner" {
  account_id   = "${var.service_name}-run"
  display_name = "Runtime SA for ${var.service_name}"
}

resource "google_secret_manager_secret_iam_member" "db_password_access" {
  secret_id = var.db_password_secret_id
  role      = "roles/secretmanager.secretAccessor"
  member    = "serviceAccount:${google_service_account.runner.email}"
}

resource "google_cloud_run_v2_service" "cdc" {
  name                = var.service_name
  location            = var.region
  ingress             = "INGRESS_TRAFFIC_INTERNAL_ONLY"
  deletion_protection = false

  template {
    service_account = google_service_account.runner.email

    scaling {
      min_instance_count = 1
      max_instance_count = 1
    }

    vpc_access {
      network_interfaces {
        network    = var.network
        subnetwork = var.subnetwork
      }
      egress = "PRIVATE_RANGES_ONLY"
    }

    containers {
      image = var.container_image
      args  = ["run", "--config-env", "CDC_CONFIG"]

      resources {
        # CPU always allocated — a request-billed instance would throttle Debezium's
        # background threads between requests and stall the pipeline.
        cpu_idle = false
        limits = {
          cpu    = var.cpu
          memory = var.memory
        }
      }

      env {
        name  = "CDC_CONFIG"
        value = local.cdc_config
      }
      env {
        name = "POSTGRES_PASSWORD"
        value_source {
          secret_key_ref {
            secret  = var.db_password_secret_id
            version = "latest"
          }
        }
      }

      ports {
        container_port = var.observability_http_port
      }

      startup_probe {
        http_get {
          path = "/health"
          port = var.observability_http_port
        }
        period_seconds    = 10
        timeout_seconds   = 5
        failure_threshold = 30
      }

      liveness_probe {
        http_get {
          path = "/health"
          port = var.observability_http_port
        }
        period_seconds = 30
      }
    }
  }

  # In scheduled mode the start/stop jobs flip min/max instance count; ignore drift on those.
  lifecycle {
    ignore_changes = [template[0].scaling]
  }
}
