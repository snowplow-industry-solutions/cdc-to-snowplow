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

resource "aws_ecs_cluster" "this" {
  name = "${var.service_name}-cluster"
}

resource "aws_cloudwatch_log_group" "this" {
  name              = "/ecs/${var.service_name}"
  retention_in_days = var.log_retention_days
}

resource "aws_security_group" "this" {
  name        = "${var.service_name}-sg"
  description = "Egress for ${var.service_name} (to source DB and collector)"
  vpc_id      = var.vpc_id

  egress {
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = ["0.0.0.0/0"]
  }
}

resource "aws_ecs_task_definition" "this" {
  family                   = var.service_name
  requires_compatibilities = ["FARGATE"]
  network_mode             = "awsvpc"
  cpu                      = var.cpu
  memory                   = var.memory
  execution_role_arn       = aws_iam_role.execution.arn
  task_role_arn            = aws_iam_role.task.arn

  container_definitions = jsonencode([
    {
      name      = var.service_name
      image     = var.container_image
      essential = true
      command   = ["run", "--config-env", "CDC_CONFIG"]
      environment = [
        { name = "CDC_CONFIG", value = local.cdc_config }
      ]
      secrets = [
        { name = "POSTGRES_PASSWORD", valueFrom = var.db_password_secret_arn }
      ]
      portMappings = [
        { containerPort = var.observability_http_port, protocol = "tcp" }
      ]
      logConfiguration = {
        logDriver = "awslogs"
        options = {
          "awslogs-group"         = aws_cloudwatch_log_group.this.name
          "awslogs-region"        = var.region
          "awslogs-stream-prefix" = "cdc"
        }
      }
    }
  ])
}

resource "aws_ecs_service" "this" {
  name                  = var.service_name
  cluster               = aws_ecs_cluster.this.id
  task_definition       = aws_ecs_task_definition.this.arn
  desired_count         = 1
  launch_type           = "FARGATE"
  wait_for_steady_state = true

  network_configuration {
    subnets          = var.subnet_ids
    security_groups  = [aws_security_group.this.id]
    assign_public_ip = var.assign_public_ip
  }

  # In scheduled mode EventBridge Scheduler flips desired_count; don't let TF revert it.
  lifecycle {
    ignore_changes = [desired_count]
  }
}
