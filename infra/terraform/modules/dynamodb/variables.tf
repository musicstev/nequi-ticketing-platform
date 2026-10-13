variable "name_prefix" {
  description = "Prefix of the physical table names (for example ticketing-dev gives ticketing-dev-events). The application receives the final names through DYNAMODB_*_TABLE variables."
  type        = string
}

variable "kms_key_arn" {
  description = "Customer managed key used for server-side encryption."
  type        = string
}

variable "point_in_time_recovery" {
  description = "Enable continuous backups (35-day point-in-time recovery)."
  type        = bool
  default     = true
}

variable "deletion_protection" {
  description = "Protect tables from accidental deletion (true in prod)."
  type        = bool
  default     = false
}
