# Standard queue: at-least-once delivery. Mirrors docker/localstack/init-sqs.sh
# (visibility 60s, long polling 20s, 4-day retention, DLQ with maxReceiveCount 3, 14-day DLQ retention).

resource "aws_sqs_queue" "dlq" {
  name                              = "${var.queue_name}-dlq"
  message_retention_seconds         = 1209600
  kms_master_key_id                 = var.kms_key_arn
  kms_data_key_reuse_period_seconds = 300
}

resource "aws_sqs_queue" "orders" {
  name                              = var.queue_name
  visibility_timeout_seconds        = var.visibility_timeout_seconds
  receive_wait_time_seconds         = 20
  message_retention_seconds         = 345600
  kms_master_key_id                 = var.kms_key_arn
  kms_data_key_reuse_period_seconds = 300

  redrive_policy = jsonencode({
    deadLetterTargetArn = aws_sqs_queue.dlq.arn
    maxReceiveCount     = var.max_receive_count
  })
}

# Only the orders queue may use this DLQ.
resource "aws_sqs_queue_redrive_allow_policy" "dlq" {
  queue_url = aws_sqs_queue.dlq.id
  redrive_allow_policy = jsonencode({
    redrivePermission = "byQueue"
    sourceQueueArns   = [aws_sqs_queue.orders.arn]
  })
}

# Deny any request that does not use TLS.
data "aws_iam_policy_document" "tls_only" {
  for_each = {
    orders = aws_sqs_queue.orders.arn
    dlq    = aws_sqs_queue.dlq.arn
  }

  statement {
    sid       = "DenyInsecureTransport"
    effect    = "Deny"
    actions   = ["sqs:*"]
    resources = [each.value]
    principals {
      type        = "*"
      identifiers = ["*"]
    }
    condition {
      test     = "Bool"
      variable = "aws:SecureTransport"
      values   = ["false"]
    }
  }
}

resource "aws_sqs_queue_policy" "orders" {
  queue_url = aws_sqs_queue.orders.id
  policy    = data.aws_iam_policy_document.tls_only["orders"].json
}

resource "aws_sqs_queue_policy" "dlq" {
  queue_url = aws_sqs_queue.dlq.id
  policy    = data.aws_iam_policy_document.tls_only["dlq"].json
}
