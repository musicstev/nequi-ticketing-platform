variable "name" {
  type = string
}

variable "vpc_id" {
  type = string
}

variable "vpc_cidr" {
  description = "Used to restrict the ALB egress to the VPC."
  type        = string
}

variable "public_subnet_ids" {
  type = list(string)
}

variable "container_port" {
  type    = number
  default = 8080
}

variable "health_check_path" {
  type    = string
  default = "/actuator/health"
}

variable "allowed_ingress_cidrs" {
  description = "Source CIDRs allowed to reach the load balancer. Open (0.0.0.0/0) for a public API; restrict for private betas."
  type        = list(string)
  default     = ["0.0.0.0/0"]
}

variable "certificate_arn" {
  description = "ACM certificate ARN. When set, 443 terminates TLS and 80 redirects to it. When empty, only plain HTTP is served (acceptable in dev ONLY)."
  type        = string
  default     = ""
}

variable "enable_waf" {
  description = "Associate a regional WAFv2 web ACL (rate limit + AWS managed rules)."
  type        = bool
  default     = false
}

variable "waf_rate_limit" {
  description = "Requests allowed per client IP in a 5-minute window before WAF blocks it."
  type        = number
  default     = 2000
}

variable "deletion_protection" {
  type    = bool
  default = false
}
