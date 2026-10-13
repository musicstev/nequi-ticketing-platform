data "aws_caller_identity" "current" {}

# One customer managed key per environment encrypts DynamoDB, SQS, SNS and Secrets Manager.
data "aws_iam_policy_document" "kms" {
  statement {
    sid       = "AccountAdministration"
    actions   = ["kms:*"]
    resources = ["*"]
    principals {
      type        = "AWS"
      identifiers = ["arn:aws:iam::${data.aws_caller_identity.current.account_id}:root"]
    }
  }

  # CloudWatch alarms publish to the KMS-encrypted SNS topic.
  statement {
    sid       = "CloudWatchAlarmsToEncryptedSns"
    actions   = ["kms:GenerateDataKey*", "kms:Decrypt"]
    resources = ["*"]
    principals {
      type        = "Service"
      identifiers = ["cloudwatch.amazonaws.com"]
    }
    condition {
      test     = "StringEquals"
      variable = "aws:SourceAccount"
      values   = [data.aws_caller_identity.current.account_id]
    }
  }
}

resource "aws_kms_key" "this" {
  description             = "${var.name} data encryption (DynamoDB, SQS, SNS, Secrets Manager)"
  enable_key_rotation     = true
  deletion_window_in_days = var.kms_deletion_window_days
  policy                  = data.aws_iam_policy_document.kms.json
}

resource "aws_kms_alias" "this" {
  name          = "alias/${var.name}"
  target_key_id = aws_kms_key.this.key_id
}

# Container for the admin token read by the application (ADMIN_TOKEN). Only a placeholder is created here.
resource "aws_secretsmanager_secret" "admin_token" {
  name                    = "${var.name}/admin-token"
  description             = "Shared secret that protects privileged endpoints (complimentary tickets)."
  kms_key_id              = aws_kms_key.this.arn
  recovery_window_in_days = var.secret_recovery_window_days
}

resource "aws_secretsmanager_secret_version" "admin_token" {
  secret_id     = aws_secretsmanager_secret.admin_token.id
  secret_string = var.admin_token_placeholder

  lifecycle {
    # The real value is rotated out-of-band and must never be overwritten (or read back) by Terraform.
    ignore_changes = [secret_string]
  }
}
