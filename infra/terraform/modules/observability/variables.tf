variable "name" {
  type = string
}

variable "kms_key_arn" {
  description = "Key encrypting the SNS topic (its policy must allow cloudwatch.amazonaws.com)."
  type        = string
}

variable "alarm_email" {
  description = "Optional e-mail subscribed to the alarm topic (must confirm the subscription). Leave empty to wire PagerDuty/Chatbot to the topic instead."
  type        = string
  default     = ""
}

variable "orders_queue_name" {
  type = string
}

variable "dlq_name" {
  type = string
}

variable "alb_arn_suffix" {
  type = string
}

variable "target_group_arn_suffix" {
  type = string
}

variable "cluster_name" {
  type = string
}

variable "service_name" {
  type = string
}

variable "queue_age_threshold_seconds" {
  description = "Alarm when the oldest message waits longer than this (consumers are not keeping up)."
  type        = number
  default     = 300
}

variable "alb_5xx_threshold" {
  description = "Target 5xx responses per 5 minutes."
  type        = number
  default     = 10
}

variable "cpu_threshold_percent" {
  type    = number
  default = 80
}
