variable "region" {
  type    = string
  default = "eu-west-1"
}
variable "name_prefix" {
  type    = string
  default = "cdc"
}
variable "db_instance_class" {
  type    = string
  default = "db.t4g.micro"
}
variable "db_name" {
  type    = string
  default = "orders_db"
}
variable "db_user" {
  type    = string
  default = "cdc"
}
variable "vpc_cidr" {
  type    = string
  default = "10.30.0.0/16"
}
variable "subnet_cidrs" {
  type    = list(string)
  default = ["10.30.1.0/24", "10.30.2.0/24"]
}
variable "azs" {
  type    = list(string)
  default = ["eu-west-1a", "eu-west-1b"]
}
variable "enable_public_access" {
  type    = bool
  default = false
}
variable "admin_authorized_cidr" {
  type    = string
  default = ""
}
variable "system" {
  type    = string
  default = "snowplow-cdc"
}
variable "owner" {
  type    = string
  default = "CC017"
  validation {
    condition     = can(regex("^CC[0-9]{3}$", var.owner))
    error_message = "owner must be an uppercase cost-centre id like CC017."
  }
}
variable "environment" {
  type    = string
  default = "dev"
  validation {
    condition     = contains(["prod", "staging", "dev"], var.environment)
    error_message = "environment must be prod, staging, or dev."
  }
}
variable "data_classification" {
  type    = string
  default = "2-internal"
  validation {
    condition     = contains(["1-public", "2-internal", "3-confidential", "4-restricted"], var.data_classification)
    error_message = "data_classification must be one of 1-public, 2-internal, 3-confidential, 4-restricted."
  }
}
