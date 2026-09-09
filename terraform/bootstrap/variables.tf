variable "aws_region" {
  description = "리소스를 생성할 AWS 리전"
  type        = string
  default     = "ap-northeast-2"
}

variable "project_name" {
  description = "리소스 네이밍 prefix"
  type        = string
  default     = "popspot"
}

variable "state_bucket_name" {
  description = "state 버킷 이름. null이면 '<project>-tfstate-<계정ID>'로 자동 생성 (전역 유니크 보장)"
  type        = string
  default     = null
}

variable "common_tags" {
  description = "모든 리소스에 붙일 공통 태그"
  type        = map(string)
  default = {
    Project   = "popspot"
    ManagedBy = "terraform"
  }
}
