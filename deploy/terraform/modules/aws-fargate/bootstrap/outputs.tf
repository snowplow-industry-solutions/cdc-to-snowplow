output "vpc_id" {
  value = aws_vpc.this.id
}
output "subnet_ids" {
  value = aws_subnet.this[*].id
}
output "db_address" {
  value = aws_db_instance.pg.address
}
output "db_name" {
  value = aws_db_instance.pg.db_name
}
output "db_user" {
  value = aws_db_instance.pg.username
}
output "registry_url" {
  value = aws_ecr_repository.images.repository_url
}
output "db_password_secret_arn" {
  value = aws_secretsmanager_secret.db_password.arn
}
