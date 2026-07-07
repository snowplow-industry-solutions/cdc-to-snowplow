resource "aws_vpc" "this" {
  cidr_block           = var.vpc_cidr
  enable_dns_support   = true
  enable_dns_hostnames = true
}

resource "aws_subnet" "this" {
  count             = length(var.subnet_cidrs)
  vpc_id            = aws_vpc.this.id
  cidr_block        = var.subnet_cidrs[count.index]
  availability_zone = var.azs[count.index]
}

resource "aws_internet_gateway" "this" {
  vpc_id = aws_vpc.this.id
}

resource "aws_route_table" "public" {
  vpc_id = aws_vpc.this.id

  route {
    cidr_block = "0.0.0.0/0"
    gateway_id = aws_internet_gateway.this.id
  }
}

resource "aws_route_table_association" "this" {
  count          = length(aws_subnet.this)
  subnet_id      = aws_subnet.this[count.index].id
  route_table_id = aws_route_table.public.id
}

resource "aws_db_subnet_group" "this" {
  name       = "${var.name_prefix}-db-subnets"
  subnet_ids = aws_subnet.this[*].id
}

# Logical replication for Debezium.
resource "aws_db_parameter_group" "pg" {
  name   = "${var.name_prefix}-pg16-logical"
  family = "postgres16"

  parameter {
    name         = "rds.logical_replication"
    value        = "1"
    apply_method = "pending-reboot"
  }
}

resource "aws_security_group" "db" {
  name        = "${var.name_prefix}-db-sg"
  description = "Ingress to source Postgres (in-VPC tasks; optional workstation)"
  vpc_id      = aws_vpc.this.id

  # In-VPC reach: ECS task ENIs get private IPs inside the VPC CIDR.
  ingress {
    description = "Postgres from within the VPC"
    from_port   = 5432
    to_port     = 5432
    protocol    = "tcp"
    cidr_blocks = [var.vpc_cidr]
  }

  # Optional workstation reach for seeding (only when public access is enabled).
  dynamic "ingress" {
    for_each = var.enable_public_access && var.admin_authorized_cidr != "" ? [var.admin_authorized_cidr] : []
    content {
      description = "Postgres from admin workstation"
      from_port   = 5432
      to_port     = 5432
      protocol    = "tcp"
      cidr_blocks = [ingress.value]
    }
  }

  egress {
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = ["0.0.0.0/0"]
  }
}

resource "aws_db_instance" "pg" {
  identifier        = "${var.name_prefix}-pg"
  engine            = "postgres"
  engine_version    = "16"
  instance_class    = var.db_instance_class
  allocated_storage = 20
  db_name           = var.db_name
  username          = var.db_user
  # Password set out-of-band and stored in the secret below; RDS requires a non-empty value here.
  password               = "CHANGEME-set-out-of-band"
  parameter_group_name   = aws_db_parameter_group.pg.name
  db_subnet_group_name   = aws_db_subnet_group.this.name
  skip_final_snapshot    = true
  publicly_accessible    = var.enable_public_access
  vpc_security_group_ids = [aws_security_group.db.id]

  tags = {
    "snowplow/data-classification" = var.data_classification
  }

  # Password is set out-of-band (runbook) and mirrored into the secret; don't let
  # a later re-apply revert it to the inline placeholder.
  lifecycle {
    ignore_changes = [password]
  }
}

resource "aws_ecr_repository" "images" {
  name = var.name_prefix

  # Teardown-friendly (like skip_final_snapshot on RDS): destroy the repo even
  # when it still holds pushed images, so `terraform destroy` doesn't wedge.
  force_delete = true

  tags = {
    "snowplow/data-classification" = var.data_classification
  }
}

# Empty secret container — value populated out-of-band, never committed.
resource "aws_secretsmanager_secret" "db_password" {
  name = "${var.name_prefix}-db-password"

  tags = {
    "snowplow/data-classification" = var.data_classification
  }
}
