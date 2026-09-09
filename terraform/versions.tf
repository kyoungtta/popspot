terraform {
  # S3 네이티브 잠금(use_lockfile)이 정식 지원되는 최소 버전.
  # 이전 방식(DynamoDB 테이블)은 deprecated 되었다.
  required_version = ">= 1.11"

  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 6.0"
    }
  }

  # 값은 backend.hcl 로 주입한다 (계정마다 버킷 이름이 다르므로 부분 설정 사용).
  #   terraform init -backend-config=backend.hcl
  backend "s3" {}
}
