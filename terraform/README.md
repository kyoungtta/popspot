# PopSpot 인프라 (Terraform)

개인 AWS 계정에 PopSpot 운영 환경을 만든다.

## 구성

```
                       인터넷
                         │
                    EIP (고정 IP)
                         │
┌────────────────────────┼─────────── VPC 10.0.0.0/16 ──────────┐
│  퍼블릭 서브넷 (10.0.1.0/24, 10.0.2.0/24)                       │
│                                                               │
│  EC2 t3.small                                                 │
│    Nginx (80, 호스트)                                          │
│      └─ upstream 127.0.0.1:8080 또는 8081                      │
│                                                               │
│    ┌─────────── Docker network: popspot-net ───────────┐      │
│    │  popspot-blue (8080)   popspot-green (8081)       │      │
│    │  mysql                 redis                      │      │
│    └───────────────────────────────────────────────────┘      │
│                                                               │
│    EBS 데이터 볼륨 → /data/mysql (인스턴스와 생명주기 분리)      │
└───────────────────────────────────────────────────────────────┘
                         │
                        S3 (이미지, 비공개 + Presigned URL)
```

- EC2는 IAM 인스턴스 프로파일로 S3에 접근한다. **앱에 AWS 액세스 키를 넣지 않는다.**
- 앱 포트(8080/8081)는 보안그룹에서 열지 않는다. Nginx가 호스트에서 127.0.0.1로 프록시한다.
- MySQL·Redis는 호스트 포트를 열지 않는다. `popspot-net` 안의 컨테이너만 접근할 수 있다.
- MySQL 데이터는 루트 볼륨이 아니라 **별도 EBS 볼륨**에 둔다. 인스턴스를 교체해도 남는다.
- DB 비밀번호는 SSM SecureString에 두고 EC2가 부팅 시 IAM 역할로 꺼내 간다.
  `user_data`는 state와 EC2 API에서 평문으로 읽히므로 비밀값을 직접 넣지 않는다.
- **SSH(22)는 인터넷에 열려 있다.** `deploy.yml`이 GitHub 호스팅 러너에서 접속하는데
  그 IP 대역을 보안그룹으로 좁힐 수 없기 때문이다. 대신 비밀번호 인증을 끄고
  (키 인증만 허용) fail2ban으로 무차별 대입을 차단한다.

## 파일

| 파일 | 내용 |
|---|---|
| `versions.tf` | Terraform/provider 버전, S3 백엔드 선언 |
| `providers.tf` | provider 설정, `default_tags`, 공통 local |
| `network.tf` | VPC, IGW, 퍼블릭 서브넷, 라우트 테이블 |
| `security.tf` | EC2 보안그룹 |
| `iam.tf` | EC2 역할 (S3 최소권한, SSM 파라미터 조회, Session Manager) |
| `compute.tf` | AMI 조회, EC2, EIP |
| `mysql.tf` | MySQL 데이터 EBS 볼륨, DB 비밀번호 SSM 파라미터 |
| `storage.tf` | 이미지 S3 (비공개/암호화/CORS/lifecycle) |
| `templates/user_data.sh.tftpl` | 부팅 시 Docker·MySQL·Redis·Nginx 설치 및 설정 |
| `bootstrap/` | state 저장용 S3 버킷 (최초 1회) |

## 실행 순서

### 0. 사전 준비

```bash
brew tap hashicorp/tap && brew install hashicorp/tap/terraform
brew install awscli
aws configure                 # 개인 계정 자격증명
aws sts get-caller-identity   # 개인 계정이 맞는지 반드시 확인
```

EC2 키페어를 콘솔에서 미리 만들고 `.pem`을 안전한 곳에 둔다.

### 1. state 버킷 생성 (최초 1회)

```bash
terraform -chdir=terraform/bootstrap init
terraform -chdir=terraform/bootstrap apply
terraform -chdir=terraform/bootstrap output -raw backend_hcl > terraform/backend.hcl
```

### 2. 변수 설정

```bash
cd terraform
cp terraform.tfvars.example terraform.tfvars
$EDITOR terraform.tfvars     # key_pair_name, db_password 두 개만 채우면 된다
```

### 3. 인프라 생성

```bash
terraform init -backend-config=backend.hcl
terraform plan               # 생성될 리소스와 비용 항목을 눈으로 확인
terraform apply
```

### 4. 출력값을 GitHub Secrets로

```bash
terraform output github_secrets
```

| Secret | 값 |
|---|---|
| `EC2_HOST` | `terraform output ec2_public_ip` |
| `EC2_USER` | `ubuntu` |
| `EC2_SSH_KEY` | 키페어 `.pem` 파일 **내용 전체** |
| `DB_HOST` | `mysql` (컨테이너 이름 — deploy.yml에 고정되어 있어 실제로는 불필요) |
| `DB_NAME` | `popspot_db` |
| `DB_USERNAME` | `popspot` (tfvars 값) |
| `DB_PASSWORD` | tfvars의 `db_password` (SSM에 넣은 값과 동일해야 함) |
| `AWS_REGION` | `ap-northeast-2` |
| `AWS_S3_BUCKET` | `terraform output s3_bucket_name` |
| `JWT_SECRET`, `GOOGLE_*`, `TOSS_*` | 기존 값 |

`AWS_ACCESS_KEY_ID` / `AWS_SECRET_ACCESS_KEY`는 **더 이상 필요 없다.** IAM 인스턴스 프로파일이 대신한다.

### 5. 확인

```bash
ssh -i <키>.pem ubuntu@$(terraform output -raw ec2_public_ip)

# user_data가 끝났는지
tail -f /var/log/cloud-init-output.log

# 준비 상태 확인
docker network ls | grep popspot-net
docker ps | grep redis
sudo nginx -t
cat /etc/nginx/conf.d/popspot.conf
```

그 다음 `main`에 push하면 `deploy.yml`이 블루/그린으로 배포한다.

## 운영 메모

**apply 전 계정 확인.** 의도한 계정이 맞는지 매번 확인한다.

```bash
aws sts get-caller-identity
```

**인스턴스 타입 변경**은 `ec2_instance_type` 한 줄이다. t3.small(2GB)에 MySQL·Redis·앱
컨테이너가 함께 올라가고, 블루/그린 전환 순간에는 앱이 2개가 된다. 메모리가 모자라면
`t3.medium`으로 올린다. 재시작을 동반한다.

**user_data는 최초 부팅 때만 실행된다.** 스크립트를 고쳐도 기존 인스턴스에는 반영되지 않는다.
반영하는 방법은 두 가지다.

1. 인스턴스 교체 (권장 — 클린 부팅에서 동작하는지까지 확인됨)

```bash
# 데이터 볼륨을 안전하게 떼고
aws ssm send-command --instance-ids <id> --document-name AWS-RunShellScript   --parameters 'commands=["docker stop mysql redis","sync","umount /data"]'
terraform apply -replace=aws_instance.app
```

2. 기존 인스턴스에서 직접 실행 (빠른 확인용)
   스크립트는 재실행 가능하도록 작성되어 있다(포맷 방지, 덮어쓰기 처리 등).

**SSH 키가 없어도 인스턴스에 붙을 수 있다.** IAM 역할에 SSM Session Manager 권한이 있다.

```bash
aws ssm start-session --target $(terraform output -raw ec2_instance_id)
```

**DB 백업은 직접 해야 한다.** RDS와 달리 자동 백업이 없다. 최소한 정기 덤프를 걸어둘 것.

```bash
docker exec mysql mysqldump -u popspot -p popspot_db > backup-$(date +%F).sql
```

**인스턴스를 교체할 때**는 데이터 볼륨을 안전하게 떼야 한다. 마운트된 채로 강제 분리하면
파일시스템이 손상될 수 있다.

```bash
docker stop mysql && sudo umount /data
```

새 인스턴스가 뜨면 `user_data`가 같은 볼륨을 다시 마운트한다. 이미 데이터가 있으면
포맷하지 않고 그대로 쓴다(`blkid` 검사).

**정리할 때**는 `terraform destroy`. 단 아래 둘은 `prevent_destroy`가 걸려 있어
의도적으로 지우려면 코드에서 해당 줄을 먼저 지워야 한다.

- `bootstrap`의 state 버킷
- MySQL 데이터 EBS 볼륨

EIP는 인스턴스가 사라져도 남아 있으면 계속 과금되므로 해제를 확인한다.

## 비용 (서울 리전, 개략 추정)

| 항목 | 월 |
|---|---|
| EC2 t3.small | ~$19 |
| EBS 루트 20GB + 데이터 20GB (gp3) | ~$3.2 |
| 퍼블릭 IPv4 (EIP) | ~$3.7 |
| S3 / state 버킷 | ~$1 |
| **합계** | **~$27** |

정확한 값은 AWS Pricing Calculator로 확인할 것. 예산 알림(Budget) 설정을 권장한다.
