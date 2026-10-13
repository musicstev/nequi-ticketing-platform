provider "aws" {
  region = var.region

  # Governance: every taggable resource carries these tags (cost allocation, ownership, audits).
  default_tags {
    tags = {
      Project     = var.project
      Environment = var.environment
      ManagedBy   = "terraform"
      Owner       = var.owner
      CostCenter  = var.cost_center
    }
  }
}

data "aws_availability_zones" "available" {
  state = "available"
}

locals {
  name = "${var.project}-${var.environment}"
  azs  = slice(data.aws_availability_zones.available.names, 0, 2)
}

module "security" {
  source                  = "../../modules/security"
  name                    = local.name
  admin_token_placeholder = var.admin_token_placeholder
}

module "network" {
  source             = "../../modules/network"
  name               = local.name
  vpc_cidr           = var.vpc_cidr
  azs                = local.azs
  enable_nat_gateway = var.enable_nat_gateway
  single_nat_gateway = var.single_nat_gateway
}

module "dynamodb" {
  source                 = "../../modules/dynamodb"
  name_prefix            = local.name
  kms_key_arn            = module.security.kms_key_arn
  point_in_time_recovery = var.point_in_time_recovery
  deletion_protection    = var.deletion_protection
}

module "sqs" {
  source      = "../../modules/sqs"
  queue_name  = "${local.name}-orders"
  kms_key_arn = module.security.kms_key_arn
}

module "alb" {
  source                = "../../modules/alb"
  name                  = local.name
  vpc_id                = module.network.vpc_id
  vpc_cidr              = module.network.vpc_cidr
  public_subnet_ids     = module.network.public_subnet_ids
  allowed_ingress_cidrs = var.allowed_ingress_cidrs
  certificate_arn       = var.certificate_arn
  enable_waf            = var.enable_waf
  waf_rate_limit        = var.waf_rate_limit
  deletion_protection   = var.deletion_protection
}

module "ecs_service" {
  source                  = "../../modules/ecs_service"
  name                    = local.name
  vpc_id                  = module.network.vpc_id
  private_subnet_ids      = module.network.private_subnet_ids
  alb_security_group_id   = module.alb.security_group_id
  target_group_arn        = module.alb.target_group_arn
  image_tag               = var.image_tag
  cpu                     = var.task_cpu
  memory                  = var.task_memory
  cpu_architecture        = var.cpu_architecture
  desired_count           = var.min_capacity
  min_capacity            = var.min_capacity
  max_capacity            = var.max_capacity
  backlog_per_task_target = var.backlog_per_task_target
  log_retention_days      = var.log_retention_days
  table_names             = module.dynamodb.table_names
  table_arns              = module.dynamodb.table_arns
  queue_name              = module.sqs.queue_name
  queue_arn               = module.sqs.queue_arn
  kms_key_arn             = module.security.kms_key_arn
  admin_token_secret_arn  = module.security.admin_token_secret_arn
}

module "observability" {
  source                  = "../../modules/observability"
  name                    = local.name
  kms_key_arn             = module.security.kms_key_arn
  alarm_email             = var.alarm_email
  orders_queue_name       = module.sqs.queue_name
  dlq_name                = module.sqs.dlq_name
  alb_arn_suffix          = module.alb.arn_suffix
  target_group_arn_suffix = module.alb.target_group_arn_suffix
  cluster_name            = module.ecs_service.cluster_name
  service_name            = module.ecs_service.service_name
}
