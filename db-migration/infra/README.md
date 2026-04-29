# db-migration/infra — 인프라 통합 모듈

`db-migration` 하위 모든 시나리오 (jenkins-direct, jenkins-k8s-job,
argocd-gitops, argocd-helm, jenkins-gitlab-mr) 의 도커 / 쿠버네티스 인프라
파일을 한 곳에 모은 모듈. 시나리오별 모듈에는 더 이상 `Dockerfile` /
`docker-compose.yml` 이 없다.

> **기준선**: 현재 사용 중인 도커 컨테이너 (`jenkins-k8s`, `jenkins-dind`,
> `ncp-cloud-db`, `jenkins-gitlab-mr-gitlab`) 의 설정이 그대로 보존되어 있다.
> compose 의 `name:` 필드로 기존 project name 을 박아둬서 기존 볼륨 / 데이터가
> 손실되지 않는다.

---

## 파일 구성

```
infra/
├── README.md                       ← 이 파일
├── docker-compose.gitlab.yml       GitLab CE (project: jenkins-gitlab-mr)
├── docker-compose.jenkins.yml      Jenkins + DinD + MySQL (project: jenkins-k8s-job)
├── docker-compose.argocd-fwd.yml   ArgoCD NodePort 포워더 (kind → 호스트 우회)
├── argocd-server-nodeport.yml      argocd-server Service 를 NodePort 로 노출하는 매니페스트
├── Dockerfile.jenkins              Jenkins 커스텀 이미지 (docker CLI + kubectl + kind)
├── Dockerfile.app                  Spring Boot 앱 공용 이미지 (5개 시나리오 공유)
├── jenkins-plugins.txt             Jenkins 사전 설치 플러그인
├── kind-config.yml                 kind 클러스터 설정 (cluster name: db-migration)
├── kubeconfig                      kind 접속용 kubeconfig (machine-specific, .gitignore 권장)
└── scripts/
    ├── setup-kubeconfig.ps1        kubeconfig 자동 생성 (Windows PowerShell)
    ├── setup-kubeconfig.sh         같은 작업 (Bash)
    └── generate-kubeconfig.ps1     kubeconfig 토큰 갱신
```

---

## 빠른 시작

### 1. 공유 네트워크 한 번 만들기

두 compose 는 같은 네트워크 `db-migration-net` 을 공유한다.
처음 한 번만 만들어 두면 된다 (이미 있으면 에러 무시):

```bash
docker network create db-migration-net
```

### 2. 인프라 기동

```bash
# 프로젝트 루트(modules) 에서
docker compose -f db-migration/infra/docker-compose.gitlab.yml  up -d
docker compose -f db-migration/infra/docker-compose.jenkins.yml up -d

# 두 compose 를 한 번에 다루는 alias 가 편함:
#   alias dco-mig='docker compose \
#     -f db-migration/infra/docker-compose.gitlab.yml \
#     -f db-migration/infra/docker-compose.jenkins.yml'
#   dco-mig up -d
```

> 두 compose 가 같은 `db-migration-net` 위에 떠서 컨테이너 간 통신은
> 이름으로 바로 된다. 더 이상 `docker network connect` 같은 수동 작업 불필요.
>
> | 출발 | 목적 | URL |
> |------|-----|-----|
> | Jenkins → GitLab | API | `http://gitlab.local` |
> | GitLab → Jenkins | webhook | `http://jenkins-k8s:8080` |
> | Jenkins → MySQL | JDBC | `jdbc:mysql://ncp-cloud-db:3306/modules_db` |

### 3. 접속 포트 정리

| 서비스 | 호스트 포트 | 컨테이너 | URL |
|--------|-----------|---------|-----|
| GitLab | 8090 | jenkins-gitlab-mr-gitlab | http://localhost:8090 |
| Jenkins | 8080 | jenkins-k8s | http://localhost:8080 |
| Jenkins Agent | 50000 | jenkins-k8s | - |
| DinD TLS | 2376 | jenkins-dind | - |
| MySQL | 3308 | ncp-cloud-db | `mysql -h 127.0.0.1 -P 3308 -u migration -pmigration1234` |
| GitLab SSH | 2222 | jenkins-gitlab-mr-gitlab | - |
| ArgoCD (NodePort 포워더) | 30443 | db-migration-argocd-fwd | https://localhost:30443 |

### 4. 앱 이미지 빌드 (시나리오별)

```bash
# 1) 먼저 jar 빌드
./gradlew :db-migration:argocd-gitops:bootJar

# 2) infra 의 공용 Dockerfile 로 이미지 빌드
docker build \
  -f db-migration/infra/Dockerfile.app \
  -t db-migration-argocd:latest \
  db-migration/argocd-gitops          # ← build context = 시나리오 디렉토리
```

`Dockerfile.app` 은 `ARG JAR_FILE=build/libs/*.jar` 로 jar 를 와일드카드로
받기 때문에 시나리오마다 jar 이름이 달라도 그대로 동작한다.

### 5. kind 클러스터

```bash
# kind 설치 후
kind create cluster --config db-migration/infra/kind-config.yml

# 삭제
kind delete cluster --name db-migration
```

### 6. ArgoCD NodePort 노출 (kind 우회 포워더)

ArgoCD 를 클러스터에 설치한 뒤, `argocd-server` 를 NodePort 로 바꾸고
호스트 / 다른 컨테이너 (Jenkins 등) 에서 접근할 수 있도록
socat 포워더 컨테이너를 띄운다.

**왜 포워더가 필요한가**: kind 노드는 도커 컨테이너 안에 있고,
NodePort 포트는 `kind-config.yml` 의 `extraPortMappings` 에 미리 박아둔
포트만 호스트로 노출된다. 이 매핑은 클러스터 생성 시점에만 적용되므로,
클러스터 재생성 없이 새 NodePort 를 호스트에 노출하려면 외부 포워더가
필요하다.

```bash
# 1) ArgoCD Service 를 NodePort 로 변경 (30443/30080 고정)
kubectl apply -f db-migration/infra/argocd-server-nodeport.yml

# 2) 포워더 컨테이너 기동
docker compose -f db-migration/infra/docker-compose.argocd-fwd.yml up -d
```

> 포워더는 두 네트워크 (`db-migration-net`, `kind`) 에 동시에 소속된다.
> kind 컨트롤플레인은 항상 `kind` 네트워크에 떠 있으므로 별도의
> `docker network connect` 없이 바로 `db-migration-control-plane` 로 연결된다.
> kind 클러스터를 재생성해도 포워더는 그대로 동작한다.

| 출발 | 목적 | URL |
|------|------|-----|
| 호스트 브라우저 | ArgoCD UI | https://localhost:30443 |
| Jenkins 컨테이너 | ArgoCD API | https://db-migration-argocd-fwd:30443 |

> 자체 서명 인증서이므로 브라우저 경고 무시 / CLI 는 `--insecure` 사용.
> ArgoCD CLI 호출 시에는 gRPC-Web 모드 권장: `argocd login ... --grpc-web --insecure`.

**확인**:

```bash
# 포워더 동작 확인
curl -k https://localhost:30443/healthz

# Jenkins 컨테이너에서
docker exec db-migration-jenkins curl -k https://db-migration-argocd-fwd:30443/healthz
```

**정리**:

```bash
docker compose -f db-migration/infra/docker-compose.argocd-fwd.yml down
```

---

## compose 동작에서 헷갈리는 점

### 왜 `name:` 필드가 박혀있나

compose v2 는 기본적으로 **compose 파일이 있는 디렉토리명** 을 project name
으로 사용한다. 이 파일이 `infra/` 에 있으니 그대로 두면 project name 이
`infra` 가 되어 볼륨이 `infra_jenkins-home` 같은 새 이름으로 만들어진다.

기존 데이터를 그대로 쓰려고 `name: jenkins-k8s-job` (또는
`jenkins-gitlab-mr`) 을 명시했다. 이러면 기존 볼륨
`jenkins-k8s-job_jenkins-home` 등을 그대로 인식한다.

### `../../:/workspace/modules:ro` 경로

`docker-compose.jenkins.yml` 의 jenkins 서비스는 **호스트의 프로젝트 루트
(`modules/`)** 를 `/workspace/modules` 로 마운트한다. compose 파일이
`db-migration/infra/` 에 있으므로 `../../` = `modules/` 로 정확히 매칭된다.
이전 위치 (`db-migration/jenkins-k8s-job/`) 와 상대 깊이가 같아서 변경 불필요.

---

## 시나리오별 가이드 (위치 변경됨)

각 시나리오의 GUIDE.md / Jenkinsfile 등 **소스 / 매니페스트는 원래 자리**에
그대로 두고, **인프라 구성만** 이 모듈로 이전했다.

| 시나리오 | 가이드 위치 | 인프라 |
|---------|----------|------|
| jenkins-direct | `db-migration/jenkins-direct/GUIDE.md` | `infra/docker-compose.jenkins.yml` 재사용 |
| jenkins-k8s-job | `db-migration/jenkins-k8s-job/GUIDE.md` + `TROUBLESHOOTING.md` | `infra/docker-compose.jenkins.yml` |
| argocd-gitops | `db-migration/argocd-gitops/GUIDE.md` + `jenkins/README.md` | `infra/docker-compose.jenkins.yml` (mysql 포함) + `infra/kind-config.yml` |
| argocd-helm | `db-migration/argocd-helm/GUIDE.md` | 위와 동일 |
| jenkins-gitlab-mr | `db-migration/jenkins-gitlab-mr/GUIDE.md` + `WALKTHROUGH.md` | `infra/docker-compose.gitlab.yml` + `infra/docker-compose.jenkins.yml` |

각 시나리오 가이드의 `cd db-migration/<scenario>; docker compose up` 명령은
이제 `docker compose -f db-migration/infra/docker-compose.<X>.yml up` 으로
바뀌었다. 구버전 가이드는 상단에 안내 문구가 추가됨.

---

## 자주 쓰는 명령어

```bash
# ─── 컨테이너 상태 ───
docker compose ls
docker ps --format 'table {{.Names}}\t{{.Networks}}\t{{.Ports}}'

# ─── GitLab ───
docker compose -f db-migration/infra/docker-compose.gitlab.yml up -d
docker compose -f db-migration/infra/docker-compose.gitlab.yml down
docker compose -f db-migration/infra/docker-compose.gitlab.yml logs -f gitlab

# ─── Jenkins + DinD + MySQL ───
docker compose -f db-migration/infra/docker-compose.jenkins.yml up -d
docker compose -f db-migration/infra/docker-compose.jenkins.yml down
docker compose -f db-migration/infra/docker-compose.jenkins.yml logs -f jenkins

# ─── 공유 네트워크 (최초 1회) ───
docker network create db-migration-net

# ─── 정리 (데이터 보존) ───
docker compose -f db-migration/infra/docker-compose.gitlab.yml  down
docker compose -f db-migration/infra/docker-compose.jenkins.yml down

# ─── 정리 (데이터까지 삭제) ───
docker compose -f db-migration/infra/docker-compose.gitlab.yml  down -v
docker compose -f db-migration/infra/docker-compose.jenkins.yml down -v
```

---

## 마이그레이션 노트 (이전 위치에서 옮긴 것)

| 이전 | 현재 |
|------|-----|
| `jenkins-direct/docker-compose.yml` | (삭제) — `infra/docker-compose.jenkins.yml` 재사용 |
| `jenkins-k8s-job/Dockerfile` | `infra/Dockerfile.app` (와일드카드 jar 로 통합) |
| `jenkins-k8s-job/jenkins/Dockerfile` | `infra/Dockerfile.jenkins` |
| `jenkins-k8s-job/docker-compose.yml` | `infra/docker-compose.jenkins.yml` |
| `jenkins-k8s-job/docker-compose-db.yml` | (삭제) — `infra/docker-compose.jenkins.yml` 안에 mysql 통합 |
| `jenkins-k8s-job/jenkins-plugins.txt` | `infra/jenkins-plugins.txt` |
| `jenkins-k8s-job/kind-config.yml` | `infra/kind-config.yml` |
| `jenkins-k8s-job/kubeconfig` | `infra/kubeconfig` |
| `jenkins-k8s-job/setup-kubeconfig.{ps1,sh}` | `infra/scripts/setup-kubeconfig.{ps1,sh}` |
| `jenkins-k8s-job/generate-kubeconfig.ps1` | `infra/scripts/generate-kubeconfig.ps1` |
| `argocd-gitops/Dockerfile` | `infra/Dockerfile.app` (통합) |
| `argocd-gitops/docker-compose.yml` | (삭제) — mysql 은 `infra/docker-compose.jenkins.yml` 의 ncp-cloud-db 사용 |
| `argocd-gitops/kind-config.yml` | (삭제) — `infra/kind-config.yml` 사용 (cluster name 만 다르니 필요시 수정) |
| `argocd-helm/Dockerfile` | `infra/Dockerfile.app` (통합) |
| `jenkins-gitlab-mr/docker-compose.yml` | `infra/docker-compose.gitlab.yml` (gitlab 만 추출, jenkins/mysql 은 jenkins-k8s-job 재사용) |
