variable "name" {
  description = "Name prefix (for example ticketing-dev)."
  type        = string
}

variable "admin_token_placeholder" {
  description = "Initial value of the admin token secret. A placeholder only: rotate it out-of-band (aws secretsmanager put-secret-value); Terraform ignores later changes. Anything passed here is stored in Terraform state, so never put the real token here."
  type        = string
  sensitive   = true
  default     = "CHANGE_ME_AFTER_APPLY"
}

variable "kms_deletion_window_days" {
  description = "Waiting period before a scheduled key deletion takes effect."
  type        = number
  default     = 30
}

variable "secret_recovery_window_days" {
  description = "Recovery window of the secret (0 forces immediate deletion; only for throwaway environments)."
  type        = number
  default     = 7
}
