# ─────────────────────────────────────────────────────────────────────────────
# State 백엔드 부트스트랩
#
# 루트 모듈의 state를 담을 S3 버킷을 만든다.
# 자기 자신의 state를 담을 버킷이라 순환 문제가 있으므로, 이 모듈만 로컬 state를
# 쓰고 루트 모듈과 디렉터리를 분리한다. 최초 1회만 apply하면 이후 건드릴 일이 없다.
#
#   terraform -chdir=terraform/bootstrap init
#   terraform -chdir=terraform/bootstrap apply
# ─────────────────────────────────────────────────────────────────────────────

terraform {
  required_version = ">= 1.11"

  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 6.0"
    }
  }
}

provider "aws" {
  region = var.aws_region

  default_tags {
    tags = var.common_tags
  }
}

data "aws_caller_identity" "current" {}

locals {
  # 버킷 이름은 전역 유니크여야 하므로 계정 ID를 붙여 충돌을 피한다.
  bucket_name = coalesce(var.state_bucket_name, "${var.project_name}-tfstate-${data.aws_caller_identity.current.account_id}")
}

resource "aws_s3_bucket" "tfstate" {
  bucket = local.bucket_name

  # state를 잃으면 인프라를 코드로 관리할 수 없게 된다. 실수로 destroy되지 않게 막는다.
  lifecycle {
    prevent_destroy = true
  }

  tags = { Name = local.bucket_name }
}

# 잘못된 apply로 state가 깨졌을 때 이전 버전으로 되돌릴 수 있는 유일한 수단.
resource "aws_s3_bucket_versioning" "tfstate" {
  bucket = aws_s3_bucket.tfstate.id

  versioning_configuration {
    status = "Enabled"
  }
}

# state에는 RDS 비밀번호 등이 평문으로 들어간다. 저장 시 암호화는 필수.
resource "aws_s3_bucket_server_side_encryption_configuration" "tfstate" {
  bucket = aws_s3_bucket.tfstate.id

  rule {
    apply_server_side_encryption_by_default {
      sse_algorithm = "AES256"
    }
  }
}

resource "aws_s3_bucket_public_access_block" "tfstate" {
  bucket = aws_s3_bucket.tfstate.id

  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true
}

# 오래된 state 버전이 무한정 쌓이지 않게 정리한다.
resource "aws_s3_bucket_lifecycle_configuration" "tfstate" {
  bucket = aws_s3_bucket.tfstate.id

  rule {
    id     = "expire-old-state-versions"
    status = "Enabled"

    filter {}

    noncurrent_version_expiration {
      noncurrent_days = 90
    }
  }
}
