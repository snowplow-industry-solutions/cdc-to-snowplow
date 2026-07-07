# Count-gated: present only when deployment_mode = "scheduled". Two Cloud Scheduler jobs PATCH
# the Cloud Run service's min instance count between 1 (start) and 0 (stop, scales to zero).
resource "google_service_account" "scheduler" {
  count        = local.is_scheduled ? 1 : 0
  account_id   = "${var.service_name}-sched"
  display_name = "Cloud Scheduler invoker for ${var.service_name}"
}

# Service-scoped (not project-wide): the scheduler SA gets run.developer on THIS service only,
# enough to PATCH its scaling. Granting it here needs run.services.setIamPolicy (covered by the
# deployer's roles/run.admin) rather than project-level resourcemanager.projects.setIamPolicy —
# so no Project IAM Admin is required, which matters in locked-down sandboxes.
resource "google_cloud_run_v2_service_iam_member" "scheduler_run_developer" {
  count    = local.is_scheduled ? 1 : 0
  project  = var.project_id
  location = var.region
  name     = google_cloud_run_v2_service.cdc.name
  role     = "roles/run.developer"
  member   = "serviceAccount:${google_service_account.scheduler[0].email}"
}

resource "google_cloud_scheduler_job" "start" {
  count     = local.is_scheduled ? 1 : 0
  name      = "${var.service_name}-start"
  region    = var.region
  schedule  = var.schedule_start_cron
  time_zone = var.schedule_timezone

  http_target {
    http_method = "PATCH"
    uri         = "https://run.googleapis.com/v2/projects/${var.project_id}/locations/${var.region}/services/${var.service_name}?updateMask=template.scaling.minInstanceCount,template.scaling.maxInstanceCount"
    headers     = { "Content-Type" = "application/json" }
    body = base64encode(jsonencode({
      template = { scaling = { minInstanceCount = 1, maxInstanceCount = 1 } }
    }))
    oauth_token {
      service_account_email = google_service_account.scheduler[0].email
    }
  }
}

resource "google_cloud_scheduler_job" "stop" {
  count     = local.is_scheduled ? 1 : 0
  name      = "${var.service_name}-stop"
  region    = var.region
  schedule  = var.schedule_stop_cron
  time_zone = var.schedule_timezone

  http_target {
    http_method = "PATCH"
    uri         = "https://run.googleapis.com/v2/projects/${var.project_id}/locations/${var.region}/services/${var.service_name}?updateMask=template.scaling.minInstanceCount,template.scaling.maxInstanceCount"
    headers     = { "Content-Type" = "application/json" }
    body = base64encode(jsonencode({
      template = { scaling = { minInstanceCount = 0, maxInstanceCount = 1 } }
    }))
    oauth_token {
      service_account_email = google_service_account.scheduler[0].email
    }
  }
}
