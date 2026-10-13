output "table_names" {
  description = "Physical table names keyed by role (events, tickets, orders, audit)."
  value       = local.tables
}

output "table_arns" {
  value = {
    events  = aws_dynamodb_table.events.arn
    tickets = aws_dynamodb_table.tickets.arn
    orders  = aws_dynamodb_table.orders.arn
    audit   = aws_dynamodb_table.audit.arn
  }
}
