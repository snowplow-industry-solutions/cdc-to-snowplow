# Approach B (AWS): always-on single task.
deployment_mode = "always_on"

region          = "eu-west-1"
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
