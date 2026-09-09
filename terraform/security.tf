# ─────────────────────────────────────────────────────────────────────────────
# 보안 그룹
#
# 인라인 ingress/egress 블록 대신 별도 규칙 리소스를 쓴다.
# 인라인 방식은 규칙 하나만 바꿔도 전체가 교체되고, 콘솔에서 수동 추가한 규칙과
# 충돌하면 조용히 지워버린다.
# ─────────────────────────────────────────────────────────────────────────────

resource "aws_security_group" "ec2" {
  # create_before_destroy와 함께 쓰려면 고정 name이 아니라 name_prefix여야 한다.
  # 고정 name이면 교체 시 같은 이름의 SG를 먼저 만들려다 충돌한다.
  name_prefix = "${local.name}-ec2-sg-"
  description = "Nginx(80/443) and SSH for the app server"
  vpc_id      = aws_vpc.main.id

  tags = { Name = "${local.name}-ec2-sg" }

  # 이름이 같은 SG를 만들 수 없으므로, 교체 시 새로 만든 뒤 기존 것을 지운다.
  lifecycle {
    create_before_destroy = true
  }
}

resource "aws_vpc_security_group_ingress_rule" "ec2_ssh" {
  security_group_id = aws_security_group.ec2.id
  # GitHub Actions 러너에서 접속해야 해서 전체 개방이다.
  # 비밀번호 인증은 user_data에서 꺼두고 fail2ban으로 무차별 대입을 차단한다.
  description = "SSH for CD runner and operator"

  cidr_ipv4   = var.ssh_allowed_cidr
  ip_protocol = "tcp"
  from_port   = 22
  to_port     = 22
}

resource "aws_vpc_security_group_ingress_rule" "ec2_http" {
  security_group_id = aws_security_group.ec2.id
  description       = "HTTP for Nginx and ACME challenge"

  cidr_ipv4   = "0.0.0.0/0"
  ip_protocol = "tcp"
  from_port   = 80
  to_port     = 80
}

resource "aws_vpc_security_group_ingress_rule" "ec2_https" {
  security_group_id = aws_security_group.ec2.id
  description       = "HTTPS for Nginx"

  cidr_ipv4   = "0.0.0.0/0"
  ip_protocol = "tcp"
  from_port   = 443
  to_port     = 443
}

# 앱 포트 8080/8081은 의도적으로 열지 않는다.
# Nginx가 EC2 호스트에서 직접 돌면서 127.0.0.1로 프록시하므로 외부 노출이 필요 없다.

resource "aws_vpc_security_group_egress_rule" "ec2_all" {
  security_group_id = aws_security_group.ec2.id
  description       = "Allow all outbound (GHCR pull, S3, package install)"

  cidr_ipv4   = "0.0.0.0/0"
  ip_protocol = "-1"
}

# MySQL·Redis용 보안그룹은 없다.
# 두 컨테이너는 호스트 포트를 열지 않고 Docker 네트워크(popspot-net) 안에만 있으므로
# VPC 보안그룹의 통제 대상이 아니다.
