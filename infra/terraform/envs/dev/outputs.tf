output "alb_dns_name" {
  description = "Public entry point of the API."
  value       = module.alb.dns_name
}

output "ecr_repository_url" {
  description = "Push the application image here."
  value       = module.ecs_service.ecr_repository_url
}

output "orders_queue_name" {
  value = module.sqs.queue_name
}

output "dlq_name" {
  value = module.sqs.dlq_name
}

output "table_names" {
  value = module.dynamodb.table_names
}

output "alarm_topic_arn" {
  value = module.observability.alarm_topic_arn
}

output "admin_token_secret_arn" {
  description = "Rotate the real token with: aws secretsmanager put-secret-value --secret-id <arn> --secret-string <token>"
  value       = module.security.admin_token_secret_arn
}
