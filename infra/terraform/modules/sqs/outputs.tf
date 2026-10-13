output "queue_name" {
  value = aws_sqs_queue.orders.name
}

output "queue_arn" {
  value = aws_sqs_queue.orders.arn
}

output "dlq_name" {
  value = aws_sqs_queue.dlq.name
}

output "dlq_arn" {
  value = aws_sqs_queue.dlq.arn
}
