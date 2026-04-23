# Jenkins + Kubernetes Job DB 마이그레이션 가이드

로컬 환경에서 Jenkins와 Docker를 이용해 Kubernetes(Kind) 클러스터에 DB 마이그레이션 Job을 실행하는 방법을 설명한다.

## 전체 아키텍처

```
┌─────────────────────────────────────────────────────────┐
│  Docker Host (로컬 PC)                                    │
│                                                         │
│  ┌──────────┐    ┌───────────┐    ┌───────────────────┐ │
│  │ Jenkins  │───▶│Docker DinD│───▶│  Kind Cluster     │ │
│  │ :8080    │    │ :2376     │    │  ┌─────────────┐  │ │
│  └──────────┘    └───────────┘    │  │MySQL (K8s)  │  │ │
│                                   │  │ database NS │  │ │
│                                   │  └──────┬──────┘  │ │
│                                   │         │         │ │
│                                   │  ┌──────▼──────┐  │ │
│                                   │  │Migration Job│  │ │
│                                   │  │ default NS  │  │ │
│                                   │  └─────────────┘  │ │
│                                   └───────────────────┘ │
└─────────────────────────────────────────────────────────┘
```

## 사전 준비

### 1. 필수 설치 도구

| 도구 | 설치 방법 | 확인 명령 |
|------|----------|----------|
| Docker Desktop | https://docs.docker.com/desktop/install/windows-install/ | `docker --version` |
| Kind | `choco install kind` 또는 https://kind.sigs.k8s.io/docs/user/quick-start/#installation | `kind --version` |
| kubectl | `choco install kubernetes-cli` 또는 Docker Desktop 설정에서 활성화 | `kubectl version --client` |
| Java 21 | https://adoptium.net/ | `java --version` |

> **Windows 기준**: Chocolatey(`choco`)가 없으면 https://chocolatey.org/install 에서 설치

### 2. Docker Desktop 설정

- Settings → General → **"Use the WSL 2 based engine"** 체크
- Settings → Resources → WSL Integration → 사용할 WSL 배포판 활성화

---

## Step 1: Kind 클러스터 생성

```bash
cd db-migration/jenkins-k8s-job

# Kind 클러스터 생성
kind create cluster --config kind-config.yml

# 클러스터 확인
kubectl cluster-info --context kind-db-migration
kubectl get nodes
```

**결과 확인:**
```
NAME                         STATUS   ROLES           AGE   VERSION
db-migration-control-plane   Ready    control-plane   30s   v1.31.0
```

---

## Step 2: K8s에 MySQL 배포

```bash
# MySQL 배포 (database 네임스페이스에 생성)
kubectl apply -f k8s/mysql-deployment.yml

# 배포 상태 확인 (Ready 될 때까지 대기)
kubectl -n database rollout status deployment/mysql --timeout=120s

# Pod 상태 확인
kubectl -n database get pods

# MySQL 접속 테스트
kubectl -n database exec -it deploy/mysql -- mysql -uroot -proot -e "SHOW DATABASES;"
```

**결과 확인:**
```
NAME                     READY   STATUS    RESTARTS   AGE
mysql-xxxxxxxxxx-xxxxx   1/1     Running   0          30s
```

---

## Step 3: Flyway 마이그레이션 앱 빌드

```bash
# 프로젝트 루트에서 bootJar 빌드
cd ../../
./gradlew :db-migration:jenkins-k8s-job:bootJar

# 빌드 확인
ls db-migration/jenkins-k8s-job/build/libs/
# → jenkins-k8s-job-1.0-SNAPSHOT.jar
```

---

## Step 4: Docker 이미지 빌드 & Kind에 로드

```bash
cd db-migration/jenkins-k8s-job

# Docker 이미지 빌드
docker build -t db-migration:v1 .

# Kind 클러스터에 이미지 로드 (DockerHub push 없이 직접 전달)
kind load docker-image db-migration:v1 --name db-migration

# 이미지 로드 확인
docker exec -it db-migration-control-plane crictl images | grep db-migration
```

> **핵심 포인트**: `kind load`를 사용하면 로컬 이미지를 레지스트리 없이 바로 클러스터에 전달할 수 있다.

---

## Step 5: Secret 생성 & 마이그레이션 Job 실행

```bash
# DB 접속 정보 Secret 생성
kubectl apply -f k8s/db-secret.yml

# 마이그레이션 Job의 이미지 태그를 수정하여 실행
sed "s|brunosong/db-migration:latest|db-migration:v1|g" k8s/migration-job.yml | kubectl apply -f -

# Job 완료 대기
kubectl wait --for=condition=complete job/db-migration-job --timeout=300s

# 실행 로그 확인
kubectl logs job/db-migration-job
```

**성공 로그 예시:**
```
Flyway Community Edition ...
Successfully validated 1 migration
Creating Schema History table ...
Current version of schema `modules_db`: << Empty Schema >>
Migrating schema `modules_db` to version "1 - create sample table"
Successfully applied 1 migration
```

---

## Step 6: 마이그레이션 결과 검증

```bash
# MySQL에 접속하여 테이블 확인
kubectl -n database exec -it deploy/mysql -- mysql -uroot -proot modules_db -e "SHOW TABLES;"

# Flyway 히스토리 확인
kubectl -n database exec -it deploy/mysql -- mysql -uroot -proot modules_db -e "SELECT * FROM flyway_schema_history;"

# 생성된 테이블 구조 확인
kubectl -n database exec -it deploy/mysql -- mysql -uroot -proot modules_db -e "DESC sample_k8s_job;"
```

---

## (선택) Jenkins를 통한 자동화 실행

Jenkins를 Docker로 띄워서 파이프라인으로 위 과정을 자동화할 수 있다.

### Jenkins 실행

```bash
cd db-migration/jenkins-k8s-job

# Jenkins + DinD 실행
docker compose up -d jenkins docker-dind

# Jenkins 초기 비밀번호 확인
docker exec jenkins-k8s cat /var/jenkins_home/secrets/initialAdminPassword
```

### Jenkins 초기 설정

1. 브라우저에서 `http://localhost:8080` 접속
2. 초기 비밀번호 입력
3. **"Install suggested plugins"** 선택
4. 관리자 계정 생성

### Jenkins에 Kind & kubectl 설치

Jenkins 컨테이너 안에 Kind와 kubectl을 설치해야 한다.

```bash
# Jenkins 컨테이너 접속
docker exec -it -u root jenkins-k8s bash

# kubectl 설치
curl -LO "https://dl.k8s.io/release/$(curl -L -s https://dl.k8s.io/release/stable.txt)/bin/linux/amd64/kubectl"
chmod +x kubectl
mv kubectl /usr/local/bin/

# Kind 설치
curl -Lo ./kind https://kind.sigs.k8s.io/dl/v0.24.0/kind-linux-amd64
chmod +x kind
mv kind /usr/local/bin/

# 설치 확인
kubectl version --client
kind --version

exit
```

### Jenkins Pipeline Job 생성

1. **새로운 Item** → **Pipeline** 선택
2. Pipeline 설정:
   - **Definition**: Pipeline script from SCM
   - **SCM**: Git
   - **Repository URL**: 프로젝트 Git URL
   - **Script Path**: `db-migration/jenkins-k8s-job/Jenkinsfile`
3. **저장** → **Build Now**

### Jenkins에서 kubeconfig 설정

Jenkins가 Kind 클러스터에 접근하려면 kubeconfig가 필요하다.

```bash
# 호스트에서 Kind kubeconfig 추출
kind get kubeconfig --name db-migration > kubeconfig.yml

# Jenkins 컨테이너로 복사
docker cp kubeconfig.yml jenkins-k8s:/var/jenkins_home/.kube/config
```

또는 Jenkins Credentials에 등록:
1. **Jenkins 관리** → **Credentials** → **Global**
2. **Add Credentials** → **Secret file**
3. ID: `kubeconfig`, File: kubeconfig.yml 업로드

---

## 자주 쓰는 명령어 모음

```bash
# ─── Kind 클러스터 관리 ───
kind create cluster --config kind-config.yml     # 클러스터 생성
kind delete cluster --name db-migration           # 클러스터 삭제
kind get clusters                                 # 클러스터 목록

# ─── 이미지 관리 ───
kind load docker-image <이미지:태그> --name db-migration  # 이미지 로드

# ─── Job 관리 ───
kubectl get jobs                                  # Job 목록
kubectl describe job db-migration-job             # Job 상세
kubectl logs job/db-migration-job                 # Job 로그
kubectl delete job db-migration-job               # Job 삭제

# ─── MySQL 접속 ───
kubectl -n database exec -it deploy/mysql -- mysql -uroot -proot modules_db

# ─── 디버깅 ───
kubectl get pods --all-namespaces                 # 전체 Pod 확인
kubectl describe pod <pod-name>                   # Pod 상세 정보
kubectl get events --sort-by=.metadata.creationTimestamp  # 이벤트 확인

# ─── Docker Compose ───
docker compose up -d                              # 전체 서비스 시작
docker compose down                               # 전체 서비스 중지
docker compose logs -f jenkins                    # Jenkins 로그 실시간 확인
```

---

## 정리 (Cleanup)

```bash
# K8s 리소스 삭제
kubectl delete -f k8s/migration-job.yml --ignore-not-found
kubectl delete -f k8s/db-secret.yml --ignore-not-found
kubectl delete -f k8s/mysql-deployment.yml --ignore-not-found

# Kind 클러스터 삭제
kind delete cluster --name db-migration

# Docker Compose 정리
docker compose down -v

# Docker 이미지 정리
docker rmi db-migration:v1
```

---

## 트러블슈팅

### Kind 클러스터가 생성되지 않는 경우

```bash
# Docker Desktop이 실행 중인지 확인
docker info

# 기존 클러스터 삭제 후 재생성
kind delete cluster --name db-migration
kind create cluster --config kind-config.yml
```

### Migration Job이 ImagePullBackOff 상태인 경우

```bash
# kind load를 했는지 확인
kind load docker-image db-migration:v1 --name db-migration

# migration-job.yml에 imagePullPolicy: Never 추가 필요할 수 있음
```

### MySQL 연결 실패 (Job이 Error 상태)

```bash
# MySQL Pod가 Running 상태인지 확인
kubectl -n database get pods

# MySQL Service가 생성되었는지 확인
kubectl -n database get svc

# DNS 해석이 되는지 확인
kubectl run -it --rm debug --image=busybox --restart=Never -- nslookup mysql-service.database.svc.cluster.local
```
