# ─────────────────────────────────────────────────────────────────────────────
# MySQL을 EC2 컨테이너로 돌리기 위한 부속 리소스
#
# 컨테이너 자체는 user_data가 띄운다. 여기서는 그 컨테이너가 필요로 하는
# 두 가지를 만든다.
#
#   1. 데이터를 담을 EBS 볼륨 (인스턴스와 생명주기 분리)
#   2. DB 비밀번호를 담을 SSM 파라미터 (user_data 평문 노출 회피)
# ─────────────────────────────────────────────────────────────────────────────

# ─── 데이터 볼륨 ──────────────────────────────────────────────────────────────
#
# 루트 볼륨에 두면 인스턴스를 교체하는 순간 DB가 통째로 사라진다.
# 별도 볼륨으로 분리하면 인스턴스를 새로 만들어도 붙여서 그대로 쓸 수 있다.
# (AZ 안에서만 이동 가능하므로 EC2와 같은 AZ에 만든다)

resource "aws_ebs_volume" "mysql_data" {
  availability_zone = aws_subnet.public[0].availability_zone
  size              = var.mysql_data_volume_size
  type              = "gp3"
  encrypted         = true

  # 인스턴스를 갈아엎어도 이 볼륨은 남아야 한다.
  lifecycle {
    prevent_destroy = true
  }

  tags = { Name = "${local.name}-mysql-data" }
}

resource "aws_volume_attachment" "mysql_data" {
  device_name = "/dev/sdf"
  volume_id   = aws_ebs_volume.mysql_data.id
  instance_id = aws_instance.app.id

  # 볼륨을 강제 분리하지 않는다. 파일시스템이 마운트된 채로 떼면 손상될 수 있다.
  # 인스턴스를 교체할 땐 먼저 MySQL 컨테이너를 멈추고 umount 할 것.
  force_detach = false
}

# ─── DB 비밀번호 ──────────────────────────────────────────────────────────────
#
# user_data는 provider v6부터 state에 평문으로 저장되고, EC2 API
# (DescribeInstanceAttribute)로도 그대로 읽힌다. 비밀번호를 거기 넣는 대신
# SSM에 SecureString으로 두고, 인스턴스가 자기 IAM 역할로 부팅 시 꺼내가게 한다.

resource "aws_ssm_parameter" "db_password" {
  name        = "/${local.name}/db/password"
  description = "MySQL application user password"
  type        = "SecureString"
  value       = var.db_password

  tags = { Name = "${local.name}-db-password" }
}
