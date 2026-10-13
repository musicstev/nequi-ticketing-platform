# dev: cheapest sensible footprint. No secrets here.
environment = "dev"
region      = "us-east-1"
vpc_cidr    = "10.10.0.0/16"

# One shared NAT (single-AZ failure domain, about 32 USD/month instead of 64).
enable_nat_gateway = true
single_nat_gateway = true

# Plain HTTP and no WAF are acceptable for a throwaway environment only.
certificate_arn = ""
enable_waf      = false

point_in_time_recovery = true
deletion_protection    = false

min_capacity       = 1
max_capacity       = 4
task_cpu           = 512
task_memory        = 1024
log_retention_days = 14
