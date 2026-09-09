# Terraform 인프라 구축 계획

> 갱신: 2026-09-09 · 대상: `kyoungtta/popspot` (개인 AWS 계정)

수동으로 구성해 둔 EC2 운영 환경을 Terraform으로 새로 만들고 갈아탄다.
기존 인프라는 `import` 하지 않고, 새로 만든 뒤 검증하고 옛 것을 내린다.

---

## 1. 구성

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
│    EBS 데이터 볼륨 → /data/mysql                                │
└───────────────────────────────────────────────────────────────┘
                         │
                        S3 (이미지, 비공개 + Presigned URL)
```

### 확정된 결정

| # | 항목 | 결정 | 근거 |
|---|---|---|---|
| A | DB | **EC2 내 MySQL 컨테이너** | 비용 우선. RDS 대비 월 ~$21 절감. 데이터는 별도 EBS 볼륨으로 분리해 인스턴스 교체에 대비 |
| B | 프록시 | **Nginx만** | `deploy.yml`이 Nginx upstream 포트를 sed로 바꾸는 방식. HAProxy는 쓰지 않음 |
| C | state | **S3 원격 + 네이티브 잠금** | `terraform/bootstrap/`이 버킷 생성. 버저닝·암호화·90일 구버전 정리 |
| D | Actions ↔ AWS | **인증 불필요** | `deploy.yml`은 SSH로만 배포. 앱의 S3 접근은 EC2 IAM 인스턴스 프로파일 |
| E | 인스턴스 | **t3.small** | 변수 한 줄로 승격 가능 |
| F | 도메인/HTTPS | **Phase 4에서 결정** | 우선 IP + 80으로 검증 |
| G | EC2 초기 설정 | **user_data** | Docker·MySQL·Redis·Nginx 자동 구성 |
| H | 모니터링 | **보류** | 메모리 여유 확인 후 |
| I | 기존 EC2 | **새로 만들고 교체** | import 안 함 |

### 비용 (서울, 개략 추정)

| 항목 | 월 |
|---|---|
| EC2 t3.small | ~$19 |
| EBS 루트 20GB + 데이터 20GB (gp3) | ~$3.2 |
| 퍼블릭 IPv4 (EIP) | ~$3.7 |
| S3 / state 버킷 | ~$1 |
| **합계** | **~$27** |

---

## 2. 진행 상황

### 완료 — Terraform 코드 작성

`terraform/` 전체를 새로 작성했다. AWS provider 6.63.0 스키마로 검증 완료.

- `terraform fmt -recursive` 통과 (diff 없음)
- `terraform validate` 통과 (루트 모듈, bootstrap 모듈)
- `user_data` 템플릿 렌더링 + `bash -n` 문법 검사 통과, 미치환 플레이스홀더 없음

작성 중 잡은 문제 셋:

1. **IMDS hop limit** — IMDSv2를 강제하면서 `http_put_response_hop_limit`을 기본값 1로 두면
   Docker 컨테이너 안의 앱이 인스턴스 자격증명을 못 읽어 S3 호출이 전부 실패한다. 2로 설정.
2. **보안그룹 `create_before_destroy` + 고정 `name`** — 교체 시 같은 이름으로 먼저 만들려다
   충돌한다. `name_prefix`로 변경.
3. **IAM 정책 경쟁 조건** — 인스턴스는 `instance_profile`만 참조하므로, 역할 정책이 붙기 전에
   부팅해 `user_data`의 SSM 조회가 AccessDenied로 실패할 수 있다. `depends_on` + 재시도 추가.

### 남은 작업

#### Phase 0 — 사전 준비

- [ ] `brew tap hashicorp/tap && brew install hashicorp/tap/terraform awscli`
- [ ] `aws configure` → `aws sts get-caller-identity`로 개인 계정 확인
- [ ] EC2 키페어 생성, `.pem` 안전 보관
- [ ] AWS Budget 알림 설정 (월 $40 권장)

#### Phase 1 — state 버킷

- [ ] `terraform -chdir=terraform/bootstrap init && apply`
- [ ] `terraform -chdir=terraform/bootstrap output -raw backend_hcl > terraform/backend.hcl`

#### Phase 2 — 인프라 생성

- [ ] `cp terraform.tfvars.example terraform.tfvars` 후 2개 값 기입
      (`key_pair_name`, `db_password`)
- [ ] `terraform init -backend-config=backend.hcl`
- [ ] `terraform plan` — 생성될 리소스와 비용 항목 확인
- [ ] `terraform apply`
- [ ] SSH 접속 후 `/var/log/cloud-init-output.log`로 `user_data` 완료 확인
- [ ] `docker ps`에 `mysql`, `redis` / `mount | grep /data` / `sudo nginx -t` 확인

#### Phase 3 — 배포 연결

- [ ] GitHub Secrets 갱신 (`terraform output github_secrets` 참고)
      - `EC2_HOST`, `EC2_USER`, `EC2_SSH_KEY`, `DB_NAME`, `DB_USERNAME`, `DB_PASSWORD`,
        `AWS_REGION`, `AWS_S3_BUCKET`, `JWT_SECRET`, `GOOGLE_*`, `TOSS_*`
      - `AWS_ACCESS_KEY_ID` / `AWS_SECRET_ACCESS_KEY`는 **삭제** (IAM 역할이 대신함)
- [ ] `main` push → 블루/그린 배포 성공 확인
- [ ] `curl http://<EIP>/actuator/health`

#### Phase 4 — 도메인 · 외부 연동

- [ ] DuckDNS(또는 도메인) → EIP 연결
- [ ] Certbot으로 HTTPS 발급, Nginx 443 설정
- [ ] `application-prod.yml`의 Google redirect-uri를 새 도메인으로 변경
- [ ] **Google Cloud Console에 새 redirect URI 등록** (빠뜨리면 로그인 전면 실패)
- [ ] `s3_cors_allowed_origins`를 실제 프론트엔드 URL로 변경

#### Phase 5 — 정리

- [ ] 기존 수동 EC2 종료 (**EIP도 해제** — 안 그러면 계속 과금)
- [ ] `docs/infra-architecture.md`, `docs/infra-ops-guide.md`를 현재 구조로 갱신
- [ ] `.env.example`의 죽은 변수명 수정 (아래 3절)
- [ ] `README.md` 인프라 설명 갱신

---

## 3. 코드에서 확인된 사항

### 앱이 요구하는 환경변수 (`application.yml` 실측)

| 변수 | 기본값 | 공급 주체 |
|---|---|---|
| `SPRING_DATASOURCE_URL/USERNAME/PASSWORD` | 없음 (prod에 datasource 블록 없음) | `deploy.yml` |
| `REDIS_HOST` / `REDIS_PORT` | `localhost` / `6379` | `deploy.yml` → redis 컨테이너 |
| `AWS_REGION`, `AWS_S3_BUCKET` | **없음 → 누락 시 기동 실패** | `deploy.yml` |
| `AWS_ACCESS_KEY_ID/SECRET_ACCESS_KEY` | 빈 문자열 | **불필요** (IAM 인스턴스 프로파일) |
| `JWT_SECRET`, `GOOGLE_*`, `TOSS_*` | 없음 | `deploy.yml` |

`S3Config`는 access-key가 비어 있으면 `DefaultCredentialsProvider`로 폴백한다.
그래서 인스턴스 프로파일만으로 S3가 동작한다.

### 문서·설정 드리프트 (Phase 5에서 정리)

| 위치 | 문제 |
|---|---|
| `.env.example` | `DB_URL`, `S3_BUCKET_NAME`을 안내하지만 코드에서 안 씀 (실제는 `SPRING_DATASOURCE_URL`, `AWS_S3_BUCKET`) |
| `docs/infra-ops-guide.md` | RDS 기준 설명, HAProxy(8090) 언급, CD 파일명을 `cd.yml`로 표기 (실제 `deploy.yml`) |
| `docs/infra-architecture.md` | Nginx → HAProxy → 앱 구조로 그려져 있음 (실제는 Nginx 직결) |

---

## 4. 리스크

| 리스크 | 영향 | 완화 |
|---|---|---|
| MySQL이 EC2에 있어 자동 백업이 없음 | 데이터 유실 | 데이터를 별도 EBS 볼륨(`prevent_destroy`)에 분리. **정기 `mysqldump` 필요** |
| 인스턴스 교체 시 볼륨 강제 분리 | 파일시스템 손상 | 교체 전 `docker stop mysql && sudo umount /data` |
| t3.small 메모리 부족 | 블루/그린 전환 중 OOM | 앱 2개 + MySQL + Redis가 2GB에 공존. 모자라면 `ec2_instance_type`을 `t3.medium`으로 |
| state 파일 분실 | 인프라를 코드로 못 건드림 | S3 백엔드 + 버저닝 |
| `apply` 후 예상 밖 과금 | 비용 부담 | Budget 알림, `plan` 확인, 미사용 EIP 즉시 해제 |
| Google OAuth redirect URI 미등록 | 로그인 전면 실패 | Phase 4 체크리스트 |
| `user_data`는 최초 부팅 때만 실행 | 스크립트 수정이 반영 안 됨 | 반영하려면 SSH로 직접 실행하거나 인스턴스 교체 |
| SSH(22) 인터넷 개방 | sshd가 스캔 대상이 됨 | CD 러너 IP를 좁힐 수 없어 불가피. 비밀번호 인증 차단 + fail2ban. 없애려면 배포를 SSM 방식으로 전환 |

---

## 5. 확인이 필요한 사항

1. **기존 EC2/DB에 보존할 데이터가 있는가?** 있으면 새 인스턴스로 덤프 이전이 먼저다.
2. **S3에 옮겨야 할 기존 이미지가 있는가?**
3. **프론트엔드 배포 URL** — `s3_cors_allowed_origins` 기본값이 `http://localhost:3000`뿐이다.

---

## 참고

- [AWS Provider Registry](https://registry.terraform.io/providers/hashicorp/aws/latest) — 6.63.0 (2026-09-03)
- [Terraform S3 Backend (네이티브 잠금)](https://developer.hashicorp.com/terraform/language/backend/s3)
- [AWS 퍼블릭 IPv4 과금 공지](https://aws.amazon.com/blogs/aws/new-aws-public-ipv4-address-charge-public-ip-insights)
