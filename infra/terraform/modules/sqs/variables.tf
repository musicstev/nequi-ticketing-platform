variable "queue_name" {
  description = "Name of the orders queue (passed to the app as SQS_QUEUE_NAME); the DLQ is <name>-dlq."
  type        = string
}

variable "kms_key_arn" {
  description = "Customer managed key for server-side encryption."
  type        = string
}

variable "visibility_timeout_seconds" {
  description = "Must exceed the worst-case processing time of one message (the app uses 60)."
  type        = number
  default     = 60
}

variable "max_receive_count" {
  description = "Deliveries before a message moves to the dead-letter queue."
  type        = number
  default     = 3
}
