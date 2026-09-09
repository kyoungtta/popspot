# ─────────────────────────────────────────────────────────────────────────────
# 네트워크
#
# EC2 한 대만 퍼블릭 서브넷에 두고 EIP로 노출한다.
# MySQL·Redis는 같은 인스턴스의 Docker 네트워크 안에만 있으므로
# 별도 서브넷도, NAT Gateway도 필요 없다.
#
# 서브넷을 2개 만드는 이유: 나중에 AZ를 옮기거나 ALB를 붙일 때
# 서브넷을 새로 만들며 VPC를 건드리지 않아도 되게 하기 위함.
# ─────────────────────────────────────────────────────────────────────────────

resource "aws_vpc" "main" {
  cidr_block           = var.vpc_cidr
  enable_dns_support   = true
  enable_dns_hostnames = true

  tags = { Name = "${local.name}-vpc" }
}

resource "aws_internet_gateway" "main" {
  vpc_id = aws_vpc.main.id

  tags = { Name = "${local.name}-igw" }
}

# ─── 퍼블릭 서브넷 (EC2) ──────────────────────────────────────────────────────

resource "aws_subnet" "public" {
  count = length(var.availability_zones)

  vpc_id                  = aws_vpc.main.id
  cidr_block              = var.public_subnet_cidrs[count.index]
  availability_zone       = var.availability_zones[count.index]
  map_public_ip_on_launch = true

  tags = { Name = "${local.name}-public-${var.availability_zones[count.index]}" }
}

resource "aws_route_table" "public" {
  vpc_id = aws_vpc.main.id

  route {
    cidr_block = "0.0.0.0/0"
    gateway_id = aws_internet_gateway.main.id
  }

  tags = { Name = "${local.name}-public-rt" }
}

resource "aws_route_table_association" "public" {
  count = length(aws_subnet.public)

  subnet_id      = aws_subnet.public[count.index].id
  route_table_id = aws_route_table.public.id
}
