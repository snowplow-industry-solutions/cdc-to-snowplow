variable "project_id" { type = string }
variable "region" {
  type    = string
  default = "europe-west1"
}
variable "name_prefix" {
  type    = string
  default = "cdc"
}
variable "db_tier" {
  type    = string
  default = "db-custom-1-3840"
}
variable "db_name" {
  type    = string
  default = "orders_db"
}
variable "db_user" {
  type    = string
  default = "cdc"
}
variable "enable_public_ip" {
  type        = bool
  default     = false
  description = "Give the Cloud SQL instance a public IPv4 address (for admin access from a workstation). Private IP is always enabled regardless."
}
variable "admin_authorized_cidr" {
  type        = string
  default     = null
  description = "CIDR allowed to reach the public IP (e.g. your workstation as a /32). Only used when enable_public_ip = true."
}
