output "service_name" {
  value = google_cloud_run_v2_service.cdc.name
}

output "service_uri" {
  value = google_cloud_run_v2_service.cdc.uri
}

output "runner_service_account" {
  value = google_service_account.runner.email
}
