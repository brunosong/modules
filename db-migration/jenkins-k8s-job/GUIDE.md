# Jenkins + Kubernetes Job DB 마이그레이션 가이드 (NCP DB)

Jenkins에서 Docker 이미지를 빌드하고, K8s 클러스터에 Job을 생성하여
**클러스터 외부의 DB**(NCP Cloud DB for MySQL)에 Flyway 마이그레이션을 실행하는 방법을 설명한다.

로컬에서는 docker-compose로 MySQL을 별도로 띄워서 NCP Cloud DB를 시뮬레이션한다.

## 전체 아키텍처

```
┌─────────────────────────────────┐     ┌──────────────────────────┐
│  K8s 클러스터 (Kind / NKS)       │     │  클러스터 외부             │
│                                 │     │                          │
│  ┌───────────────────────────┐  │     │  ┌────────────────────┐  │
│  │ Migration Job (Pod)       │  │     │  │ MySQL              │  │
│  │  - Flyway 마이그레이션 실행  │──┼────▶│  │ (docker-compose)   │  │
│  │  - 완료 후 자동 종료        │  │     │  │ 또는 NCP Cloud DB  │  │
│  └───────────────────────────┘  │     │  │ :3308              │  │
│                                 │     │  └────────────────────┘  │
│  ┌───────────────────────────┐  │     │                          │
│  │ Secret (db-migration-secret)│ │     │  ┌────────────────────┐  │
│  │  DB_HOST, DB_PORT, ...    │  │     │  │ Jenkins            │  │
│  └───────────────────────────┘  │     │  │ (docker-compose)   │  │
│                                 │     │  │ :8080              │  │
└─────────────────────────────────┘     │  └────────────────────┘  │
                                        └──────────────────────────┘
```

**핵심**: MySQL은 K8s 클러스터 안에 없다. 완전히 분리된 외부 DB이다.

---

## 파일 구성

```
jenkins-k8s-job/
├── docker-compose.yml        # Jenkins + Docker-in-Docker
├── docker-compose-db.yml     # MySQL (클러스터 외부 DB - NCP Cloud DB 역할)
├── jenkins/
│   └── Dockerfile            # 커스텀 Jenkins 이미지 (kubectl, kind 포함)
├── generate-kubeconfig.ps1   # Jenkins용 kubeconfig 생성 스크립트
├── kind-config.yml           # Kind 클러스터 설정
├── Dockerfile                # 마이그레이션 앱 컨테이너 이미지
├── Jenkinsfile               # Jenkins 파이프라인
├── jenkins-plugins.txt       # Jenkins 플러그인 목록
├── build.gradle
├── k8s/
│   ├── db-secret.yml         # DB 접속 정보 (호스트/포트/계정)
│   ├── ncp-db-service.yml    # 외부 DB 연결 Service (ExternalName / Endpoints)
│   └── migration-job.yml     # Flyway 마이그레이션 K8s Job
└── src/
    └── main/
        ├── java/...          # Spring Boot + Flyway 앱
        └── resources/
            ├── application.yml
            └── db/migration/ # SQL 마이그레이션 파일
```

---

## Step 1: 외부 MySQL 실행 (NCP Cloud DB 시뮬레이션)

```bash
cd db-migration/jenkins-k8s-job

# 클러스터 밖에 MySQL 띄우기
docker compose -f docker-compose-db.yml up -d

# 접속 확인
docker exec -it ncp-cloud-db mysql -umigration -pmigration1234 modules_db -e "SELECT 1;"
```

이 MySQL은 K8s 클러스터와 완전히 분리되어 있다.
운영 환경에서는 이 자리에 NCP Cloud DB for MySQL이 들어간다.

| 구분 | 로컬 | 운영 (NCP) |
|------|------|-----------|
| DB | docker-compose-db.yml | NCP Cloud DB for MySQL |
| Host | `host.docker.internal` | `xxx.cdb.ntruss.com` |
| Port | 3308 | 3306 |
| User | migration | migration |

---

## Step 2: Kind 클러스터 생성

```bash
# Kind 클러스터 생성
kind create cluster --config kind-config.yml

# 확인
kubectl get nodes
```

---

## Step 3: 앱 빌드 & 이미지 준비

```bash
# 프로젝트 루트로 이동
cd ../../

# bootJar 빌드
./gradlew :db-migration:jenkins-k8s-job:bootJar

# Docker 이미지 빌드
cd db-migration/jenkins-k8s-job
docker build -t db-migration:v1 .

# Kind 클러스터에 이미지 로드 (레지스트리 없이 직접 전달)
kind load docker-image db-migration:v1 --name db-migration
```

---

## Step 4: K8s 리소스 배포 & 마이그레이션 실행

```bash
# 1. DB 접속 정보 Secret 생성
kubectl apply -f k8s/db-secret.yml

# 2. (선택) 외부 DB Service 매핑
kubectl apply -f k8s/ncp-db-service.yml

# 3. 마이그레이션 Job 실행
sed "s|brunosong/db-migration:latest|db-migration:v1|g" \
    k8s/migration-job.yml | kubectl apply -f -

# 4. 완료 대기
kubectl wait --for=condition=complete job/db-migration-job --timeout=300s

# 5. 로그 확인
kubectl logs job/db-migration-job
```

**성공 로그 예시:**
```
Flyway Community Edition ...
Successfully validated 1 migration
Creating Schema History table ...
Migrating schema `modules_db` to version "1 - create sample table"
Successfully applied 1 migration
```

---

## Step 5: 결과 검증

외부 MySQL에 직접 접속하여 확인한다.

```bash
# docker-compose MySQL에 접속
docker exec -it ncp-cloud-db mysql -umigration -pmigration1234 modules_db

# 테이블 확인
SHOW TABLES;
SELECT * FROM flyway_schema_history;
DESC sample_k8s_job;
```

---

## db-secret.yml 환경별 설정

로컬과 운영 환경에서 `db-secret.yml`의 값만 바꾸면 된다.

### 로컬 (Kind + docker-compose-db.yml)

```yaml
stringData:
  DB_HOST: "host.docker.internal"
  DB_PORT: "3308"
  DB_NAME: "modules_db"
  DB_USERNAME: "migration"
  DB_PASSWORD: "migration1234"
```

### 운영 (NKS + NCP Cloud DB)

```yaml
stringData:
  DB_HOST: "db-migration-mysql.cdb.ntruss.com"
  DB_PORT: "3306"
  DB_NAME: "modules_db"
  DB_USERNAME: "migration"
  DB_PASSWORD: "실제비밀번호"
```

---

## (선택) Jenkins 파이프라인 자동화

### Jenkins 실행

> **중요한 순서**: `docker compose up` **전에** 반드시 `kubeconfig` 파일을 먼저 생성해야 한다.
> 파일이 없는 상태에서 compose를 실행하면 Docker가 호스트 쪽에 빈 디렉토리를 만들어 버려서
> 나중에 마운트 에러(`Are you trying to mount a directory onto a file`)가 발생한다.

```bash
# 1. Kind 클러스터 생성
kind create cluster --config kind-config.yml

# 2. kubeconfig 파일 먼저 생성 (PowerShell)
.\generate-kubeconfig.ps1

# 3. 이제 Jenkins + DinD 실행
docker compose up -d --build

# 4. 초기 비밀번호 확인
docker exec jenkins-k8s cat /var/jenkins_home/secrets/initialAdminPassword
```

### Jenkins 초기 설정

1. `http://localhost:8080` 접속 → 비밀번호 입력
2. **Install suggested plugins** 선택
3. 관리자 계정 생성

### kubectl / Kind 확인

커스텀 Jenkins 이미지(`jenkins/Dockerfile`)에 kubectl과 Kind가 이미 포함되어 있다.
`docker compose up -d` 시 자동으로 빌드된다.

```bash
# 설치 확인
docker exec jenkins-k8s kubectl version --client
docker exec jenkins-k8s kind --version
```

### kubeconfig 설정

호스트의 `~/.kube/config`를 직접 마운트하면 안 된다.
Kind kubeconfig의 API Server 주소가 `127.0.0.1`인데,
Jenkins 컨테이너 안에서 `127.0.0.1`은 컨테이너 자기 자신을 가리키기 때문이다.

대신 **Jenkins 전용 kubeconfig 파일**을 별도로 생성한다.
`generate-kubeconfig.ps1`가 Kind kubeconfig를 추출하고 서버 주소만 `host.docker.internal`로 변경한다.

```bash
# Kind 클러스터 생성 후 (Step 2 완료 상태에서)
generate-kubeconfig.ps1

# Jenkins에서 클러스터 접근 확인
docker exec jenkins-k8s kubectl get nodes
```

> **원리:**
> - `generate-kubeconfig.ps1` → Kind kubeconfig를 `kubeconfig` 파일로 추출
> - `127.0.0.1` → `host.docker.internal`로 치환 (호스트 `~/.kube/config`는 안 건드림)
> - docker-compose가 이 파일을 Jenkins 컨테이너에 읽기전용 마운트

> **운영 환경(NKS)에서는** 볼륨 마운트가 아닌 `set-cluster` 방식으로 클러스터에 접근한다:
>
> ```bash
> # 클러스터 등록
> kubectl config set-cluster <클러스터명> \
>     --server=https://<API-Server-주소>:<포트> \
>     --certificate-authority=/path/to/ca.crt \
>     --embed-certs=true
>
> # 사용자 인증 정보 등록
> kubectl config set-credentials <사용자명> \
>     --client-certificate=/path/to/client.crt \
>     --client-key=/path/to/client.key \
>     --embed-certs=true
>
> # 컨텍스트 등록 & 사용
> kubectl config set-context <컨텍스트명> \
>     --cluster=<클러스터명> --user=<사용자명>
> kubectl config use-context <컨텍스트명>
> ```

### Pipeline Job 생성

1. 새로운 Item → Pipeline
2. Definition: Pipeline script from SCM
3. SCM: Git → Repository URL 입력
4. Script Path: `db-migration/jenkins-k8s-job/Jenkinsfile`
5. 저장 → Build Now

---

## 자주 쓰는 명령어

```bash
# ─── 외부 MySQL ───
docker compose -f docker-compose-db.yml up -d     # MySQL 시작
docker compose -f docker-compose-db.yml down       # MySQL 중지
docker compose -f docker-compose-db.yml down -v    # MySQL 중지 + 데이터 삭제
docker exec -it ncp-cloud-db mysql -umigration -pmigration1234 modules_db

# ─── Jenkins ───
docker compose up -d                               # Jenkins 시작
docker compose down                                # Jenkins 중지

# ─── Kind 클러스터 ───
kind create cluster --config kind-config.yml
kind delete cluster --name db-migration
kind load docker-image <이미지:태그> --name db-migration

# ─── K8s Job ───
kubectl get jobs
kubectl logs job/db-migration-job
kubectl delete job db-migration-job
```

---

## 정리 (Cleanup)

```bash
# K8s 리소스 삭제
kubectl delete -f k8s/migration-job.yml --ignore-not-found
kubectl delete -f k8s/db-secret.yml --ignore-not-found
kubectl delete -f k8s/ncp-db-service.yml --ignore-not-found

# Kind 클러스터 삭제
kind delete cluster --name db-migration

# Docker Compose 정리
docker compose down -v
docker compose -f docker-compose-db.yml down -v

# Docker 이미지 정리
docker rmi db-migration:v1
```

---

## 트러블슈팅

### Job에서 외부 MySQL 연결 실패

```bash
# 1. 외부 MySQL이 실행 중인지 확인
docker ps | grep ncp-cloud-db

# 2. Kind 클러스터에서 호스트 접근 가능한지 확인
kubectl run -it --rm debug --image=busybox --restart=Never -- \
    wget -qO- --timeout=3 host.docker.internal:3308 || echo "port check done"

# 3. host.docker.internal이 안 되면 Gateway IP 사용
docker network inspect kind | grep Gateway
# 출력된 IP(예: 172.18.0.1)를 db-secret.yml의 DB_HOST에 입력
```

### Migration Job이 ImagePullBackOff

```bash
# kind load를 했는지 확인
kind load docker-image db-migration:v1 --name db-migration

# migration-job.yml에 imagePullPolicy: Never 가 있는지 확인
```

### NCP Cloud DB 연결 실패 (운영)

```bash
# 1. NKS와 Cloud DB가 같은 VPC에 있는지 확인 (NCP 콘솔)
# 2. Cloud DB ACG에 NKS Worker Node Subnet이 허용되어 있는지 확인
# 3. Cloud DB Private Domain이 NKS 내부에서 해석되는지 확인
kubectl run -it --rm debug --image=busybox --restart=Never -- \
    nslookup db-migration-mysql.cdb.ntruss.com
```
