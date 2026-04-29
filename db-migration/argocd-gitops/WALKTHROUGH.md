# ArgoCD GitOps + Jenkins 통합 — 작업 노트

로컬 환경에서 GitLab + Jenkins + ArgoCD + kind 클러스터를 묶어서
Push GitOps 파이프라인을 구성하면서 막힌 지점과 결정 사항을 정리한다.
순서는 실제로 부딪힌 순서.

- 호스트: Windows 11
- Docker Desktop / kind 클러스터 (cluster name: `db-migration`)
- 공유 도커 네트워크: `db-migration-net`
- 매니페스트 repo: GitLab (로컬, `host.docker.internal:8090`)
- Jenkins 컨테이너: `db-migration-jenkins` (`db-migration-net`)
- ArgoCD: `argocd` namespace, kind 안

---

## 1. docker-compose 볼륨 마이그레이션

### 문제
`db-migration/infra/docker-compose.jenkins.yml`, `docker-compose.gitlab.yml` 로
구조를 새로 잡으면서 `name:` 필드를 `db-migration-jenkins` /
`db-migration-gitlab` 로 바꿨더니, Compose 가 새 빈 볼륨
(`db-migration-jenkins_jenkins-home` 등) 을 만들어버렸다. 기존 데이터가
들어 있는 볼륨은 `jenkins-k8s-job_jenkins-home` /
`jenkins-gitlab-mr_gitlab-config` 처럼 옛 project name 으로 남아 있었다.

### 해결
컨테이너 이름은 새 규칙으로 유지하면서, 볼륨만 **external 로 옛 이름 참조**.

```yaml
# docker-compose.jenkins.yml
volumes:
  jenkins-home:
    external: true
    name: jenkins-k8s-job_jenkins-home
  docker-certs:
    external: true
    name: jenkins-k8s-job_docker-certs
  mysql-data:
    external: true
    name: jenkins-k8s-job_mysql-data

# docker-compose.gitlab.yml
volumes:
  gitlab-config:
    external: true
    name: jenkins-gitlab-mr_gitlab-config
  gitlab-logs:
    external: true
    name: jenkins-gitlab-mr_gitlab-logs
  gitlab-data:
    external: true
    name: jenkins-gitlab-mr_gitlab-data
```

`down` (볼륨 옵션 없이) → `up -d` 만으로 적용. 비어 있는 신규 볼륨은
정상 동작 확인 후 `docker volume rm` 으로 정리.

---

## 2. GitLab 422 (CSRF / external_url 미스매치)

### 증상
재기동 후 로그인 시도 시 `422: The change you requested was rejected`.

### 원인 두 가지
1. 컨테이너 재기동으로 GitLab secret 이 재생성 → 브라우저의 옛 쿠키와
   불일치 (가장 흔함).
2. `external_url 'http://gitlab.local'` vs 실제 접속 주소
   (`http://localhost:8090`) 불일치 → CSRF 토큰 호스트 검사 실패.

### 해결
- **시크릿 창 / 쿠키 삭제** 로 90% 해결.
- `external_url` 을 실제 접속 URL 과 정확히 맞춤.
  hosts 파일에 `127.0.0.1 gitlab.local` 추가 + `external_url 'http://gitlab.local:8090'`,
  또는 통일해서 `external_url 'http://localhost:8090'`.
- 변경 시 `docker compose ... up -d --force-recreate gitlab` 로 reconfigure.

---

## 3. ArgoCD 접근 — port-forward → NodePort + socat

### 첫 번째 시도: kubectl port-forward
```powershell
kubectl -n argocd port-forward svc/argocd-server 8081:443
```
브라우저: `https://localhost:8081`. 사람이 쓸 때는 OK.
**문제**: 사람의 터미널 세션에 묶여 있어 Jenkins 컨테이너에서 사용 불편.

### 왜 port-forward 는 되는데 NodePort 는 안 되는가
이게 핵심.

| | port-forward | NodePort |
|---|---|---|
| 동작 계층 | apiserver 경유 L7 터널 | 노드의 TCP 포트 바인딩 |
| 호스트 → 노드 | apiserver 매핑 포트 (kind 가 자동 노출) | 노드 컨테이너의 `-p` 매핑 필요 |

`docker ps` 결과:
```
db-migration-control-plane   0.0.0.0:30306->30306/tcp, 127.0.0.1:61053->6443/tcp
```
→ kind-config.yml 의 `extraPortMappings` 에 30306 (MySQL) 만 박혀 있었다.
30443 은 노드 안에 바인딩은 됐지만 **호스트로 가는 길이 없음**.
`extraPortMappings` 는 클러스터 생성 시점에만 적용됨 →
재생성 없이 새 NodePort 호스트 노출 불가.

### 두 번째 시도: socat 포워더 (옵션 C)

`db-migration/infra/argocd-server-nodeport.yml` 로 ArgoCD Service 를
NodePort (30443/30080) 로 변경.

`db-migration/infra/docker-compose.argocd-fwd.yml` 로 socat 컨테이너
하나를 띄움. **두 네트워크에 동시 소속**:

```yaml
services:
  argocd-fwd:
    image: alpine/socat:latest
    container_name: db-migration-argocd-fwd
    ports:
      - "30443:30443"
    command: TCP-LISTEN:30443,fork,reuseaddr TCP-CONNECT:db-migration-control-plane:30443
    networks:
      - db-migration-net      # Jenkins 등이 이름으로 호출
      - kind                  # kind 컨트롤플레인이 사는 네트워크

networks:
  db-migration-net:
    name: db-migration-net
    external: true
  kind:
    name: kind
    external: true
```

> **삽질 포인트**: 처음엔 `db-migration-net` 만 붙였는데
> `db-migration-control-plane` 이름이 풀리지 않았다.
> 이유: 컨트롤플레인은 `kind` 네트워크에만 있었음.
> `kind` 네트워크에 socat 을 같이 붙이면 클러스터 재생성에도
> 안정적으로 동작 (control-plane 이 새로 떠도 같은 네트워크 안의 새 컨테이너).

### 결과
| 출발 | URL |
|------|-----|
| 호스트 브라우저 | https://localhost:30443 |
| Jenkins 컨테이너 | https://db-migration-argocd-fwd:30443 |

자체 서명 인증서이므로 CLI 는 `--insecure --grpc-web` 사용.

---

## 4. ArgoCD 기본 — admin 비번, AppProject, Sync 옵션

### 초기 admin 비번 (PowerShell)
```powershell
$pw = kubectl -n argocd get secret argocd-initial-admin-secret -o jsonpath="{.data.password}"
[System.Text.Encoding]::UTF8.GetString([System.Convert]::FromBase64String($pw))
```
이 secret 은 **첫 비번 변경 시 ArgoCD 가 자동 삭제**.

설치 시점에 비번을 직접 박고 싶으면 **bcrypt 해시** 로만 가능
(`argocd account bcrypt --password '...'` 또는 `htpasswd -nbBC 10`).

### AppProject 의 의미
"여러 Application 을 묶는 권한·정책 그룹". K8s namespace 와 별개.

| 항목 | 통제 대상 |
|------|---------|
| `sourceRepos` | 가져올 수 있는 Git/Helm repo |
| `destinations` | 배포 가능한 클러스터 + namespace 조합 |
| `clusterResourceWhitelist` | 클러스터 레벨 리소스 (Namespace, ClusterRole, CRD…) |
| `namespaceResourceWhitelist` | namespace 레벨 리소스 종류 |
| `roles` | 프로젝트 안 RBAC |
| `signatureKeys` | 서명된 commit 만 sync 허용 (GPG) |
| `syncWindows` | 시간대 제한 |

`default` 프로젝트는 **모든 게 `*`** 로 열려 있음. 새로 만든 프로젝트는
**clusterResourceWhitelist 가 비어 있음 = 전부 거부** 가 기본값.

본인 케이스에서 `flyway` Application 이 `project: db-migration`,
destinations 가 비어 있어
`destination ... do not match any of the allowed destinations` 에러 발생.

해결:
```yaml
spec:
  destinations:
    - server: https://kubernetes.default.svc
      namespace: '*'
  sourceRepos:
    - '*'
```
또는 `project: default` 로 변경.

### Cluster-scoped 리소스 종류
`kubectl api-resources --namespaced=false` 로 확인. 자주 쓰는 것:
`Namespace`, `Node`, `PersistentVolume`, `ClusterRole`,
`ClusterRoleBinding`, `CustomResourceDefinition`,
`MutatingWebhookConfiguration`, `StorageClass`, `IngressClass`,
`PriorityClass`. cert-manager 의 `ClusterIssuer` 등 서드파티 추가됨.

DB 마이그레이션 Job 용도라면 보통 `Namespace` 만 추가하면 충분.

### Sync Policy 옵션 정리

**Automatic 그룹**
- **Enable Auto-Sync** — Git 변경 시 자동 sync. 운영은 끄는 경우 많음.
- **Prune Resources** — Git 에서 빠진 리소스를 클러스터에서도 삭제.
- **Self Heal** — kubectl edit 같은 수동 변경을 즉시 Git 상태로 되돌림.
- **Set Deletion Finalizer** — Application 삭제 시 자식 리소스까지 cascade.

**Sync Options 그룹**
- **Skip Schema Validation** — CRD 함께 적용 시 등.
- **Auto-Create Namespace** — destination namespace 자동 생성.
- **Prune Last** — 새 리소스 적용 후 삭제 → 다운타임 감소.
- **Apply Out of Sync Only** — 차이 나는 리소스만 apply (성능).
- **Respect Ignore Differences** — `ignoreDifferences` 를 sync 시점에도 적용.
- **Server-Side Apply** — `kubectl apply --server-side`. 큰 매니페스트 / 다중 컨트롤러 환경에 유리.
- **Prune Propagation Policy** — `foreground` (자식 먼저 정리) / `background` / `orphan`.
- **Replace** — `kubectl replace`. Job 같은 immutable 리소스에 필요. 위험.

**Retry**: 일시 장애 자동 복구. 켜두면 안정성 ↑.

**Job 중심 Application 추천**: Replace ✓ (또는 리소스 단위 어노테이션
`argocd.argoproj.io/sync-options: Replace=true`), Self Heal 보통 ✗.

---

## 5. ArgoCD 가 로컬 GitLab 에 어떻게 닿는가

K8s 안 ArgoCD 파드에서 `localhost:8090` 은 자기 자신.
호스트의 GitLab 컨테이너에 닿으려면:

### 채택: `host.docker.internal:8090`
Docker Desktop 이 컨테이너 안 `host.docker.internal` → 호스트 IP 로
자동 매핑. ArgoCD repoURL:
```yaml
repoURL: http://host.docker.internal:8090/brunosong/modules.git
```

파드에서 동작 검증:
```powershell
kubectl run nettest --rm -it --image=busybox --restart=Never -- wget -qO- http://host.docker.internal:8090
```

만약 파드 DNS 가 `host.docker.internal` 못 풀면 CoreDNS hosts 블록 추가:
```
hosts {
    host-gateway host.docker.internal
    fallthrough
}
```

(대안: kind 컨트롤플레인을 `db-migration-net` 에 붙이고 GitLab 컨테이너
이름으로 접근 + CoreDNS 에 `gitlab.local` IP 등록 — 더 복잡해서 보류.)

---

## 6. CI/CD 패턴 — Push GitOps + Manifest Repo

### 표준 흐름
```
[코드 push]
  → Jenkins 빌드
  → 이미지 빌드 + 레지스트리 push
  → 매니페스트 repo 의 image tag 갱신 + commit/push
  → ArgoCD 가 변경 감지 (poll ~3분 또는 webhook)
  → 클러스터에 새 이미지로 sync
```

매니페스트가 안 변하면 ArgoCD 는 새 이미지를 배포할 수 없음.
`latest` + `imagePullPolicy: Always` 우회는 GitOps 가 아님.

### 두 repo 분리 vs 단일 repo
표준은 코드 repo + 매니페스트 repo 분리. 본인은 같은 repo 안에
`db-migration/argocd-gitops/k8s/` 로 둠 — 학습/단순화 OK.

---

## 7. GitLab Files API 로 commit (`Jenkinsfile.gitlab-api`)

`git push` 대신 GitLab REST API 로 commit 을 만든다.
동료 환경에서 본 패턴.

```bash
POST /api/v4/projects/{ID}/repository/commits
  branch         = main
  commit_message = ci: bump image to <tag> [skip ci]
  actions[][action]    = update
  actions[][file_path] = db-migration/argocd-gitops/k8s/image-patch.yaml
  actions[][content]   = - op: replace
                          path: /spec/template/spec/containers/0/image
                          value: <registry>/<image>:<tag>
```

핵심 — `FILE_PATH` 가 가리키는 파일은 **매번 통째로 교체**된다.
그래서 그 파일에는 **자주 바뀌는 1줄짜리 정보만** 둬야 한다.

### 필수 준비
- Jenkins Credentials: Secret text, ID `gitlab-api-token`,
  값 = GitLab Personal Access Token (`api` scope)
- `[skip ci]` 메시지로 매니페스트 repo webhook 의 무한 루프 방지

### 보안 함정
동료 로그에서 `PRIVATE-TOKEN: glpat-...` 가 평문으로 찍힘 →
Credentials Binding 으로 감싸지 않으면 `withCredentials` 마스킹 못 받음.
반드시 `withCredentials([string(credentialsId:'gitlab-api-token', variable:'GITLAB_TOKEN')]) { sh '...' }` 로.

---

## 8. Kustomize image-patch.yaml — Job 매니페스트 분리

본인 매니페스트는 단일 `migration-job.yml` 에 image 라인까지 박혀
있었다. Files API 로 통째 교체하는 건 위험.

### 채택: 동료 패턴 (JSON6902 image-patch)

`db-migration/argocd-gitops/k8s/kustomization.yaml`
```yaml
apiVersion: kustomize.config.k8s.io/v1beta1
kind: Kustomization
resources:
  - migration-job.yml
  - db-secret.yml
patches:
  - path: image-patch.yaml
    target:
      kind: Job
      name: db-migration-argocd-job
```

`db-migration/argocd-gitops/k8s/image-patch.yaml` (Jenkins 가 매번 덮어씀)
```yaml
- op: replace
  path: /spec/template/spec/containers/0/image
  value: localhost:5001/db-migration-argocd:12690fe
```

`migration-job.yml` 은 그대로. ArgoCD Application 의 `path:
db-migration/argocd-gitops/k8s` 가 kustomization.yaml 를 자동 인식 →
Kustomize 빌드 시 image 필드를 patch 로 치환.

Job 이라도 image 필드 경로
(`/spec/template/spec/containers/0/image`) 는 Deployment 와 동일 →
같은 patch 로 동작.

---

## 9. CI/CD 관측 단절 → CI-driven sync (Jenkinsfile.gitlab-api-cd)

### 문제 인식
Jenkins 가 commit/push 후 SUCCESS 로 종료 → 그 뒤 ArgoCD 가 sync 실패해도
**Jenkins 만 보는 사람은 모름**. CI 와 CD 의 관측 채널이 분리됨.

### 해결: Auto-sync 끄고 Jenkins 가 직접 sync 트리거 + 결과 대기

ArgoCD Application 의 `syncPolicy.automated` 블록 제거.

Jenkinsfile 에 두 stage 추가:
1. **Trigger ArgoCD sync** — `POST /api/v1/applications/{name}/sync` body `{"revision":"<SHA>","prune":true}`. 방금 만든 commit 으로만 sync.
2. **Wait for Healthy** — `GET /api/v1/applications/{name}` 폴링.
   `sync=Synced && health=Healthy && revision==우리 SHA` 가 될 때까지
   대기. Degraded 면 즉시 실패.

### 장점
- **결정론적 타이밍** — polling 주기 (~3분) 무시
- **CI/CD 통합** — Jenkins 빌드 결과 = 실제 배포 결과 → Slack 등 기존 알림 재활용
- **원자성** — `--revision` 으로 동시 빌드 충돌 방지
- **드리프트 자가 정합** — 매 빌드마다 강제 sync

### 트레이드오프
- ArgoCD 가용성에 빌드 종속
- Git 직접 commit (Jenkins 우회) 으로는 배포 안 됨 → 팀 룰 명시
- Jenkins → ArgoCD 권한 토큰 관리

### Job + Hook 의 동작
`migration-job.yml` 의 `argocd.argoproj.io/hook: Sync` 어노테이션 →
sync 트리거 시 Job 새로 생성, `hook-delete-policy: BeforeHookCreation`
으로 이전 Job 자동 정리. **Job 이 끝날 때까지 wait stage 가 대기** →
마이그레이션 시간을 SYNC_TIMEOUT 에 반영.

---

## 10. ArgoCD CLI 통합

### Jenkins 이미지에 설치 (`infra/Dockerfile.jenkins`)
```dockerfile
RUN curl -Lo /usr/local/bin/argocd https://github.com/argoproj/argo-cd/releases/latest/download/argocd-linux-amd64 \
    && chmod +x /usr/local/bin/argocd
```

재빌드:
```powershell
docker compose -f db-migration/infra/docker-compose.jenkins.yml build jenkins
docker compose -f db-migration/infra/docker-compose.jenkins.yml up -d jenkins
```
`jenkins-home` 볼륨은 그대로라 설정/Job 보존.

### Windows 호스트에 CLI 설치
```powershell
winget install argocd
```
없으면 수동:
```powershell
$dest = "$env:USERPROFILE\bin"
New-Item -ItemType Directory -Force -Path $dest | Out-Null
Invoke-WebRequest -Uri "https://github.com/argoproj/argo-cd/releases/latest/download/argocd-windows-amd64.exe" -OutFile "$dest\argocd.exe"
# PATH 추가
$current = [Environment]::GetEnvironmentVariable("Path", "User")
if ($current -notlike "*$dest*") {
    [Environment]::SetEnvironmentVariable("Path", "$current;$dest", "User")
}
```

### 인증 — CI 에서는 환경변수 토큰 (login 불필요)
```bash
export ARGOCD_SERVER=db-migration-argocd-fwd:30443
export ARGOCD_AUTH_TOKEN=<발급받은 토큰>
export ARGOCD_OPTS='--grpc-web --insecure'

argocd app sync db-migration --revision $SHA --prune
argocd app wait db-migration --sync --health --timeout 300
```

REST 30+ 줄짜리 폴링이 CLI 에서 **2 줄로** 줄어듬.

### CI 전용 계정 만들기 (admin 토큰 직접 사용 비추)
```bash
kubectl -n argocd patch configmap argocd-cm --type merge \
  -p '{"data":{"accounts.jenkins":"apiKey"}}'

kubectl -n argocd patch configmap argocd-rbac-cm --type merge \
  -p '{"data":{"policy.csv":"p, role:ci, applications, sync, */*, allow\np, role:ci, applications, get, */*, allow\ng, jenkins, role:ci"}}'

argocd account generate-token --account jenkins
```

발급된 토큰을 Jenkins Credentials 에 Secret text / ID
`argocd-auth-token` 으로 등록.

---

## 11. Jenkins SCM 설정 (Pipeline script vs from SCM)

### 두 모드
| | Pipeline script | Pipeline script from SCM |
|---|---|---|
| Jenkinsfile 위치 | Jenkins UI 텍스트박스 | Git repo 안 |
| SCM 설정 | 불필요 | URL/Credentials/Branch/Script Path 입력 |
| 빌드 시작 시 | 워크스페이스 빈 상태 | 자동 clone |
| 버전관리 | Jenkins audit log 만 | Git 히스토리 |

### `checkout scm` 의미
Job 설정의 SCM 정보로 다시 clone (재시작 / 다른 브랜치 체크아웃 등).
`Pipeline script` 모드에서 호출하면 **에러** (참조할 SCM 없음).

본인의 `Jenkinsfile.gitlab-api`, `Jenkinsfile.gitlab-api-cd` 는
외부 API 만 호출하므로 **Pipeline script 모드로도 그대로 동작**.

### Jenkins Credentials 등록 (HTTPS + 토큰)
Manage Jenkins → Credentials → System → Global → Add:
- Kind: **Username with password**
- Username: GitLab user
- Password: GitLab PAT
- ID: 예 `gitlab-https-credential` (Jenkinsfile 에서 참조)

### Git clone 방법 (참고)
| 방법 | 사용 시점 |
|------|---------|
| `checkout scm` | Job 이 SCM 모드로 등록됐을 때 — Jenkinsfile 한 줄 |
| `git url:..., credentialsId:..., branch:...` | 다른 repo 추가 clone — 짧은 1 줄 |
| `checkout([$class:'GitSCM', ...])` | shallow / sparse / 서브디렉토리 등 옵션 필요 |
| `withCredentials + sh "git clone https://${USER}:${TOKEN}@..."` | SCM step 이 안 되는 특수 옵션 |
| `sshagent(['ssh-cred']) { sh 'git clone git@...' }` | SSH 키 인증 |

본인의 GitLab API 패턴은 **clone 자체가 불필요** — Files API 가
조회/수정을 다 처리.

### Jenkins → GitLab URL 의 함정
Job 의 SCM URL 은 **Jenkins 컨테이너 관점의 주소** 여야 함.
`localhost:8090` 은 Jenkins 자기 자신을 가리키므로 안 됨.

| 어디서 보느냐 | URL |
|--------------|-----|
| 호스트 브라우저 | http://localhost:8090/... |
| Jenkins 컨테이너 → GitLab | http://db-migration-gitlab/... 또는 http://gitlab.local/... |

---

## 12. 파일 인벤토리

```
db-migration/
├── infra/
│   ├── docker-compose.jenkins.yml          # external 볼륨 (jenkins-k8s-job_*)
│   ├── docker-compose.gitlab.yml           # external 볼륨 (jenkins-gitlab-mr_*)
│   ├── docker-compose.argocd-fwd.yml       # socat: 호스트:30443 → kind 컨트롤플레인
│   ├── argocd-server-nodeport.yml          # ArgoCD Service 를 NodePort 로 변경
│   ├── Dockerfile.jenkins                  # docker / kubectl / kind / argocd CLI
│   ├── kind-config.yml                     # extraPortMappings: 30306 만 (NodePort 호스트 노출은 socat 으로 우회)
│   └── README.md                           # 인프라 사용법
│
└── argocd-gitops/
    ├── argocd/
    │   ├── application.yml                 # repoURL → host.docker.internal:8090
    │   └── application-prod.yml
    ├── k8s/
    │   ├── kustomization.yaml              # base + image-patch
    │   ├── migration-job.yml               # Job + sync hook
    │   ├── image-patch.yaml                # Jenkins 가 매번 덮어쓰는 1줄 patch
    │   └── db-secret.yml
    ├── Jenkinsfile                         # 코드 repo SCM 기반 (기존)
    ├── Jenkinsfile.gitlab-api              # CI 전용: GitLab API commit 만
    ├── Jenkinsfile.gitlab-api-cd           # CI + CD: commit + sync 트리거 + 결과 대기
    └── WALKTHROUGH.md                      # 이 파일
```

---

## 13. 자주 쓰는 명령어

```powershell
# 인프라 기동/정리
docker compose -f db-migration/infra/docker-compose.gitlab.yml up -d
docker compose -f db-migration/infra/docker-compose.jenkins.yml up -d
docker compose -f db-migration/infra/docker-compose.argocd-fwd.yml up -d

# kind
kind create cluster --config db-migration/infra/kind-config.yml
kind delete cluster --name db-migration

# ArgoCD
kubectl apply -f db-migration/infra/argocd-server-nodeport.yml
$pw = kubectl -n argocd get secret argocd-initial-admin-secret -o jsonpath="{.data.password}"
[System.Text.Encoding]::UTF8.GetString([System.Convert]::FromBase64String($pw))

argocd login localhost:30443 --username admin --password $plainPw --grpc-web --insecure
argocd app list
argocd app sync db-migration --revision <SHA> --prune
argocd app wait db-migration --sync --health --timeout 300

# 헬스체크
curl -k https://localhost:30443/healthz
docker exec db-migration-jenkins curl -k https://db-migration-argocd-fwd:30443/healthz
```

---

## 14. 다음에 손볼 거리 (열린 항목)

- ArgoCD Notifications (Slack/Webhook) 설정 — Jenkins wait 와 별도로
  이중 안전망
- CI 전용 ArgoCD 계정 + RBAC 적용 (현재는 admin 토큰)
- 매니페스트 repo 를 별도 GitLab project 로 분리 (현재는 같은 repo)
- prod 용 syncWindow / 서명 commit 강제
