# ─────────────────────────────────────────────────────────────────────────────
# 애플리케이션 서버
# ─────────────────────────────────────────────────────────────────────────────

data "aws_ami" "ubuntu" {
  most_recent = true
  owners      = ["099720109477"] # Canonical

  filter {
    name   = "name"
    values = ["ubuntu/images/hvm-ssd-gp3/ubuntu-noble-24.04-amd64-server-*"]
  }

  filter {
    name   = "virtualization-type"
    values = ["hvm"]
  }
}

resource "aws_instance" "app" {
  ami                    = data.aws_ami.ubuntu.id
  instance_type          = var.ec2_instance_type
  key_name               = var.key_pair_name
  subnet_id              = aws_subnet.public[0].id
  vpc_security_group_ids = [aws_security_group.ec2.id]
  iam_instance_profile   = aws_iam_instance_profile.ec2.name

  user_data = templatefile("${path.module}/templates/user_data.sh.tftpl", {
    aws_region     = var.aws_region
    docker_network = var.docker_network_name
    redis_image    = var.redis_image
    mysql_image    = var.mysql_image
    db_name        = var.db_name
    db_username    = var.db_username

    # 비밀번호가 아니라 "비밀번호가 든 파라미터의 이름"만 넘긴다.
    db_password_param = aws_ssm_parameter.db_password.name

    # Nitro 인스턴스의 by-id 링크는 볼륨 ID에서 하이픈을 뺀 형태다.
    #   vol-0abc123... -> nvme-Amazon_Elastic_Block_Store_vol0abc123...
    mysql_volume_id_nodash = replace(aws_ebs_volume.mysql_data.id, "-", "")
  })

  root_block_device {
    volume_size = var.root_volume_size
    volume_type = "gp3"
    encrypted   = true
  }

  metadata_options {
    http_tokens   = "required" # IMDSv2 강제
    http_endpoint = "enabled"

    # 앱이 Docker 컨테이너 안에서 돌기 때문에 IMDS까지 홉이 하나 더 필요하다.
    # 기본값 1이면 컨테이너에서 인스턴스 자격증명을 못 읽어 S3 호출이 전부 실패한다.
    http_put_response_hop_limit = 2
  }

  # user_data가 부팅하자마자 SSM에서 DB 비밀번호를 꺼낸다.
  # 인스턴스는 instance_profile만 참조하므로, 명시하지 않으면 역할 정책이 붙기 전에
  # 인스턴스가 먼저 떠서 get-parameter가 AccessDenied로 실패할 수 있다.
  depends_on = [
    aws_iam_role_policy.db_password_access,
    aws_iam_role_policy.s3_access,
  ]

  lifecycle {
    # Canonical이 새 AMI를 내면 data 소스 값이 바뀌어 인스턴스가 교체된다.
    # 의도적으로 갈아탈 때만 이 줄을 지우고 apply 한다.
    ignore_changes = [ami]
  }

  tags = { Name = "${local.name}-app" }
}

# EIP를 쓰는 이유: 인스턴스를 중지/시작해도 IP가 바뀌지 않아야
# GitHub Secrets의 EC2_HOST와 DNS 레코드를 다시 안 고친다.
# 2024-02부터 연결 여부와 무관하게 시간당 과금되므로, 인스턴스를 없앨 땐 EIP도 함께 해제할 것.
resource "aws_eip" "app" {
  instance = aws_instance.app.id
  domain   = "vpc"

  depends_on = [aws_internet_gateway.main]

  tags = { Name = "${local.name}-eip" }
}
