# ─────────────────────────────────────────────────────────────────────────────
# 이미지 저장용 S3
#
# 버킷은 완전 비공개이고, 클라이언트는 Presigned URL로만 읽고 쓴다.
# ─────────────────────────────────────────────────────────────────────────────

resource "aws_s3_bucket" "images" {
  bucket = local.images_bucket_name

  tags = { Name = local.images_bucket_name }
}

resource "aws_s3_bucket_public_access_block" "images" {
  bucket = aws_s3_bucket.images.id

  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true
}

resource "aws_s3_bucket_server_side_encryption_configuration" "images" {
  bucket = aws_s3_bucket.images.id

  rule {
    apply_server_side_encryption_by_default {
      sse_algorithm = "AES256"
    }
  }
}

# 브라우저가 Presigned PUT을 보내기 전에 preflight(OPTIONS)를 던지므로 CORS가 필요하다.
resource "aws_s3_bucket_cors_configuration" "images" {
  bucket = aws_s3_bucket.images.id

  cors_rule {
    allowed_origins = var.s3_cors_allowed_origins
    allowed_methods = ["PUT", "GET"]
    allowed_headers = ["*"]
    expose_headers  = ["ETag"]
    max_age_seconds = 3000
  }
}

# S3Service가 temp/ 에 먼저 올린 뒤 확정 시 실제 키로 move 한다.
# 확정되지 않고 남은 객체를 정리한다.
resource "aws_s3_bucket_lifecycle_configuration" "images" {
  bucket = aws_s3_bucket.images.id

  rule {
    id     = "expire-unconfirmed-temp-objects"
    status = "Enabled"

    filter {
      prefix = "temp/"
    }

    expiration {
      days = var.s3_temp_expiration_days
    }
  }

  # 중단된 멀티파트 업로드는 보이지 않게 요금이 쌓인다.
  rule {
    id     = "abort-incomplete-multipart-uploads"
    status = "Enabled"

    filter {}

    abort_incomplete_multipart_upload {
      days_after_initiation = 7
    }
  }
}
