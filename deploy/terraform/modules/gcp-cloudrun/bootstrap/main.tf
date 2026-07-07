resource "google_compute_network" "vpc" {
  name                    = "${var.name_prefix}-vpc"
  auto_create_subnetworks = false
}

resource "google_compute_subnetwork" "subnet" {
  name          = "${var.name_prefix}-subnet"
  region        = var.region
  network       = google_compute_network.vpc.id
  ip_cidr_range = "10.20.0.0/24"
}

# Private Service Access so Cloud SQL gets a private IP reachable from Cloud Run Direct VPC egress.
resource "google_compute_global_address" "private_ip_range" {
  name          = "${var.name_prefix}-psa-range"
  purpose       = "VPC_PEERING"
  address_type  = "INTERNAL"
  prefix_length = 16
  network       = google_compute_network.vpc.id
}

resource "google_service_networking_connection" "psa" {
  network                 = google_compute_network.vpc.id
  service                 = "servicenetworking.googleapis.com"
  reserved_peering_ranges = [google_compute_global_address.private_ip_range.name]
}

resource "google_sql_database_instance" "pg" {
  name                = "${var.name_prefix}-pg"
  database_version    = "POSTGRES_16"
  region              = var.region
  deletion_protection = false
  depends_on          = [google_service_networking_connection.psa]

  settings {
    tier    = var.db_tier
    edition = "ENTERPRISE"

    database_flags {
      name  = "cloudsql.logical_decoding"
      value = "on"
    }

    ip_configuration {
      # Private IP is always on so Cloud Run reaches the DB over Direct VPC egress.
      # Public IP is opt-in (enable_public_ip) for admin access from a workstation,
      # locked to admin_authorized_cidr.
      ipv4_enabled    = var.enable_public_ip
      private_network = google_compute_network.vpc.id

      dynamic "authorized_networks" {
        for_each = var.admin_authorized_cidr == null ? [] : [var.admin_authorized_cidr]
        content {
          name  = "admin"
          value = authorized_networks.value
        }
      }
    }
  }
}

resource "google_sql_database" "db" {
  name     = var.db_name
  instance = google_sql_database_instance.pg.name
}

resource "google_sql_user" "cdc" {
  name     = var.db_user
  instance = google_sql_database_instance.pg.name
  # Password is set out-of-band (e.g. gcloud sql users set-password) and stored in the secret below.
  password = "CHANGEME-set-out-of-band"
}

resource "google_artifact_registry_repository" "images" {
  location      = var.region
  repository_id = var.name_prefix
  format        = "DOCKER"
}

# Empty secret container — the value is populated out-of-band, never committed.
resource "google_secret_manager_secret" "db_password" {
  secret_id = "${var.name_prefix}-db-password"
  replication {
    auto {}
  }
}
