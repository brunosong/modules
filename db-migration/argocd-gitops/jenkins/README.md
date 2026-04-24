# Jenkins + ArgoCD 파이프라인 셋업

전체 흐름:
```
[git push 소스 코드]
        ↓
Jenkins: bootJar → docker build → docker push(kind-registry)
        ↓
Jenkins: k8s/migration-job.yml의 image 태그 수정 → git commit & push
        ↓
ArgoCD: git 변경 감지 → Sync → Hook Job 실행 → Flyway
```

---

## 1. GitHub Personal Access Token (PAT) 발급

Jenkins가 manifest 변경을 git에 push하려면 쓰기 권한이 필요합니다.

### Fine-grained PAT (권장)
1. https://github.com/settings/tokens?type=beta 접속
2. **Generate new token**
3. 설정:
   - **Token name**: `jenkins-argocd-gitops`
   - **Expiration**: 90 days (또는 원하는 기간)
   - **Repository access**: `Only select repositories` → `brunosong/modules` 선택
   - **Permissions → Repository permissions**:
     - **Contents**: `Read and write`  ← 필수
     - **Metadata**: `Read-only` (자동 선택됨)
4. **Generate token** → 생성된 토큰(`github_pat_...`) 복사 (페이지 떠나면 다시 못 봄)

### Classic PAT (대안)
https://github.com/settings/tokens 에서 **Generate new token (classic)** → `repo` 스코프 체크 → 생성.

---

## 2. 로컬 Docker Registry 기동 & Kind 연결

Jenkins(`jenkins-k8s`)는 **호스트 Docker socket**을 bind mount해서 사용하므로, push는 호스트 Docker daemon이 실행합니다. 호스트 daemon은 `kind` 네트워크 안의 컨테이너명(`kind-registry`)을 DNS로 찾지 못하기 때문에, **`localhost:5001`(포트 매핑)로 push**하는 방식을 씁니다.

Kind 노드는 `kind-registry:5000`(내부 DNS)으로 pull해야 하므로, containerd에 **`localhost:5001` → `http://kind-registry:5000` 리다이렉트** 설정을 넣어 둡니다.

### 2-1. 레지스트리 컨테이너 기동 + Kind 네트워크 연결
```bash
docker run -d --restart=always \
  --name kind-registry \
  -p 127.0.0.1:5001:5000 \
  registry:2

# Kind 노드가 DNS로 찾을 수 있도록 kind 네트워크에 연결
docker network connect kind kind-registry
```

### 2-2. Kind containerd 패치 (hosts.toml)
```bash
NODE=db-migration-control-plane

docker exec $NODE bash -c '
cat >> /etc/containerd/config.toml << "EOF"

[plugins."io.containerd.grpc.v1.cri".registry]
  config_path = "/etc/containerd/certs.d"
EOF

mkdir -p /etc/containerd/certs.d/localhost:5001
cat > /etc/containerd/certs.d/localhost:5001/hosts.toml << "EOF"
server = "http://kind-registry:5000"

[host."http://kind-registry:5000"]
  capabilities = ["pull", "resolve"]
EOF
'

docker exec $NODE systemctl restart containerd
```

### 2-3. 검증
```bash
# 호스트에서 push
docker pull hello-world
docker tag  hello-world localhost:5001/test:smoke
docker push localhost:5001/test:smoke

# Kind에서 pull (containerd가 kind-registry:5000으로 리다이렉트)
kubectl run smoke-test --image=localhost:5001/test:smoke --restart=Never --rm -it
# → "Hello from Docker!" 출력되면 성공
```

> **주의**: Kind 클러스터를 다시 만들면 containerd 설정이 초기화됩니다. `kind-config.yml`에 `containerdConfigPatches`로 박아두거나, 재생성 후 2-2를 다시 실행해야 합니다.

---

## 3. Jenkins Credential 등록

Jenkins UI → **Manage Jenkins → Credentials → (global) → Add Credentials**

| 항목 | 값 |
|------|-----|
| Kind | `Username with password` |
| Scope | `Global` |
| Username | 본인 GitHub username (예: `brunosong`) |
| Password | 1단계에서 발급한 **PAT** |
| ID | `github-pat` ← **Jenkinsfile이 이 ID를 참조함** |
| Description | GitHub PAT for ArgoCD GitOps pipeline |

---

## 4. Jenkins Pipeline Job 생성

Jenkins UI → **New Item** → `db-migration-argocd-gitops` → **Pipeline** 선택

### Pipeline 설정
- **Definition**: `Pipeline script from SCM`
- **SCM**: `Git`
- **Repository URL**: `https://github.com/brunosong/modules.git`
- **Credentials**: `github-pat` (선택)
- **Branch**: `*/master`
- **Script Path**: `db-migration/argocd-gitops/Jenkinsfile`
- **Lightweight checkout**: ✅ (체크)

### 무한 루프 방지
Jenkins가 manifest를 커밋하면 그 커밋이 다시 Jenkins를 트리거해서 무한 루프가 생길 수 있습니다. 세 가지 방어선을 둡니다.

1. **커밋 메시지에 `[skip ci]` 포함** — Jenkinsfile에 이미 들어 있음
2. **Jenkins Poll SCM에서 경로 필터** — Advanced → `Included Regions` 에 아래 입력:
   ```
   db-migration/argocd-gitops/src/.*
   db-migration/argocd-gitops/build\.gradle
   db-migration/argocd-gitops/Dockerfile
   db-migration/argocd-gitops/Jenkinsfile
   ```
   → manifest (`k8s/*.yml`) 변경만 있는 커밋은 트리거하지 않음
3. **Excluded Regions**에 명시적으로 추가 (선택):
   ```
   db-migration/argocd-gitops/k8s/.*
   ```

### 트리거 방식 (택 1)
- **Poll SCM**: `H/2 * * * *` (2분마다 폴링)
- **GitHub webhook**: Github repo → Settings → Webhooks → `http://<jenkins-host>/github-webhook/` 등록 (Jenkins가 외부에서 접근 가능해야 함)
- **수동**: UI의 **Build Now** 버튼

---

## 5. 첫 실행 체크리스트

- [ ] `kind-registry` 컨테이너 Running & `kind` 네트워크에 연결됨
- [ ] Kind 노드 containerd에 hosts.toml 적용 & containerd 재시작됨
- [ ] `docker push localhost:5001/...` 이 호스트에서 작동
- [ ] `kubectl run ... --image=localhost:5001/...` 이 클러스터에서 작동
- [ ] Jenkins에 `github-pat` credential 등록됨
- [ ] Jenkins 파이프라인 Job 생성됨
- [ ] Jenkins 이미지에 `git`, `docker` CLI, Java 21 (gradlew 실행용) 설치됨

---

## 6. 동작 확인

```bash
# Jenkins 빌드 실행 후 레지스트리에 이미지가 올라갔는지 확인
curl -s http://localhost:5001/v2/db-migration-argocd/tags/list | jq

# git log에서 Jenkins의 commit 확인
git log --oneline -5

# ArgoCD 상태 확인
kubectl -n argocd get application db-migration

# 실제 Job 실행 확인
kubectl get jobs -w

# Job 로그 (Flyway 출력)
kubectl logs -f job/db-migration-argocd-job
```

---

## 트러블슈팅

### `docker push`에서 `no such host` / `connection refused`
- 호스트 Docker daemon이 `localhost:5001`로 접근 못함.
- `docker ps`로 `kind-registry`가 Running이고 `127.0.0.1:5001->5000/tcp` 매핑되어 있는지 확인.

### Kind 노드에서 `ErrImagePull: localhost:5001/...`
- containerd hosts.toml 설정이 누락됨 → 2-2 재실행 & `systemctl restart containerd`.
- `kind-registry`가 `kind` 네트워크에 없음 → `docker network connect kind kind-registry`.

### `gradlew: Permission denied`
- Jenkins 이미지에서 gradlew 실행 권한이 없는 경우.
- Jenkinsfile의 `chmod +x gradlew`가 이미 처리 중. 그래도 안 되면 workspace 퍼미션 확인.

### `git push` 실패 (403)
- PAT 권한 부족 (Contents: Read and write 필요).
- PAT 만료.
- credential ID가 Jenkinsfile의 `github-pat`와 일치하지 않음.

### ArgoCD가 Sync를 안 함
- ArgoCD는 기본 3분마다 폴링 → 즉시 반영하려면 UI에서 **Refresh** 또는 webhook 설정.
- 또는 `kubectl -n argocd annotate app db-migration argocd.argoproj.io/refresh=hard --overwrite`
