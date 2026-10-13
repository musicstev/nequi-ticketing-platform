variable "environment" {
  type    = string
  default = "dev"
}

variable "region" {
  type    = string
  default = "us-east-1"
}

variable "project" {
  type    = string
  default = "ticketing"
}

variable "owner" {
  description = "Team or e-mail accountable for the resources (governance tag)."
  type        = string
  default     = "platform-team"
}

variable "cost_center" {
  type    = string
  default = "engineering"
}

# --- network ---
variable "vpc_cidr" {
  type = string
}

variable "enable_nat_gateway" {
  type    = bool
  default = true
}

variable "single_nat_gateway" {
  type    = bool
  default = true
}

# --- edge ---
variable "certificate_arn" {
  description = "ACM certificate for the HTTPS listener. Empty serves plain HTTP (dev only)."
  type        = string
  default     = ""
}

variable "allowed_ingress_cidrs" {
  type    = list(string)
  default = ["0.0.0.0/0"]
}

variable "enable_waf" {
  type    = bool
  default = false
}

variable "waf_rate_limit" {
  type    = number
  default = 2000
}

# --- data ---
variable "point_in_time_recovery" {
  type    = bool
  default = true
}

variable "deletion_protection" {
  type    = bool
  default = false
}

variable "admin_token_placeholder" {
  description = "Placeholder written once to Secrets Manager; rotate the real token out-of-band."
  type        = string
  sensitive   = true
  default     = "CHANGE_ME_AFTER_APPLY"
}

# --- compute ---
variable "image_tag" {
  type    = string
  default = "latest"
}

variable "task_cpu" {
  type    = number
  default = 512
}

variable "task_memory" {
  type    = number
  default = 1024
}

variable "cpu_architecture" {
  type    = string
  default = "X86_64"
}

variable "min_capacity" {
  type    = number
  default = 1
}

variable "max_capacity" {
  type    = number
  default = 4
}

variable "backlog_per_task_target" {
  type    = number
  default = 100
}

variable "log_retention_days" {
  type    = number
  default = 14
}

variable "alarm_email" {
  type    = string
  default = ""
}
