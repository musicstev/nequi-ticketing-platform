# Terraform: ticketing platform on AWS

Infrastructure as code for the ticketing platform: ECS Fargate behind an ALB, DynamoDB, SQS with a dead-letter queue, KMS, Secrets Manager and CloudWatch alarms. It mirrors what the application really uses (`DynamoSchema.java`, `docker/localstack/init-sqs.sh`, `application.yml`).

> Status: validated with `terraform fmt -check -recursive` and `terraform init -backend=false && terraform validate` in both environments. It has **not** been applied or planned against a real AWS account.

## Layout

```
infra/terraform
|-- modules
|   |-- network        VPC, 2 AZs, public/private subnets, IGW, NAT, VPC endpoints, flow logs
|   |-- security       KMS customer managed key (rotation on), Secrets Manager admin-token secret
|   |-- dynamodb       events, tickets (+available-index), orders (+status-expiry-index), audit
|   |-- sqs            orders queue + DLQ (maxReceiveCount 3), SSE-KMS, TLS-only policy
|   |-- alb            ALB, target group, HTTPS listener (optional cert), optional WAFv2
|   |-- ecs_service    ECR, cluster, task/execution roles, task definition, service, autoscaling
|   `-- observability  SNS topic + CloudWatch alarms
`-- envs
    |-- dev            terraform.tfvars, backend.hcl.example
    `-- prod           terraform.tfvars, backend.hcl.example
```

Each environment is a thin root module that wires the same modules with different variables. Dependency order: `security` -> `network`/`dynamodb`/`sqs` -> `alb` -> `ecs_service` -> `observability`.

## How to use

```bash
cd infra/terraform/envs/dev
cp backend.hcl.example backend.hcl            # fill in bucket, lock table, region
terraform init -backend-config=backend.hcl
terraform plan -var-file=terraform.tfvars
```

First deploy is two-stage because the service needs an image: create the registry first, push the image, then apply everything.

```bash
terraform apply -target=module.security -target=module.ecs_service.aws_ecr_repository.this
docker build -t "$(terraform output -raw ecr_repository_url):v1" ../../../..   # repo root
aws ecr get-login-password | docker login --username AWS --password-stdin <account>.dkr.ecr.<region>.amazonaws.com
docker push "$(terraform output -raw ecr_repository_url):v1"
terraform apply -var image_tag=v1
```

Then replace the placeholder secret (Terraform ignores later changes to it):

```bash
aws secretsmanager put-secret-value --secret-id "$(terraform output -raw admin_token_secret_arn)" --secret-string "<real token>"
aws ecs update-service --cluster ticketing-dev --service ticketing-dev --force-new-deployment
```

Local validation (no AWS credentials needed): `terraform fmt -check -recursive` at `infra/terraform`, then `terraform init -backend=false && terraform validate` in each env. Terraform 1.15 with AWS provider 6.x prints deprecation warnings for `hash_key`/`range_key` on `aws_dynamodb_table` (the newer `key_schema` argument); they are warnings, not errors.

## Remote state

`backend.tf` declares a partial `backend "s3" {}`; the real values come from an untracked `backend.hcl` (see `backend.hcl.example`): an S3 bucket (versioning, SSE-KMS, public access blocked) and a DynamoDB table with hash key `LockID` for locking. Terraform 1.10+ can lock with S3 only (`use_lockfile = true`). The state bucket and lock table are created once outside this configuration (chicken and egg). State contains the secret placeholder, so the bucket must be encrypted and access restricted.

## Networking

- VPC per environment (`10.10.0.0/16` dev, `10.20.0.0/16` prod: non-overlapping so they can be peered or attached to a transit gateway later).
- Two AZs. Public subnets hold only the ALB and the NAT gateways. Private subnets hold the Fargate tasks and the interface endpoints. Tasks have **no public IP**.
- Security groups chain: internet -> ALB SG (80/443) -> task SG (8080 only from the ALB SG). Endpoint SG accepts 443 from the VPC only. The ALB egress is limited to the VPC on the app port.
- VPC endpoints: DynamoDB and S3 (gateway, free; S3 is required because ECR layers are served from S3) and interface endpoints for SQS, ECR (api and dkr), CloudWatch Logs and Secrets Manager. With them AWS traffic never crosses the internet, and NAT is only a convenience (`enable_nat_gateway = false` removes it entirely).
- VPC flow logs go to CloudWatch Logs for forensics.

## Security

- **Least privilege**: the task role can only call item-level DynamoDB actions on the four tables and their two indexes (no `DeleteItem`, no table administration; `Scan` only on the events table, the one place the app scans), and only the SQS actions the app uses on the one queue. `kms:Decrypt`/`GenerateDataKey` on the environment key. The execution role (pull image, write logs) can additionally read exactly one secret.
- **Encryption**: one customer managed KMS key per environment with rotation. DynamoDB SSE, SQS SSE-KMS (queue and DLQ), SNS topic, ECR repository and the secret use it. Queues deny non-TLS requests. The ALB serves TLS 1.2/1.3 (policy `ELBSecurityPolicy-TLS13-1-2-2021-06`) when a certificate is provided; prod requires it (validated).
- **Secrets**: the admin token lives in Secrets Manager and reaches the container as `ADMIN_TOKEN` through the ECS `secrets` mechanism (never in the task definition as plain text). Terraform only writes a sensitive placeholder and `ignore_changes` on the value, so the real token is rotated out-of-band and never enters code or state.
- **Supply chain**: ECR with scan on push, immutable tags and a KMS key; lifecycle policy keeps the last 30 images.
- **Edge**: optional WAFv2 (per-IP rate limit, AWS common and known-bad-inputs rule sets), `drop_invalid_header_fields`, deletion protection in prod. The application also rate-limits per client and reads the client address from the last `X-Forwarded-For` hop (the one the ALB appends).

## Scalability and resilience

- Stateless tasks across two AZs behind the ALB; deployment circuit breaker with automatic rollback; minimum 100% healthy during rollout; health grace period 60 s; 30 s stop timeout for graceful drain.
- Autoscaling (Application Auto Scaling, target tracking): average CPU (60%) and **SQS backlog per task** (visible messages divided by running tasks, target 100) through metric math over `AWS/SQS` and `ECS/ContainerInsights`. Scale-out is fast (60 s cooldown), scale-in slow (300 s).
- DynamoDB on-demand absorbs on-sale spikes without capacity planning (note: new tables start with limited throughput headroom; pre-warm by setting a high `warm_throughput` before a major launch if needed). PITR provides 35 days of recovery. Prod enables deletion protection.
- SQS standard queue with a 60 s visibility timeout, long polling and a DLQ after 3 receives; the consumer is idempotent so redelivery is safe.

## Observability

Container Insights, a log group per service with environment-specific retention (14 days dev, 90 days prod), and CloudWatch alarms to an encrypted SNS topic (optional e-mail subscription, or connect Chatbot/PagerDuty): DLQ depth > 0, oldest message age > 300 s, ALB target 5xx, unhealthy targets, ECS CPU > 80%. The application exposes Prometheus metrics at `/actuator/prometheus` (the application has no authentication on it and the ALB forwards every path, so before production restrict `/actuator/*` with an ALB listener rule or WAF, or scrape from inside the VPC with an ADOT sidecar).

## Environment isolation

Same code, different state and variables. Recommended: one AWS account per environment (AWS Organizations, SCPs), separate state bucket and lock table, separate KMS key, separate VPC CIDR. Resource names embed `ticketing-<env>` so even a shared account does not collide. The app learns the table and queue names from environment variables, so no code change is needed between environments. Promote by applying the same module version to prod with a reviewed plan (CI with OIDC, no long-lived keys).

| Aspect | dev | prod |
|---|---|---|
| NAT gateways | 1 shared | 1 per AZ |
| Tasks min/max | 1 / 4 | 2 / 20 |
| Task size | 0.5 vCPU / 1 GB | 1 vCPU / 2 GB |
| HTTPS / WAF | optional (off) | required / on |
| Deletion protection | off | on |
| Log retention | 14 days | 90 days |

## Cost considerations (us-east-1, indicative)

- NAT gateway is the largest fixed item (about 32 USD/month each plus data processing). Dev uses one; the interface endpoints (5 endpoints x 2 AZs, roughly 7 USD each per month) partly replace NAT traffic. For a tiny dev, drop interface endpoints or NAT, not both.
- Fargate: pay per vCPU/GB-hour; ARM64 (Graviton) is about 20% cheaper (`cpu_architecture = "ARM64"` with an ARM image). Fargate Spot is an option for non-critical dev tasks.
- DynamoDB on-demand is pay per request; PITR adds storage-proportional cost. ALB has an hourly plus LCU charge; WAF has a fixed monthly plus per-request charge (prod only).
- Tags (`Project`, `Environment`, `ManagedBy`, `Owner`, `CostCenter`) are applied through `default_tags` for cost allocation.

## Known gaps

No CI pipeline is included. Not provided: Route 53 record and ACM certificate creation (pass `certificate_arn`), ALB access logs bucket, CloudWatch Logs KMS encryption, a prod dashboard, multi-region replication. See the root README, section Improvement scenarios.
