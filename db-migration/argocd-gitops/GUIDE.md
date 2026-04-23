# ArgoCD 기반 GitOps DB 마이그레이션 가이드

ArgoCD가 Git 저장소 변경을 감지하여 K8s Job으로 Flyway 마이그레이션을 자동 실행하는 방법.
DB는 K8s 클러스터 외부에 있으며(NCP Cloud DB), 로컬에서는 docker-compose MySQL로 시뮬레이션한다.

## 전체 아키텍처

```
┌──────────────────────────────────────────────────┐
│  K8s 클러스터 (Kind / NKS)                        │
│                                                  │
│  ┌──────────────────────────┐                    │
│  │ ArgoCD                   │                    │        ┌─────────────┐
│  │  - Git 저장소 감시         │                    │        │ Git Repo    │
│  │  - 변경 감지 시 Sync      │◀───────────────────┼────────│ (k8s/*.yml) │
│  └────────────┬─────────────┘                    │        └─────────────┘
│               │ Sync Hook                        │
│               ▼                                  │
│  ┌──────────────────────────┐                    │
│  │ Migration Job (Pod)      │                    │        ┌──────────────┐
│  │  - argocd.argoproj.io/   │                    │        │ MySQL        │
│  │    hook: Sync            │────────────────────┼───────▶│ (외부 DB)    │
│  │  - Flyway 실행 후 종료    │                    │        │ :3309        │
│  └──────────────────────────┘                    │        └──────────────┘
│                                                  │
└──────────────────────────────────────────────────┘
```

**핵심**: Git에 SQL 파일을 push하면 ArgoCD가 자동으로 마이그레이션을 실행한다.
Git 이력 = DB 변경 이력.

---

## 파일 구성

```
argocd-gitops/
├── docker-compose.yml            # MySQL (클러스터 외부 DB)
├── kind-config.yml               # Kind 클러스터 설정
├── Dockerfile                    # 마이그레이션 앱 컨테이너 이미지
├── build.gradle
├── argocd/
│   ├── application.yml           # ArgoCD Application (개발/스테이징 - 자동 Sync)
│   └── application-prod.yml      # ArgoCD Application (운영 - 수동 승인)
├── k8s/
│   ├── db-secret.yml             # DB 접속 정보
│   └── migration-job.yml         # Flyway 마이그레이션 Job (Sync Hook)
└── src/
    └── main/
        ├── java/...
        └── resources/
            ├── application.yml
            └── db/migration/
                └── V1__create_sample_table.sql
```

---

## Step 1: 외부 MySQL 실행

```bash
cd db-migration/argocd-gitops

# 클러스터 밖에 MySQL 띄우기
docker compose up -d

# 접속 확인
docker exec -it argocd-gitops-mysql mysql -umigration -pmigration1234 modules_db -e "SELECT 1;"
```

---

## Step 2: Kind 클러스터 생성

```bash
kind create cluster --config kind-config.yml

kubectl get nodes
```

---

## Step 3: ArgoCD 설치

### 3-1. ArgoCD 네임스페이스 생성 & 설치

```bash
# 네임스페이스 생성
kubectl create namespace argocd

# ArgoCD 설치 (stable 버전)
kubectl apply -n argocd -f https://raw.githubusercontent.com/argoproj/argo-cd/stable/manifests/install.yaml

# 설치 확인 (모든 Pod가 Running 될 때까지 대기)
kubectl -n argocd get pods -w
```

모든 Pod가 Running 상태가 되면 `Ctrl+C`로 중단한다.

```
NAME                                  READY   STATUS    RESTARTS   AGE
argocd-application-controller-0       1/1     Running   0          60s
argocd-dex-server-xxxxxxxxxx-xxxxx    1/1     Running   0          60s
argocd-notifications-controller-...   1/1     Running   0          60s
argocd-redis-xxxxxxxxxx-xxxxx         1/1     Running   0          60s
argocd-repo-server-xxxxxxxxxx-xxxxx   1/1     Running   0          60s
argocd-server-xxxxxxxxxx-xxxxx        1/1     Running   0          60s
```

### 3-2. ArgoCD UI 접속 (포트포워딩)

```bash
# ArgoCD Server를 로컬 포트로 포워딩
kubectl port-forward svc/argocd-server -n argocd 8443:443
```

브라우저에서 `https://localhost:8443` 접속 (인증서 경고 무시).

### 3-3. ArgoCD 초기 비밀번호 확인

```bash
# admin 초기 비밀번호 확인
kubectl -n argocd get secret argocd-initial-admin-secret -o jsonpath="{.data.password}" | base64 -d
echo
```

- **Username**: `admin`
- **Password**: 위 명령의 출력값

### 3-4. ArgoCD CLI 설치 (선택)

```bash
# Windows (Chocolatey)
choco install argocd-cli

# 또는 직접 다운로드
# https://github.com/argoproj/argo-cd/releases/latest

# CLI 로그인
argocd login localhost:8443 --insecure --username admin --password <비밀번호>
```

---

## Step 4: 앱 빌드 & 이미지 준비

```bash
# 프로젝트 루트에서 bootJar 빌드
cd ../../
./gradlew :db-migration:argocd-gitops:bootJar

cd db-migration/argocd-gitops

# Docker 이미지 빌드
docker build -t db-migration-argocd:v1 .

# Kind 클러스터에 이미지 로드
kind load docker-image db-migration-argocd:v1 --name argocd-migration
```

---

## Step 5: DB Secret 먼저 배포

ArgoCD가 관리하는 k8s/ 디렉토리의 리소스가 적용되기 전에, Secret을 먼저 만들어둔다.

```bash
kubectl apply -f k8s/db-secret.yml
```

---

## Step 6: ArgoCD Application 등록

### 방법 A: kubectl로 직접 등록

```bash
# 개발/스테이징 (자동 Sync)
kubectl apply -f argocd/application.yml

# 또는 운영 (수동 승인)
kubectl apply -f argocd/application-prod.yml
```

### 방법 B: ArgoCD UI에서 등록

1. `https://localhost:8443` 접속
2. **+ NEW APP** 클릭
3. 설정:
   - Application Name: `db-migration`
   - Project: `default`
   - Repository URL: Git 저장소 URL
   - Path: `k8s`
   - Cluster URL: `https://kubernetes.default.svc`
   - Namespace: `default`
4. **CREATE** 클릭

### 방법 C: ArgoCD CLI로 등록

```bash
argocd app create db-migration \
    --repo https://github.com/brunosong/db-migration.git \
    --path k8s \
    --dest-server https://kubernetes.default.svc \
    --dest-namespace default \
    --sync-policy automated
```

---

## Step 7: Sync & 마이그레이션 실행

### 자동 Sync (개발/스테이징)

`application.yml`에 `automated`가 설정되어 있으므로, Git에 push하면 ArgoCD가 자동으로 감지하여
Sync를 실행한다. Sync가 실행되면 `migration-job.yml`의 `argocd.argoproj.io/hook: Sync`
어노테이션에 의해 마이그레이션 Job이 자동으로 생성·실행된다.

### 수동 Sync (운영)

```bash
# CLI로 수동 Sync
argocd app sync db-migration-prod

# 또는 ArgoCD UI에서 SYNC 버튼 클릭
```

### 로컬 테스트 (수동 Sync)

ArgoCD 없이 직접 Job을 실행하여 테스트할 수 있다.

```bash
# migration-job.yml의 이미지를 로컬 빌드 이미지로 교체하여 실행
sed "s|brunosong/db-migration-argocd:latest|db-migration-argocd:v1|g" \
    k8s/migration-job.yml | kubectl apply -f -

# 완료 대기
kubectl wait --for=condition=complete job/db-migration-argocd-job --timeout=300s

# 로그 확인
kubectl logs job/db-migration-argocd-job
```

---

## Step 8: 결과 검증

```bash
# 외부 MySQL에 접속하여 확인
docker exec -it argocd-gitops-mysql mysql -umigration -pmigration1234 modules_db

SHOW TABLES;
SELECT * FROM flyway_schema_history;
DESC sample_argocd_gitops;
```

---

## ArgoCD Sync Hook 동작 방식

`migration-job.yml`에 있는 어노테이션이 핵심이다.

```yaml
annotations:
  argocd.argoproj.io/hook: Sync
  argocd.argoproj.io/hook-delete-policy: BeforeHookCreation
```

| 어노테이션 | 의미 |
|-----------|------|
| `hook: Sync` | ArgoCD Sync가 실행될 때 이 Job을 실행한다 |
| `hook-delete-policy: BeforeHookCreation` | 새 Sync 시 이전 Job을 삭제하고 다시 생성한다 |

**흐름**:
```
Git push → ArgoCD 감지 → Sync 시작 → 이전 Job 삭제 → 새 Job 생성 → Flyway 실행 → Job 완료
```

---

## 배포 방식 정리

| 환경 | ArgoCD 설정 | 배포 방식 |
|------|------------|----------|
| 개발 / 스테이징 | `application.yml` (automated) | Git push → ArgoCD 자동 Sync → Job 실행 |
| 운영 | `application-prod.yml` (수동) | Git push → ArgoCD UI에서 관리자가 SYNC 클릭 |

---

## db-secret.yml 환경별 설정

| 구분 | DB_HOST | DB_PORT |
|------|---------|---------|
| 로컬 (Kind + docker-compose) | `host.docker.internal` | `3309` |
| 운영 (NKS + NCP Cloud DB) | `xxx.cdb.ntruss.com` | `3306` |

---

## 자주 쓰는 명령어

```bash
# ─── 외부 MySQL ───
docker compose up -d                              # MySQL 시작
docker compose down -v                            # MySQL 중지 + 데이터 삭제
docker exec -it argocd-gitops-mysql mysql -umigration -pmigration1234 modules_db

# ─── Kind 클러스터 ───
kind create cluster --config kind-config.yml
kind delete cluster --name argocd-migration
kind load docker-image <이미지:태그> --name argocd-migration

# ─── ArgoCD ───
kubectl port-forward svc/argocd-server -n argocd 8443:443   # UI 접속
kubectl -n argocd get pods                                    # ArgoCD Pod 확인

# ArgoCD 초기 비밀번호
kubectl -n argocd get secret argocd-initial-admin-secret -o jsonpath="{.data.password}" | base64 -d; echo

# ─── ArgoCD CLI ───
argocd app list                                   # Application 목록
argocd app get db-migration                       # Application 상태
argocd app sync db-migration                      # 수동 Sync
argocd app history db-migration                   # Sync 이력

# ─── K8s Job ───
kubectl get jobs
kubectl logs job/db-migration-argocd-job
kubectl delete job db-migration-argocd-job
```

---

## 정리 (Cleanup)

```bash
# ArgoCD Application 삭제
kubectl delete -f argocd/application.yml --ignore-not-found
# 또는
argocd app delete db-migration

# K8s 리소스 삭제
kubectl delete -f k8s/migration-job.yml --ignore-not-found
kubectl delete -f k8s/db-secret.yml --ignore-not-found

# ArgoCD 삭제
kubectl delete -n argocd -f https://raw.githubusercontent.com/argoproj/argo-cd/stable/manifests/install.yaml
kubectl delete namespace argocd

# Kind 클러스터 삭제
kind delete cluster --name argocd-migration

# Docker Compose 정리
docker compose down -v
```

---

## 트러블슈팅

### ArgoCD Pod가 Pending 상태

```bash
# 리소스 부족일 가능성 → Kind 노드 확인
kubectl describe node argocd-migration-control-plane

# Docker Desktop의 메모리를 4GB 이상으로 설정
# Settings → Resources → Memory
```

### ArgoCD UI 접속이 안 되는 경우

```bash
# port-forward가 실행 중인지 확인
kubectl port-forward svc/argocd-server -n argocd 8443:443

# 브라우저에서 https://localhost:8443 (http가 아닌 https!)
# 인증서 경고 → 고급 → 안전하지 않음으로 이동
```

### ArgoCD 초기 비밀번호를 모르겠는 경우

```bash
# Secret에서 확인
kubectl -n argocd get secret argocd-initial-admin-secret -o jsonpath="{.data.password}" | base64 -d
echo

# Secret이 없으면 (이미 삭제된 경우) admin 비밀번호 재설정
kubectl -n argocd patch secret argocd-secret \
    -p '{"stringData": {"admin.password": "$2a$10$xxxxx", "admin.passwordMtime": "'$(date +%FT%T%Z)'"}}'
# bcrypt 해시 생성: htpasswd -nbBC 10 "" 새비밀번호 | tr -d ':\n' | sed 's/$2y/$2a/'
```

### Sync Hook Job이 실행되지 않는 경우

```bash
# ArgoCD Application 상태 확인
argocd app get db-migration

# Sync 이벤트 확인
kubectl -n argocd get events --sort-by=.metadata.creationTimestamp

# migration-job.yml에 argocd.argoproj.io/hook: Sync 어노테이션이 있는지 확인
```

### 외부 MySQL 연결 실패

```bash
# 1. MySQL이 실행 중인지 확인
docker ps | grep argocd-gitops-mysql

# 2. Kind에서 host.docker.internal 접근 가능한지 확인
kubectl run -it --rm debug --image=busybox --restart=Never -- \
    wget -qO- --timeout=3 host.docker.internal:3309 || echo "port check done"

# 3. host.docker.internal이 안 되면 Gateway IP 사용
docker network inspect kind | grep Gateway
# 출력된 IP를 db-secret.yml의 DB_HOST에 입력
```
