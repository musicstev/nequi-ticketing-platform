variable "name" {
  description = "Name prefix (for example ticketing-dev)."
  type        = string
}

variable "vpc_id" {
  type = string
}

variable "private_subnet_ids" {
  type = list(string)
}

variable "alb_security_group_id" {
  description = "The only source allowed to reach the tasks."
  type        = string
}

variable "target_group_arn" {
  type = string
}

variable "container_port" {
  type    = number
  default = 8080
}

variable "image_tag" {
  description = "Immutable image tag (for example the git SHA) pushed to the ECR repository."
  type        = string
  default     = "latest"
}

variable "cpu" {
  type    = number
  default = 512
}

variable "memory" {
  type    = number
  default = 1024
}

variable "cpu_architecture" {
  description = "X86_64 or ARM64 (Graviton is cheaper; build the image for the same architecture)."
  type        = string
  default     = "X86_64"
}

variable "desired_count" {
  description = "Initial task count; autoscaling owns it afterwards."
  type        = number
  default     = 2
}

variable "min_capacity" {
  type    = number
  default = 2
}

variable "max_capacity" {
  type    = number
  default = 10
}

variable "cpu_target_percent" {
  description = "Target average CPU utilization for target tracking."
  type        = number
  default     = 60
}

variable "backlog_per_task_target" {
  description = "Target number of visible queue messages per running task (SQS backlog target tracking)."
  type        = number
  default     = 100
}

variable "log_retention_days" {
  type    = number
  default = 30
}

variable "table_names" {
  description = "Map with events, tickets, orders and audit table names."
  type        = map(string)
}

variable "table_arns" {
  description = "Map with events, tickets, orders and audit table ARNs."
  type        = map(string)
}

variable "queue_name" {
  type = string
}

variable "queue_arn" {
  type = string
}

variable "kms_key_arn" {
  type = string
}

variable "admin_token_secret_arn" {
  type = string
}

variable "rate_limit_capacity" {
  description = "Per-client token bucket size (per task)."
  type        = number
  default     = 50
}

variable "rate_limit_refill_per_second" {
  type    = number
  default = 25
}

variable "reservation_ttl" {
  description = "Reservation hold time (ISO-8601 or Spring duration, for example 10m)."
  type        = string
  default     = "10m"
}
