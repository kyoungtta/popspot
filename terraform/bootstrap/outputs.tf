output "state_bucket_name" {
  description = "생성된 state 버킷 이름"
  value       = aws_s3_bucket.tfstate.id
}

# apply 직후 이 내용을 terraform/backend.hcl 로 저장하면 루트 모듈 init에 그대로 쓸 수 있다.
output "backend_hcl" {
  description = "루트 모듈의 backend.hcl 에 넣을 내용"
  value       = <<-EOT
    bucket       = "${aws_s3_bucket.tfstate.id}"
    key          = "${var.project_name}/terraform.tfstate"
    region       = "${var.aws_region}"
    encrypt      = true
    use_lockfile = true
  EOT
}
