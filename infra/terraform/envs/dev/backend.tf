# Remote state: one S3 bucket + one DynamoDB lock table PER ENVIRONMENT (ideally in the environment's own
# AWS account). The block is intentionally partial so no account ids or bucket names live in code:
#
#   terraform init -backend-config=backend.hcl        (copy backend.hcl.example to backend.hcl first)
#
# For local validation without credentials: terraform init -backend=false
terraform {
  backend "s3" {}
}
