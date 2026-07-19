# Approach A (AWS): serverless-ish, scheduled on/off.
deployment_mode = "scheduled"

region          = "eu-west-1"
# Own build pushed to ECR; for the released demo use the public image instead:
# container_image = "ghcr.io/snowplow-industry-solutions/cdc-to-snowplow:0.1.0"
container_image = "123456789012.dkr.ecr.eu-west-1.amazonaws.com/cdc:0.1.0"

vpc_id                 = "vpc-0123456789abcdef0"
subnet_ids             = ["subnet-0aaa", "subnet-0bbb"]
db_password_secret_arn = "arn:aws:secretsmanager:eu-west-1:123456789012:secret:cdc-db-password-AbCdEf"

# Public-subnet posture: the task needs a public IP to reach ECR/collector
# (bootstrap has an IGW but no NAT).
assign_public_ip = true

app_id          = "orders-cdc"
source_hostname = "cdc-pg.abc123.eu-west-1.rds.amazonaws.com"
source_database = "orders_db"
source_username = "cdc"
offset_username = "cdc"
collector_url   = "https://collector.example.com"

schedule_start_expression = "cron(0 8 ? * MON-FRI *)"
schedule_stop_expression  = "cron(0 19 ? * MON-FRI *)"
schedule_timezone         = "Europe/London"
