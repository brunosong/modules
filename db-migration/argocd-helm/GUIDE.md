# ArgoCD + Helm chart 기반 DB 마이그레이션 가이드

> ⚠️ **2026-04 변경**: `Dockerfile` 이 `db-migration/infra/Dockerfile.app` 으로 통합됨
> (모든 시나리오 공유, 와일드카드 jar). 자세한 내용은 `db-migration/infra/README.md`.

`argocd-gitops` 모듈이 **plain YAML** 로 마이그레이션 Job 을 정의했다면,
이 모듈은 동일한 Job 을 **Helm chart** 로 템플릿화해서 dev/prod 등 **환경별로 다른 값**을 주입하는 방식을 보여준다.

## 무엇이 달라지나

| 구분 | `argocd-gitops` (plain YAML) | `argocd-helm` (Helm chart) |
|------|-----------------------------|---------------------------|
| 소스 형태 | `k8s/*.yml` 정적 manifest | `helm-chart/` 템플릿 + values |
| 환경별 차이 표현 | 파일/브랜치 복제 | `values-<env>.yaml` 한 파일 |
| ArgoCD Application `source` | `path: ...` 만 | `path:` + `helm.valueFiles:` |
| 재사용 | manifest 전체 복붙 | chart 하나 + env 별 override |

**한 문장 요약**: chart 는 "무엇을 배포할지", values 는 "어떻게 배포할지".

---

## 디렉토리 구조

```
db-migration/argocd-helm/
├── GUIDE.md                         ← 이 파일
├── build.gradle                     ← bootJar 빌드
├── Dockerfile
├── src/
│   └── main/
│       ├── java/                    ← Spring Boot + Flyway 엔트리포인트
│       └── resources/
│           ├── application.yml      ← flyway.table 로 schema history 이름 분리
│           └── db/migration/
│               └── V1__create_sample_table.sql
├── helm-chart/
│   ├── Chart.yaml                   ← 차트 메타데이터
│   ├── values.yaml                  ← 기본값
│   ├── values-dev.yaml              ← dev 오버라이드
│   ├── values-prod.yaml             ← prod 오버라이드
│   └── templates/
│       ├── _helpers.tpl             ← 공통 label/fullname 헬퍼
│       ├── db-secret.yaml           ← DB 접속 정보 (values 에서 주입)
│       └── migration-job.yaml       ← Flyway Sync Hook Job
└── argocd/
    ├── application-dev.yml          ← ArgoCD Application (dev, 자동 Sync)
    └── application-prod.yml         ← ArgoCD Application (prod, 수동 승인)
```

---

## Helm 핵심 개념 (초간단)

### 1. 템플릿 + values = 렌더링된 manifest
```
templates/migration-job.yaml + values.yaml + values-dev.yaml
        │
        ▼ (helm render)
        │
   최종 Kubernetes Job YAML
```

### 2. 환경별 values 병합 순서
```
values.yaml (기본값)
  ▲
values-<env>.yaml (환경별 오버라이드, 위에서 얹음)
  ▲
Application.spec.source.helm.parameters (CLI 수준, 제일 위에 얹음)
```
위로 갈수록 우선순위 높음. **중복된 키만 덮어씀**, 아닌 건 그대로 유지.

### 3. 템플릿 문법 3가지만 기억
```yaml
image: "{{ .Values.image.repository }}:{{ .Values.image.tag }}"     # 값 주입
{{- include "db-migration-helm.fullname" . }}                        # 헬퍼 호출
{{- toYaml .Values.job.resources | nindent 12 }}                     # map 을 통째로
```

---

## Step 1 — 사전 준비

### 1-1. 레지스트리 + Kind 구성
`argocd-gitops/GUIDE.md` 와 동일한 로컬 레지스트리 구성이 이미 되어있어야 한다.
- `kind-registry` 컨테이너 Running (`localhost:5001`)
- Kind 노드 containerd 에 `localhost:5001` → `http://kind-registry:5000` 리다이렉트 설정
- 외부 MySQL (`ncp-cloud-db` 또는 `argocd-gitops-mysql`) 가 `external-mysql` Service 뒤에 붙어 있음

### 1-2. Helm CLI 설치 (로컬 렌더링 테스트용, 선택)
```bash
# Windows (Chocolatey)
choco install kubernetes-helm

# 또는 바이너리
# https://github.com/helm/helm/releases
```

ArgoCD 안에는 이미 Helm 이 내장되어 있어서 배포 자체에는 호스트에 Helm 이 없어도 된다.
로컬에서 **템플릿 렌더링 결과를 눈으로 확인**하고 싶을 때만 필요.

---

## Step 2 — 이미지 빌드 & push

```bash
# 프로젝트 루트에서
./gradlew :db-migration:argocd-helm:bootJar

cd db-migration/argocd-helm

# 이미지 빌드 (values.yaml 의 image.repository 와 맞춰야 함)
docker build -t localhost:5001/db-migration-argocd-helm:latest .
docker push    localhost:5001/db-migration-argocd-helm:latest

# 확인
curl -s http://localhost:5001/v2/db-migration-argocd-helm/tags/list
```

---

## Step 3 — (선택) 로컬에서 chart 렌더링 해보기

ArgoCD 없이 chart 가 어떻게 최종 YAML 로 풀리는지 확인:

```bash
cd db-migration/argocd-helm/helm-chart

# dev 환경으로 렌더링
helm template db-migration-helm-dev . -f values-dev.yaml

# prod 환경으로 렌더링
helm template db-migration-helm-prod . -f values-prod.yaml
```

각각의 출력에서 `image:`, `resources:`, `env:` 라벨 등이 어떻게 달라지는지 비교해보면 감이 온다.

---

## Step 4 — ArgoCD Application 등록

### 방법 A: kubectl 로 직접
```bash
# dev (자동 Sync)
kubectl apply -f argocd/application-dev.yml

# prod (수동 Sync)
kubectl apply -f argocd/application-prod.yml
```

### 방법 B: ArgoCD UI
+ NEW APP → Source Repo 입력 → Path 에 `db-migration/argocd-helm/helm-chart` 입력 → Helm 섹션에서 `VALUES FILES` 에 `values-dev.yaml` 추가.

---

## Step 5 — 동작 확인

```bash
# Application 상태 (dev)
kubectl -n argocd get app db-migration-helm-dev

# 실행된 Job
kubectl get jobs -l app.kubernetes.io/name=db-migration-helm

# 로그
kubectl logs -l app.kubernetes.io/instance=db-migration-helm-dev --tail=50

# DB 확인
docker exec ncp-cloud-db mysql -umigration -pmigration1234 modules_db \
  -e "SHOW TABLES; SELECT * FROM flyway_schema_history_argocd_helm;"
```

---

## Step 6 — 환경별 차이 바꿔보기

### 6-1. dev 에서 리소스 키우기
`values-dev.yaml` 에 아래 추가 후 git push:
```yaml
job:
  resources:
    requests:
      memory: 512Mi
```
→ ArgoCD 가 자동 Sync → 새 Job 이 더 큰 리소스로 실행.

### 6-2. 이미지 태그만 오버라이드
Application 의 `helm.parameters` 로도 가능 (values 파일 안 건드리고):
```yaml
# argocd/application-dev.yml 안에서
helm:
  valueFiles:
    - values-dev.yaml
  parameters:
    - name: image.tag
      value: abc1234           # 여기만 바꿔서 commit
```

### 6-3. prod 는 왜 수동 Sync?
`application-prod.yml` 에 `automated` 블록이 없다. 운영에선 DBA/담당자가 UI 에서 **SYNC** 버튼을 눌러야 반영된다.

---

## 정리 (Cleanup)

```bash
kubectl -n argocd delete app db-migration-helm-dev --ignore-not-found
kubectl -n argocd delete app db-migration-helm-prod --ignore-not-found

# 혹시 finalizer 로 안 지워지면
kubectl -n argocd patch app db-migration-helm-dev \
  --type json -p '[{"op":"remove","path":"/metadata/finalizers"}]'
```

---

## 트러블슈팅

### chart path 를 못 찾는다고 나온다
ArgoCD Application 의 `path:` 가 **`...argocd-helm/helm-chart`** 까지 내려가야 한다.
`argocd-helm` 만 지정하면 ArgoCD 가 Chart.yaml 을 못 찾아서 plain directory 로 취급.

### `Replace=true` 때문에 경고
Job 의 `spec.template` 이 immutable 이라 업데이트 시 `kubectl replace` 가 필요하다.
`syncOptions: Replace=true` 가 그걸 처리해준다. 일반 Deployment 앱에선 필요 없음.

### dev/prod 가 같은 이름 충돌
두 Application 다 같은 chart 를 쓰면 리소스 이름이 겹칠 수 있다.
이 모듈은 `fullname = <chart>-<release>` 규칙이라 Application name(=Release name) 이
`db-migration-helm-dev` vs `db-migration-helm-prod` 로 다르면 알아서 분리된다.
같은 네임스페이스에 둘 다 배포해보고 싶으면 `destination.namespace` 를 다르게 주거나
Application name 을 다르게 유지하면 된다.

---

## TODO — 남은 패턴들

이 모듈은 ArgoCD 학습 로드맵의 **3번째 단계**다. 다음 단계들이 남아있다:

### [ ] TODO: 2번 — 상시 실행 앱을 ArgoCD 로 배포
- Job 이 아닌 **Deployment + Service** 를 ArgoCD 로 관리.
- `selfHeal: true` 로 설정해서, 누가 `kubectl scale` 로 임의 변경해도 ArgoCD 가 원복하는 것 체험.
- 이미지 태그 변경 시 rolling update 관찰.
- 적당한 샘플: nginx, 간단한 Spring Boot 웹앱.

### [ ] TODO: 4번 — ApplicationSet 으로 dev/stg/prod 자동 생성
- 지금은 `application-dev.yml`, `application-prod.yml` 을 **수동으로 두 개** 만들었다.
- ApplicationSet Generator 를 쓰면 하나의 선언으로 여러 환경 Application 이 **자동 생성**됨.
- 예시: List generator, Git directory generator.
- chart 와 values 구조는 이 모듈 그대로 재사용 가능.

### [ ] TODO: 5번 — ArgoCD Image Updater 도입
- 지금 파이프라인은 **Jenkins 가 git 에 commit** 해서 이미지 태그를 바꾼다 → 이 때문에 Jenkins 에 PAT 권한이 필요했고 고생했음.
- Image Updater 는 **레지스트리의 새 태그를 ArgoCD 쪽에서 감지**해서 자동으로 값을 반영.
- CI 는 이미지 push 까지만 담당 → **git 쓰기 권한 불필요**.
- Helm values 의 `image.tag` 만 덮어쓰도록 annotation 붙이면 됨:
  ```yaml
  argocd-image-updater.argoproj.io/image-list: app=localhost:5001/db-migration-argocd-helm
  argocd-image-updater.argoproj.io/app.update-strategy: latest
  argocd-image-updater.argoproj.io/app.helm.image-tag: image.tag
  ```

### [ ] TODO: Helm chart 자체 개선
- `NOTES.txt` 추가해서 `helm install` 시 사용법 안내.
- Chart dependencies (`dependencies:` in Chart.yaml) 로 MySQL 같은 서브 차트 같이 배포.
- `ct lint` / `helm lint` 를 CI 에 추가.
- 버전 관리 (`version` 을 semver 로) 와 Helm repo 에 publish 하는 흐름.

### [ ] TODO: 민감정보 처리
- `values-prod.yaml` 의 DB password 가 평문이다 — 실무에선 금지.
- 대안:
  - **Sealed Secrets** (bitnami): git 에 암호화된 Secret 을 두고 클러스터에서만 복호화.
  - **External Secrets Operator** + AWS Secrets Manager / Vault: 런타임 주입.
  - **SOPS** + `argocd-vault-plugin`: values 파일 자체를 암호화해서 git 에 보관.
