output "ec2_public_ip" {
  description = "EC2에 붙은 Elastic IP. GitHub Secrets의 EC2_HOST 값"
  value       = aws_eip.app.public_ip
}

output "ec2_instance_id" {
  description = "EC2 인스턴스 ID (SSM 접속 시 사용)"
  value       = aws_instance.app.id
}

output "mysql_data_volume_id" {
  description = "MySQL 데이터 EBS 볼륨 ID. 인스턴스를 교체해도 이 볼륨을 다시 붙이면 데이터가 유지된다"
  value       = aws_ebs_volume.mysql_data.id
}

output "s3_bucket_name" {
  description = "이미지 버킷 이름. GitHub Secrets의 AWS_S3_BUCKET 값"
  value       = aws_s3_bucket.images.id
}

output "vpc_id" {
  description = "VPC ID"
  value       = aws_vpc.main.id
}

# apply 후 이 값을 그대로 GitHub Secrets에 옮겨 담으면 된다.
# 비밀번호는 여기 넣지 않는다 (state/콘솔 출력에 남지 않도록).
output "github_secrets" {
  description = "GitHub Secrets에 설정할 값 (비밀번호 제외)"
  value = {
    EC2_HOST = aws_eip.app.public_ip
    EC2_USER = "ubuntu"

    # MySQL은 같은 Docker 네트워크의 컨테이너라 호스트명이 컨테이너 이름이다.
    DB_HOST     = "mysql"
    DB_NAME     = var.db_name
    DB_USERNAME = var.db_username

    AWS_REGION    = var.aws_region
    AWS_S3_BUCKET = aws_s3_bucket.images.id
  }
}

output "ssh_command" {
  description = "EC2 접속 명령"
  value       = "ssh -i <경로>/${var.key_pair_name}.pem ubuntu@${aws_eip.app.public_ip}"
}
