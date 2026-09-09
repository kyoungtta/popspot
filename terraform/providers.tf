provider "aws" {
  region = var.aws_region

  # 공통 태그는 여기서 한 번만 선언한다. 리소스마다 merge() 할 필요가 없다.
  default_tags {
    tags = var.common_tags
  }
}

data "aws_caller_identity" "current" {}

locals {
  name = var.project_name

  # S3 버킷 이름은 전역 유니크여야 하므로, 지정하지 않으면 계정 ID를 붙인다.
  images_bucket_name = coalesce(
    var.s3_bucket_name,
    "${var.project_name}-images-${data.aws_caller_identity.current.account_id}"
  )
}
