data "aws_region" "current" {}

locals {
  table_and_index_arns = flatten([for arn in values(var.table_arns) : [arn, "${arn}/index/*"]])
}

# --- Image registry ---

resource "aws_ecr_repository" "this" {
  name                 = var.name
  image_tag_mutability = "IMMUTABLE"

  image_scanning_configuration {
    scan_on_push = true
  }

  encryption_configuration {
    encryption_type = "KMS"
    kms_key         = var.kms_key_arn
  }
}

resource "aws_ecr_lifecycle_policy" "this" {
  repository = aws_ecr_repository.this.name
  policy = jsonencode({
    rules = [{
      rulePriority = 1
      description  = "Keep the last 30 images"
      selection    = { tagStatus = "any", countType = "imageCountMoreThan", countNumber = 30 }
      action       = { type = "expire" }
    }]
  })
}

# --- Logs and cluster ---

resource "aws_cloudwatch_log_group" "app" {
  name              = "/ecs/${var.name}"
  retention_in_days = var.log_retention_days
}

resource "aws_ecs_cluster" "this" {
  name = var.name

  setting {
    name  = "containerInsights"
    value = "enabled" # also provides RunningTaskCount used by the backlog-per-task scaling policy
  }
}

# --- IAM: execution role (pull image, write logs, read the secret) ---

data "aws_iam_policy_document" "ecs_assume" {
  statement {
    actions = ["sts:AssumeRole"]
    principals {
      type        = "Service"
      identifiers = ["ecs-tasks.amazonaws.com"]
    }
  }
}

resource "aws_iam_role" "execution" {
  name_prefix        = "${var.name}-exec-"
  assume_role_policy = data.aws_iam_policy_document.ecs_assume.json
}

resource "aws_iam_role_policy_attachment" "execution_managed" {
  role       = aws_iam_role.execution.name
  policy_arn = "arn:aws:iam::aws:policy/service-role/AmazonECSTaskExecutionRolePolicy"
}

data "aws_iam_policy_document" "execution_secret" {
  statement {
    sid       = "ReadAdminTokenSecret"
    actions   = ["secretsmanager:GetSecretValue"]
    resources = [var.admin_token_secret_arn]
  }
  statement {
    sid       = "DecryptSecret"
    actions   = ["kms:Decrypt"]
    resources = [var.kms_key_arn]
  }
}

resource "aws_iam_role_policy" "execution_secret" {
  name   = "read-admin-token"
  role   = aws_iam_role.execution.id
  policy = data.aws_iam_policy_document.execution_secret.json
}

# --- IAM: task role (what the application code may do; nothing else) ---

resource "aws_iam_role" "task" {
  name_prefix        = "${var.name}-task-"
  assume_role_policy = data.aws_iam_policy_document.ecs_assume.json
}

data "aws_iam_policy_document" "task" {
  # Item-level access only: no table administration (the app never creates tables in the cloud) and no DeleteItem.
  statement {
    sid = "TicketingTables"
    actions = [
      "dynamodb:GetItem",
      "dynamodb:BatchGetItem",
      "dynamodb:PutItem",
      "dynamodb:UpdateItem",
      "dynamodb:BatchWriteItem",
      "dynamodb:ConditionCheckItem",
      "dynamodb:Query",
    ]
    resources = local.table_and_index_arns
  }

  # The event listing is the only Scan the application performs.
  statement {
    sid       = "ScanEventsOnly"
    actions   = ["dynamodb:Scan"]
    resources = [var.table_arns["events"]]
  }

  statement {
    sid = "OrdersQueue"
    actions = [
      "sqs:SendMessage",
      "sqs:ReceiveMessage",
      "sqs:DeleteMessage",
      "sqs:ChangeMessageVisibility",
      "sqs:GetQueueUrl",
      "sqs:GetQueueAttributes",
    ]
    resources = [var.queue_arn]
  }

  statement {
    sid       = "UseDataKey"
    actions   = ["kms:Decrypt", "kms:GenerateDataKey"]
    resources = [var.kms_key_arn]
  }
}

resource "aws_iam_role_policy" "task" {
  name   = "ticketing-least-privilege"
  role   = aws_iam_role.task.id
  policy = data.aws_iam_policy_document.task.json
}

# --- Network access ---

resource "aws_security_group" "tasks" {
  name_prefix = "${var.name}-tasks-"
  description = "Ticketing tasks: ingress only from the ALB"
  vpc_id      = var.vpc_id

  ingress {
    description     = "App port from the ALB"
    from_port       = var.container_port
    to_port         = var.container_port
    protocol        = "tcp"
    security_groups = [var.alb_security_group_id]
  }

  egress {
    description = "HTTPS to AWS APIs (VPC endpoints, or NAT when enabled)"
    from_port   = 443
    to_port     = 443
    protocol    = "tcp"
    cidr_blocks = ["0.0.0.0/0"]
  }

  tags = { Name = "${var.name}-tasks" }

  lifecycle {
    create_before_destroy = true
  }
}

# --- Task definition and service ---

resource "aws_ecs_task_definition" "app" {
  family                   = var.name
  requires_compatibilities = ["FARGATE"]
  network_mode             = "awsvpc"
  cpu                      = var.cpu
  memory                   = var.memory
  execution_role_arn       = aws_iam_role.execution.arn
  task_role_arn            = aws_iam_role.task.arn

  runtime_platform {
    operating_system_family = "LINUX"
    cpu_architecture        = var.cpu_architecture
  }

  container_definitions = jsonencode([{
    name         = "app"
    image        = "${aws_ecr_repository.this.repository_url}:${var.image_tag}"
    essential    = true
    portMappings = [{ containerPort = var.container_port, protocol = "tcp" }]

    # No endpoint overrides: the AWS SDK talks to the real DynamoDB/SQS through the VPC endpoints,
    # and tables are created by Terraform (DYNAMODB_BOOTSTRAP_TABLES=false).
    environment = [
      { name = "AWS_REGION", value = data.aws_region.current.region },
      { name = "DYNAMODB_BOOTSTRAP_TABLES", value = "false" },
      { name = "DYNAMODB_EVENTS_TABLE", value = var.table_names["events"] },
      { name = "DYNAMODB_TICKETS_TABLE", value = var.table_names["tickets"] },
      { name = "DYNAMODB_ORDERS_TABLE", value = var.table_names["orders"] },
      { name = "DYNAMODB_AUDIT_TABLE", value = var.table_names["audit"] },
      { name = "SQS_QUEUE_NAME", value = var.queue_name },
      { name = "RESERVATION_TTL", value = var.reservation_ttl },
      { name = "RATE_LIMIT_CAPACITY", value = tostring(var.rate_limit_capacity) },
      { name = "RATE_LIMIT_REFILL_PER_SECOND", value = tostring(var.rate_limit_refill_per_second) },
      # Safe because the ALB appends the real client address as the last X-Forwarded-For hop.
      { name = "RATE_LIMIT_TRUST_FORWARDED_FOR", value = "true" },
    ]

    secrets = [
      { name = "ADMIN_TOKEN", valueFrom = var.admin_token_secret_arn },
    ]

    logConfiguration = {
      logDriver = "awslogs"
      options = {
        "awslogs-group"         = aws_cloudwatch_log_group.app.name
        "awslogs-region"        = data.aws_region.current.region
        "awslogs-stream-prefix" = "app"
      }
    }

    stopTimeout = 30 # graceful shutdown drains in-flight requests and SQS messages
  }])
}

resource "aws_ecs_service" "app" {
  name                               = var.name
  cluster                            = aws_ecs_cluster.this.id
  task_definition                    = aws_ecs_task_definition.app.arn
  desired_count                      = var.desired_count
  launch_type                        = "FARGATE"
  health_check_grace_period_seconds  = 60
  deployment_minimum_healthy_percent = 100
  deployment_maximum_percent         = 200
  propagate_tags                     = "SERVICE"

  deployment_circuit_breaker {
    enable   = true
    rollback = true
  }

  network_configuration {
    subnets          = var.private_subnet_ids
    security_groups  = [aws_security_group.tasks.id]
    assign_public_ip = false
  }

  load_balancer {
    target_group_arn = var.target_group_arn
    container_name   = "app"
    container_port   = var.container_port
  }

  lifecycle {
    ignore_changes = [desired_count] # owned by autoscaling
  }
}

# --- Autoscaling: CPU and SQS backlog per task ---

resource "aws_appautoscaling_target" "app" {
  service_namespace  = "ecs"
  scalable_dimension = "ecs:service:DesiredCount"
  resource_id        = "service/${aws_ecs_cluster.this.name}/${aws_ecs_service.app.name}"
  min_capacity       = var.min_capacity
  max_capacity       = var.max_capacity
}

resource "aws_appautoscaling_policy" "cpu" {
  name               = "${var.name}-cpu"
  policy_type        = "TargetTrackingScaling"
  service_namespace  = aws_appautoscaling_target.app.service_namespace
  scalable_dimension = aws_appautoscaling_target.app.scalable_dimension
  resource_id        = aws_appautoscaling_target.app.resource_id

  target_tracking_scaling_policy_configuration {
    target_value       = var.cpu_target_percent
    scale_in_cooldown  = 300
    scale_out_cooldown = 60

    predefined_metric_specification {
      predefined_metric_type = "ECSServiceAverageCPUUtilization"
    }
  }
}

# Backlog per task = visible messages / running tasks. Keeps queue latency bounded when a burst of
# orders arrives faster than the consumers drain it, independently of CPU.
resource "aws_appautoscaling_policy" "sqs_backlog" {
  name               = "${var.name}-sqs-backlog"
  policy_type        = "TargetTrackingScaling"
  service_namespace  = aws_appautoscaling_target.app.service_namespace
  scalable_dimension = aws_appautoscaling_target.app.scalable_dimension
  resource_id        = aws_appautoscaling_target.app.resource_id

  target_tracking_scaling_policy_configuration {
    target_value       = var.backlog_per_task_target
    scale_in_cooldown  = 300
    scale_out_cooldown = 60

    customized_metric_specification {
      metrics {
        id          = "backlog"
        label       = "Visible messages per running task"
        expression  = "visible / tasks"
        return_data = true
      }

      metrics {
        id          = "visible"
        return_data = false
        metric_stat {
          stat = "Average"
          metric {
            namespace   = "AWS/SQS"
            metric_name = "ApproximateNumberOfMessagesVisible"
            dimensions {
              name  = "QueueName"
              value = var.queue_name
            }
          }
        }
      }

      metrics {
        id          = "tasks"
        return_data = false
        metric_stat {
          stat = "Average"
          metric {
            namespace   = "ECS/ContainerInsights"
            metric_name = "RunningTaskCount"
            dimensions {
              name  = "ClusterName"
              value = aws_ecs_cluster.this.name
            }
            dimensions {
              name  = "ServiceName"
              value = aws_ecs_service.app.name
            }
          }
        }
      }
    }
  }
}
