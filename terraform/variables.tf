##############################################
# 공통
##############################################

variable "aws_region" {
  description = "리소스를 생성할 AWS 리전"
  type        = string
  default     = "ap-northeast-2" # 서울
}

variable "project_name" {
  description = "모든 리소스 이름의 prefix"
  type        = string
  default     = "popspot"
}

variable "common_tags" {
  description = "모든 리소스에 자동으로 붙는 공통 태그 (provider default_tags)"
  type        = map(string)
  default = {
    Project   = "popspot"
    ManagedBy = "terraform"
  }
}

##############################################
# 네트워크
##############################################

variable "vpc_cidr" {
  description = "VPC CIDR 블록"
  type        = string
  default     = "10.0.0.0/16"
}

variable "availability_zones" {
  description = "사용할 가용영역. 서브넷을 만들 AZ 목록"
  type        = list(string)
  default     = ["ap-northeast-2a", "ap-northeast-2c"]

  validation {
    condition     = length(var.availability_zones) >= 2
    error_message = "가용영역은 2개 이상 지정하세요."
  }
}

variable "public_subnet_cidrs" {
  description = "퍼블릭 서브넷 CIDR. availability_zones와 같은 순서/개수"
  type        = list(string)
  default     = ["10.0.1.0/24", "10.0.2.0/24"]
}

##############################################
# EC2
##############################################

variable "ec2_instance_type" {
  description = "EC2 인스턴스 타입. 블루/그린 전환 순간 앱 컨테이너 2개가 동시에 뜬다"
  type        = string
  default     = "t3.small"
}

variable "key_pair_name" {
  description = "EC2 접속용 키페어 이름. 개인 계정에 미리 생성해 둘 것 (Private Key는 state에 남기지 않는다)"
  type        = string
}

variable "ssh_allowed_cidr" {
  description = <<-EOT
    SSH(22) 접근을 허용할 CIDR.

    기본값이 전체 공개인 이유: deploy.yml이 GitHub 호스팅 러너(runs-on: ubuntu-latest)에서
    SSH로 접속하는데, 그 러너의 출구 IP는 매번 바뀌고 대역이 수천 개라 보안그룹으로
    좁힐 수 없다. 여기를 본인 IP/32로 바꾸면 CD가 타임아웃으로 실패한다.

    좁히고 싶다면 배포를 SSM send-command 방식으로 바꿔야 한다.
  EOT
  type        = string
  default     = "0.0.0.0/0"
}

variable "root_volume_size" {
  description = "EC2 루트 볼륨 크기(GB). Docker 이미지가 SHA 태그로 쌓이므로 여유를 둔다"
  type        = number
  default     = 20
}

variable "docker_network_name" {
  description = "앱/Redis가 붙는 Docker 네트워크 이름 (deploy.yml의 --network 값과 일치해야 함)"
  type        = string
  default     = "popspot-net"
}

variable "redis_image" {
  description = "EC2에 띄울 Redis 이미지"
  type        = string
  default     = "redis:7-alpine"
}

##############################################
# MySQL (EC2 내 컨테이너)
##############################################

variable "mysql_image" {
  description = "EC2에 띄울 MySQL 이미지"
  type        = string
  default     = "mysql:8.0"
}

variable "mysql_data_volume_size" {
  description = "MySQL 데이터용 EBS 볼륨 크기(GB). 인스턴스와 분리되어 교체 시에도 남는다"
  type        = number
  default     = 20
}

variable "db_name" {
  description = "생성할 데이터베이스 이름"
  type        = string
  default     = "popspot_db"
}

variable "db_username" {
  description = "앱이 사용할 MySQL 유저명 (root가 아닌 전용 계정)"
  type        = string
  default     = "popspot"
}

variable "db_password" {
  description = "앱 MySQL 유저 비밀번호. terraform.tfvars로만 주입하며 SSM SecureString에 저장된다"
  type        = string
  sensitive   = true

  validation {
    condition     = length(var.db_password) >= 8
    error_message = "비밀번호는 8자 이상이어야 합니다."
  }
}

##############################################
# S3 (이미지 저장용)
##############################################

variable "s3_bucket_name" {
  description = "이미지 버킷 이름. null이면 '<project>-images-<계정ID>'로 자동 생성 (전역 유니크 보장)"
  type        = string
  default     = null
}

variable "s3_cors_allowed_origins" {
  description = "Presigned PUT preflight를 허용할 Origin 목록 (프론트엔드 URL)"
  type        = list(string)
  default     = ["http://localhost:3000"]
}

variable "s3_temp_expiration_days" {
  description = "temp/ 프리픽스에 남은 미확정 업로드 객체를 삭제하기까지의 일수"
  type        = number
  default     = 3
}
