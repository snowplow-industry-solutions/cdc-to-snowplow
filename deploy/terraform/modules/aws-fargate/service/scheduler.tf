# Count-gated: present only when deployment_mode = "scheduled". Two EventBridge Scheduler
# schedules call ecs:UpdateService to flip desired_count between 1 (start) and 0 (stop).
data "aws_iam_policy_document" "scheduler_assume" {
  count = local.is_scheduled ? 1 : 0
  statement {
    actions = ["sts:AssumeRole"]
    principals {
      type        = "Service"
      identifiers = ["scheduler.amazonaws.com"]
    }
  }
}

resource "aws_iam_role" "scheduler" {
  count              = local.is_scheduled ? 1 : 0
  name               = "${var.service_name}-scheduler"
  assume_role_policy = data.aws_iam_policy_document.scheduler_assume[0].json
}

data "aws_iam_policy_document" "scheduler_update_service" {
  count = local.is_scheduled ? 1 : 0
  statement {
    actions   = ["ecs:UpdateService"]
    resources = [aws_ecs_service.this.id]
  }
}

resource "aws_iam_role_policy" "scheduler" {
  count  = local.is_scheduled ? 1 : 0
  name   = "${var.service_name}-update-service"
  role   = aws_iam_role.scheduler[0].id
  policy = data.aws_iam_policy_document.scheduler_update_service[0].json
}

resource "aws_scheduler_schedule" "start" {
  count                        = local.is_scheduled ? 1 : 0
  name                         = "${var.service_name}-start"
  schedule_expression          = var.schedule_start_expression
  schedule_expression_timezone = var.schedule_timezone

  flexible_time_window {
    mode = "OFF"
  }

  target {
    arn      = "arn:aws:scheduler:::aws-sdk:ecs:updateService"
    role_arn = aws_iam_role.scheduler[0].arn
    input = jsonencode({
      Cluster      = aws_ecs_cluster.this.name
      Service      = aws_ecs_service.this.name
      DesiredCount = 1
    })
  }
}

resource "aws_scheduler_schedule" "stop" {
  count                        = local.is_scheduled ? 1 : 0
  name                         = "${var.service_name}-stop"
  schedule_expression          = var.schedule_stop_expression
  schedule_expression_timezone = var.schedule_timezone

  flexible_time_window {
    mode = "OFF"
  }

  target {
    arn      = "arn:aws:scheduler:::aws-sdk:ecs:updateService"
    role_arn = aws_iam_role.scheduler[0].arn
    input = jsonencode({
      Cluster      = aws_ecs_cluster.this.name
      Service      = aws_ecs_service.this.name
      DesiredCount = 0
    })
  }
}
