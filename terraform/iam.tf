# ─────────────────────────────────────────────────────────────────────────────
# EC2 인스턴스 역할
#
# 이 역할 덕분에 앱에 AWS 액세스 키를 주입할 필요가 없다.
# S3Config가 access-key가 비어 있으면 DefaultCredentialsProvider로 폴백하고,
# 그 체인이 인스턴스 프로파일 자격증명을 IMDS에서 읽어간다.
# ─────────────────────────────────────────────────────────────────────────────

data "aws_iam_policy_document" "ec2_assume_role" {
  statement {
    effect  = "Allow"
    actions = ["sts:AssumeRole"]

    principals {
      type        = "Service"
      identifiers = ["ec2.amazonaws.com"]
    }
  }
}

resource "aws_iam_role" "ec2" {
  name               = "${local.name}-ec2-role"
  assume_role_policy = data.aws_iam_policy_document.ec2_assume_role.json

  tags = { Name = "${local.name}-ec2-role" }
}

# AmazonS3FullAccess 같은 광범위한 관리형 정책 대신, 이 버킷에만 필요한 동작을 준다.
data "aws_iam_policy_document" "s3_access" {
  statement {
    sid    = "ObjectAccess"
    effect = "Allow"

    actions = [
      "s3:GetObject",
      "s3:PutObject",
      "s3:DeleteObject",
    ]

    resources = ["${aws_s3_bucket.images.arn}/*"]
  }

  # Presigned URL 발급과 objects 조회에 필요.
  statement {
    sid       = "BucketAccess"
    effect    = "Allow"
    actions   = ["s3:ListBucket", "s3:GetBucketLocation"]
    resources = [aws_s3_bucket.images.arn]
  }
}

resource "aws_iam_role_policy" "s3_access" {
  name   = "${local.name}-s3-access"
  role   = aws_iam_role.ec2.id
  policy = data.aws_iam_policy_document.s3_access.json
}

# ─── DB 비밀번호 조회 ─────────────────────────────────────────────────────────
# user_data가 부팅 시 SSM에서 MySQL 비밀번호를 꺼내는 데 필요하다.

data "aws_iam_policy_document" "db_password_access" {
  statement {
    sid       = "ReadDbPassword"
    effect    = "Allow"
    actions   = ["ssm:GetParameter"]
    resources = [aws_ssm_parameter.db_password.arn]
  }

  # SecureString은 KMS로 암호화되어 있어 복호화 권한이 따로 필요하다.
  # SSM을 거친 호출로만 쓸 수 있게 조건으로 묶는다.
  statement {
    sid       = "DecryptViaSsm"
    effect    = "Allow"
    actions   = ["kms:Decrypt"]
    resources = ["*"]

    condition {
      test     = "StringEquals"
      variable = "kms:ViaService"
      values   = ["ssm.${var.aws_region}.amazonaws.com"]
    }
  }
}

resource "aws_iam_role_policy" "db_password_access" {
  name   = "${local.name}-db-password-access"
  role   = aws_iam_role.ec2.id
  policy = data.aws_iam_policy_document.db_password_access.json
}

# SSM Session Manager. SSH 키를 잃어버려도 콘솔에서 셸에 붙을 수 있는 안전장치.
resource "aws_iam_role_policy_attachment" "ssm" {
  role       = aws_iam_role.ec2.name
  policy_arn = "arn:aws:iam::aws:policy/AmazonSSMManagedInstanceCore"
}

resource "aws_iam_instance_profile" "ec2" {
  name = "${local.name}-ec2-profile"
  role = aws_iam_role.ec2.name

  tags = { Name = "${local.name}-ec2-profile" }
}
