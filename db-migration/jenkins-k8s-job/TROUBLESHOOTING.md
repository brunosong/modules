# Jenkins + Kind + DB Migration 트러블슈팅 정리

로컬 환경(Windows + Docker Desktop + Kind + Jenkins)에서 Jenkins K8s Job 기반 DB 마이그레이션
환경을 구축하면서 겪은 문제와 해결 방법을 정리한 문서.

---

## 1. Jenkins에서 `kubectl` 명령어 없음

**에러:**
```
kubectl: command not found
Error when executing failure post condition
```

**원인:** Jenkins 기본 이미지에는 kubectl이 없음.

**해결:** 커스텀 Jenkins 이미지 생성 (`jenkins/Dockerfile`)
- `jenkins/jenkins:lts-jdk21` 기반에 `kubectl`, `kind` 설치
- docker-compose에서 `image` 대신 `build: ./jenkins`

---

## 2. Docker CLI도 없음

**에러:**
```
docker: not found
```

**원인:** 커스텀 이미지에 kubectl만 넣고 docker CLI를 빼먹음.

**해결:** Jenkins Dockerfile에 `docker-ce-cli` 추가 설치.

---

## 3. `docker compose up --build` 해도 이미지 재빌드 안 됨

**원인:** `image:` 태그를 명시하니 Docker가 기존 이미지 재사용.

**해결:** docker-compose.yml에서 `image: jenkins-k8s-custom:latest` 제거 → `build: ./jenkins`만 남김.

---

## 4. Jenkins에서 `kubectl get nodes` 실패 (연결 안 됨)

### 4-1. Connection refused

**에러:**
```
No connection could be made because the target machine actively refused
```

**원인:** Jenkins 컨테이너 안에서 `127.0.0.1`은 컨테이너 자기 자신을 가리킴.
호스트의 Kind API Server에 도달하지 못함.

**해결:** kubeconfig의 서버 주소를 `127.0.0.1` → `host.docker.internal`로 변경.
- 호스트의 `~/.kube/config`는 건드리지 않고 **별도 파일로 추출**해서 수정.
- `generate-kubeconfig.ps1` 스크립트 작성.

### 4-2. TLS 인증서 검증 실패

**에러:**
```
tls: failed to verify certificate: x509: certificate is valid for ..., not host.docker.internal
```

**원인:** Kind 인증서 SAN에 `host.docker.internal`이 없음.

**해결:** kubeconfig의 `certificate-authority-data`를 `insecure-skip-tls-verify: true`로 치환.
- 로컬 개발용이므로 허용.
- 운영 환경(NKS)에서는 정식 인증서 SAN으로 검증.

---

## 5. docker-compose에서 kubeconfig 마운트 에러

**에러:**
```
not a directory: Are you trying to mount a directory onto a file?
```

**원인:**
- `kubeconfig` 파일 없는 상태로 `docker compose up` → Docker가 빈 디렉토리 자동 생성.
- 이전 `~/.kube` 마운트 잔재가 `jenkins-home` 볼륨에 남아있음.

**해결:**
1. `docker compose down`
2. `docker volume rm jenkins-k8s-job_jenkins-home` (볼륨 잔재 제거)
3. `generate-kubeconfig.ps1` **먼저 실행**해서 파일 생성
4. `docker compose up -d --build`

> **순서가 중요:** 반드시 kubeconfig 파일이 존재한 뒤에 compose up.

---

## 6. `.cmd` 스크립트 한글 깨짐

**에러:**
```
'?????'은(는) 내부 또는 외부 명령...
```

**원인:** Windows cmd가 UTF-8 한글 주석을 제대로 읽지 못함.

**해결:** `.cmd` → `.ps1` (PowerShell)로 변경.
- PowerShell은 UTF-8을 기본 지원.
- 구문도 더 깔끔.

---

## 7. NCR Credentials 없어서 빌드 실패

**에러:**
```
ERROR: ncr-credentials
Finished: FAILURE
```

**원인:** Jenkinsfile이 NCP Container Registry 사용 전제 → 로컬 테스트엔 불필요.

**해결:** 로컬 Kind용으로 Jenkinsfile 재작성.
- `docker push` 제거
- `kind load docker-image` 사용

---

## 8. `gradlew` Permission denied

**에러:**
```
../../gradlew: Permission denied
```

**원인:** Windows에서 체크아웃된 `gradlew` 파일에 실행 권한 없음.

**해결:** Jenkinsfile에서 `chmod +x ../../gradlew &&` 먼저 실행.

---

## 9. Git 저장소 없는데 `checkout scm` 사용

**에러:**
```
fatal: not in a git directory
```

**원인:** Git 저장소 설정 안 한 상태로 "Pipeline from SCM" 사용.

**해결:**
- Pipeline 정의를 `Pipeline script`로 변경 (SCM 말고 직접 작성).
- docker-compose에 `../../:/workspace/modules:ro` 마운트해서 로컬 소스 사용.

---

## 10. Docker 소켓 Permission denied

**에러:**
```
permission denied while trying to connect to the docker API at unix:///var/run/docker.sock
```

**원인:** Jenkins 유저(UID 1000)에게 호스트 Docker 소켓 접근 권한 없음.
Windows Docker Desktop에선 docker 그룹 GID 매칭도 까다로움.

**해결:** docker-compose에 `user: root` 추가 (로컬 개발용).

---

## 11. DinD 안에서 빌드한 이미지를 Kind가 못 봄

**상황:** DinD Docker와 호스트 Docker가 완전히 분리돼 있어,
DinD에서 빌드한 이미지를 호스트의 Kind가 `kind load`로 가져올 수 없음.

**해결:** DinD 대신 **호스트 Docker 소켓 공유(Socket Bind)**로 전환.
- `/var/run/docker.sock` 마운트
- DinD 컨테이너는 남겨두되, 기본은 호스트 Docker 사용

**개념 정리:**

| 방식 | 격리 | Kind와 이미지 공유 | Registry 필요 |
|------|------|-----------------|--------------|
| DinD | 높음 | 안 됨 | 필수 |
| Socket Bind | 낮음 | 됨 | 불필요 (로컬) |

---

## 12. `kind load`가 Jenkins 안에서 실패

**에러:**
```
ERROR: failed to detect containerd snapshotter
```

**원인:** Jenkins 컨테이너 안의 kind 바이너리가 Kind 노드의 containerd 구성 감지 실패.
- 버전 불일치
- 환경 차이

**해결:** `kind load` 동작을 수동으로 풀어서 실행.

```bash
docker save <image> -o /tmp/image.tar
docker cp /tmp/image.tar <kind-node>:/tmp/image.tar
docker exec <kind-node> ctr -n k8s.io images import /tmp/image.tar
```

---

## 13. `docker save + docker cp`도 실패 (파일 경로 꼬임)

**에러:**
```
ctr: open /tmp/image.tar: no such file or directory
```

**원인:** 호스트 Docker 소켓 공유 방식에서는 `docker cp`의 로컬 경로 해석이
애매해짐 (client는 Jenkins 컨테이너, daemon은 호스트).

**해결:** 파일을 거치지 않고 **stdin 파이프**로 직접 전달.

```bash
docker save <image> | docker exec -i <kind-node> ctr -n k8s.io images import -
```

tar 스트림이 메모리상에서 바로 전달되므로 파일시스템 경로 문제가 사라진다.

---

## 14. Jenkins `kubectl` 명령이 Jenkins UI 응답을 받음

**에러:**
```
error validating data: failed to download openapi:
<html>...<script ... src='/static/.../scripts/redirect.js'>
Authentication required
```

**원인:** `user: root`로 변경하면서 `HOME=/root`가 되어
kubectl이 `/root/.kube/config`를 찾음. 파일이 없어 기본값(`localhost:8080`)으로
폴백 → 같은 포트의 Jenkins UI에 HTTP 요청을 날림.

**해결:** docker-compose에 `KUBECONFIG` 환경변수 명시.

```yaml
environment:
  - KUBECONFIG=/var/jenkins_home/.kube/config
```

**교훈:** 컨테이너 사용자(UID)를 바꾸면 `$HOME`도 바뀌므로,
홈 디렉토리에 의존하는 모든 도구(kubectl, helm, aws cli 등)는
환경변수로 config 경로를 명시하는 게 안전하다.

---

## 15. Git 저장소 없이 로컬 소스로 Jenkins 돌리기

**상황:** Git 저장소를 아직 안 만들었는데 Jenkins Pipeline을 테스트하고 싶음.

**해결:** 호스트 프로젝트 디렉토리를 Jenkins 컨테이너에 읽기전용 마운트.

```yaml
volumes:
  - ../../:/workspace/modules:ro    # 프로젝트 루트를 컨테이너로 공유
```

- docker-compose.yml 위치 기준 `../../`가 프로젝트 루트 (`D:\SideProject\modules\`)
- 컨테이너 안 경로: `/workspace/modules/`
- `:ro` (read-only) → Jenkins가 소스를 건드릴 수 없음

**Pipeline 설정 변경:**
- Jenkins Job → Pipeline → **Definition: `Pipeline script`** (SCM 아님)
- Jenkinsfile에서 `checkout scm` 제거
- 경로를 `/workspace/modules`로 참조

**장점:** 로컬 파일 수정 → 즉시 반영 (재복사 불필요).
**Git push 이후:** 이 마운트를 제거하고 `Pipeline script from SCM` + `checkout scm`으로 복원.

---

## 핵심 개념 정리

### K8s에서 이미지가 전달되는 경로

```
docker build → 호스트 Docker
                      │
                      ▼ (kind load / docker save+ctr import)
                [Kind 노드 컨테이너]
                      │
                      ▼
                 [containerd]  ← K8s Pod가 여기서 pull
```

- **kubectl은 이미지 관리를 안 한다.** API Server와만 통신.
- 이미지는 **노드의 컨테이너 런타임**(containerd/Docker)이 직접 관리.
- 로컬 K8s 개발에선 결국 Registry 또는 `kind load`/`ctr import` 중 하나로 이미지를 주입해야 함.

### 네트워크 접근

- 호스트 → Kind API Server: `127.0.0.1:<port>` OK
- 컨테이너 → 호스트: `host.docker.internal:<port>` 사용
- `127.0.0.1`은 컨테이너 자기 자신

### DinD vs Socket Bind 선택 기준

- **DinD**: CI 격리 필요, Registry 기반 정식 배포 흐름 모사
- **Socket Bind**: 로컬 Kind 개발, `kind load` 호환성 필요

---

## 자주 쓰는 복구 명령

```powershell
# 전체 정리 후 재시작
docker compose down
docker volume rm jenkins-k8s-job_jenkins-home
docker compose up -d --build

# kubeconfig 재생성
.\generate-kubeconfig.ps1
docker restart jenkins-k8s

# Jenkins에서 K8s 연결 확인
docker exec jenkins-k8s kubectl get nodes

# Jenkins에서 Docker 연결 확인
docker exec jenkins-k8s docker ps

# Jenkins workspace 초기화
docker exec -it jenkins-k8s rm -rf /var/jenkins_home/workspace/<JobName>
```
