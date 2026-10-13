output "kms_key_arn" {
  value = aws_kms_key.this.arn
}

output "admin_token_secret_arn" {
  value = aws_secretsmanager_secret.admin_token.arn
}
