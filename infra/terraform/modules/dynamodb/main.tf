# Mirrors DynamoSchema.java exactly (same keys, attribute types and GSIs). Keep both in sync.
# On-demand billing: capacity follows the traffic spikes of an on-sale without capacity planning.

locals {
  tables = {
    events  = "${var.name_prefix}-events"
    tickets = "${var.name_prefix}-tickets"
    orders  = "${var.name_prefix}-orders"
    audit   = "${var.name_prefix}-ticket-audit"
  }
}

# events: one item per event, carrying the per-status inventory counters (cntAvailable, cntSold, ...).
resource "aws_dynamodb_table" "events" {
  name                        = local.tables.events
  billing_mode                = "PAY_PER_REQUEST"
  hash_key                    = "id"
  deletion_protection_enabled = var.deletion_protection

  attribute {
    name = "id"
    type = "S"
  }

  point_in_time_recovery {
    enabled = var.point_in_time_recovery
  }

  server_side_encryption {
    enabled     = true
    kms_key_arn = var.kms_key_arn
  }
}

# tickets: one item per ticket. The sparse GSI only holds AVAILABLE tickets (availableEvent is removed
# from the item as soon as the ticket leaves AVAILABLE).
resource "aws_dynamodb_table" "tickets" {
  name                        = local.tables.tickets
  billing_mode                = "PAY_PER_REQUEST"
  hash_key                    = "eventId"
  range_key                   = "ticketId"
  deletion_protection_enabled = var.deletion_protection

  attribute {
    name = "eventId"
    type = "S"
  }

  attribute {
    name = "ticketId"
    type = "S"
  }

  attribute {
    name = "availableEvent"
    type = "S"
  }

  global_secondary_index {
    name            = "available-index"
    hash_key        = "availableEvent"
    range_key       = "ticketId"
    projection_type = "ALL"
  }

  point_in_time_recovery {
    enabled = var.point_in_time_recovery
  }

  server_side_encryption {
    enabled     = true
    kms_key_arn = var.kms_key_arn
  }
}

# orders: orders plus idempotency items (id = "idem#..."). The sparse GSI only holds orders that hold
# tickets, ordered by reservation deadline (expiresAt, epoch seconds) so the expiry job reads a tiny range.
resource "aws_dynamodb_table" "orders" {
  name                        = local.tables.orders
  billing_mode                = "PAY_PER_REQUEST"
  hash_key                    = "id"
  deletion_protection_enabled = var.deletion_protection

  attribute {
    name = "id"
    type = "S"
  }

  attribute {
    name = "status"
    type = "S"
  }

  attribute {
    name = "expiresAt"
    type = "N"
  }

  global_secondary_index {
    name            = "status-expiry-index"
    hash_key        = "status"
    range_key       = "expiresAt"
    projection_type = "ALL"
  }

  point_in_time_recovery {
    enabled = var.point_in_time_recovery
  }

  server_side_encryption {
    enabled     = true
    kms_key_arn = var.kms_key_arn
  }
}

# audit: append-only ticket state changes (ticketKey = eventId#ticketId, at = ISO-8601 instant).
resource "aws_dynamodb_table" "audit" {
  name                        = local.tables.audit
  billing_mode                = "PAY_PER_REQUEST"
  hash_key                    = "ticketKey"
  range_key                   = "at"
  deletion_protection_enabled = var.deletion_protection

  attribute {
    name = "ticketKey"
    type = "S"
  }

  attribute {
    name = "at"
    type = "S"
  }

  point_in_time_recovery {
    enabled = var.point_in_time_recovery
  }

  server_side_encryption {
    enabled     = true
    kms_key_arn = var.kms_key_arn
  }
}
