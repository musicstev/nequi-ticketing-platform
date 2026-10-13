# prod: multi-AZ everything, protected data, WAF on. No secrets here.
environment = "prod"
region      = "us-east-1"
vpc_cidr    = "10.20.0.0/16"

# One NAT per AZ: an AZ failure does not cut outbound traffic of the other AZ.
enable_nat_gateway = true
single_nat_gateway = false

# Mandatory in prod (validated). Replace with the real ACM certificate ARN, for example via -var or CI.
certificate_arn = "arn:aws:acm:us-east-1:111111111111:certificate/00000000-0000-0000-0000-000000000000"
enable_waf      = true
waf_rate_limit  = 2000

point_in_time_recovery = true
deletion_protection    = true

min_capacity            = 2
max_capacity            = 20
task_cpu                = 1024
task_memory             = 2048
backlog_per_task_target = 100
log_retention_days      = 90
