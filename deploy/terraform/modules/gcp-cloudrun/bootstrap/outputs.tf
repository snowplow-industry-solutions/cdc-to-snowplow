output "network" {
  value = google_compute_network.vpc.id
}
output "subnetwork" {
  value = google_compute_subnetwork.subnet.id
}
output "db_private_ip" {
  value = google_sql_database_instance.pg.private_ip_address
}
output "db_name" {
  value = google_sql_database.db.name
}
output "db_user" {
  value = google_sql_user.cdc.name
}
output "registry_url" {
  value = "${google_artifact_registry_repository.images.location}-docker.pkg.dev/${var.project_id}/${google_artifact_registry_repository.images.repository_id}"
}
output "db_password_secret_id" {
  value = google_secret_manager_secret.db_password.secret_id
}
