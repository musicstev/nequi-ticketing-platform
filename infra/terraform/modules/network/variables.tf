variable "name" {
  description = "Name prefix for every resource (for example ticketing-dev)."
  type        = string
}

variable "vpc_cidr" {
  description = "CIDR block of the VPC. Keep it unique per environment so VPCs can be peered later."
  type        = string
}

variable "azs" {
  description = "Exactly the availability zones to span (two for high availability)."
  type        = list(string)
}

variable "enable_nat_gateway" {
  description = "Create NAT gateways for outbound internet from private subnets. With the VPC endpoints below the app needs no internet, so this can be false to save cost."
  type        = bool
  default     = true
}

variable "single_nat_gateway" {
  description = "One shared NAT gateway (cheap, single-AZ failure domain) instead of one per AZ. Use true in dev, false in prod."
  type        = bool
  default     = true
}

variable "flow_log_retention_days" {
  description = "Retention of the VPC flow logs log group."
  type        = number
  default     = 14
}
