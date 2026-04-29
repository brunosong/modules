# GitLab MR → Jenkins 트리거 실전 워크스루

이 문서는 처음부터 끝까지 동작시키는 동안 실제로 막혔던 지점과 해결법을
순서대로 정리한 것이다. 풀 레퍼런스는 `GUIDE.md` 참조.

> 가정: Jenkins / DinD / MySQL 은 `infra/docker-compose.jenkins.yml`
> (project name `jenkins-k8s-job`) 로 이미 떠 있음. GitLab 만 이 워크스루로 새로 띄움.
>
> ⚠️ **2026-04 변경**: 모든 도커 파일이 `db-migration/infra/` 로 통합됨.
> 이 문서의 명령어도 새 경로로 업데이트됨.

---

## Step 1. GitLab 컨테이너 기동

```bash
# 프로젝트 루트(modules) 에서
docker compose -f db-migration/infra/docker-compose.gitlab.yml up -d
docker compose -f db-migration/infra/docker-compose.gitlab.yml logs -f gitlab
# "gitlab Reconfigured!" 까지 대기 (5~10분)
```

부팅 확인:

```bash
docker exec jenkins-gitlab-mr-gitlab gitlab-ctl status
# 모든 서비스가 'run:' 으로 떠야 정상
```

| 서비스 | 호스트 포트 | 접속 |
|--------|----------|------|
| GitLab | 8090 | http://localhost:8090 |
| Jenkins | 8080 | http://localhost:8080 |
| MySQL | 3309 | `mysql -h 127.0.0.1 -P 3309 -u migration -pmigration1234` |

---

## Step 2. GitLab root 계정 만들기 (수동)

`initial_root_password` 환경변수가 안 먹고 `users` 테이블이 비어 있는
케이스가 있었다. 직접 만든다.

```bash
# 0건 확인
docker exec jenkins-gitlab-mr-gitlab gitlab-psql -d gitlabhq_production \
    -c "SELECT id, username FROM users;"
# (0 rows)

# 스크립트 작성
docker exec -i jenkins-gitlab-mr-gitlab bash -c 'cat > /tmp/create-root.rb' <<'RUBY'
password = 'Xq7$kP9wL2vN4mR8'
user = User.new(
  username: 'root',
  email: 'admin@example.com',
  name: 'Administrator',
  password: password,
  password_confirmation: password,
  admin: true,
  confirmed_at: Time.now
)
user.assign_personal_namespace(Organizations::Organization.default_organization) if user.respond_to?(:assign_personal_namespace)
user.skip_confirmation!
user.password_automatically_set = false
puts(user.save ? "OK id=#{user.id}" : "FAIL: #{user.errors.full_messages.join(' | ')}")
RUBY

# 실행 (Git Bash 면 MSYS_NO_PATHCONV=1 필요)
MSYS_NO_PATHCONV=1 docker exec jenkins-gitlab-mr-gitlab \
    gitlab-rails runner /tmp/create-root.rb
# → OK id=1
```

→ http://localhost:8090 에서 `root` / `Xq7$kP9wL2vN4mR8` 로 로그인.

> 비번은 영/숫/특/대소문자 섞인 강한 값이어야 GitLab 의 weak-password 검사를 통과.
> `gitlabroot1234!` 같은 흔한 패턴은 거절됨.

---

## Step 3. GitLab 프로젝트 생성 + clone

GitLab UI → **New project → Create blank project**:

- Project name: `db-migration-demo`
- Visibility: Internal
- ☐ Initialize with README (직접 push 할 거니까 해제)

### Clone (IntelliJ 의 GitLab 통합은 토큰 요구해서 까다로움 — 터미널이 빠름)

```bash
cd D:/SideProject     # 원하는 워크스페이스
git clone "http://root:Xq7\$kP9wL2vN4mR8@localhost:8090/root/db-migration-demo.git"
# IntelliJ 에서는 'Open' 으로 폴더만 열기
```

기존 `modules` 디렉토리에 remote 만 추가하는 방법:

```bash
cd D:/SideProject/modules
git remote add gitlab "http://root:Xq7\$kP9wL2vN4mR8@localhost:8090/root/db-migration-demo.git"
git push -u gitlab master
```

---

## Step 4. Jenkins 와 GitLab 같은 docker network 에 연결

기존 `jenkins-k8s` 는 다른 compose 의 네트워크에 있어서 `gitlab.local` 이
안 보인다. 한 줄로 연결 (Jenkins 재시작 불필요):

```bash
docker network connect jenkins-gitlab-mr_jenkins-gitlab-mr-net jenkins-k8s
```

양방향 통신 확인:

```bash
# Jenkins → GitLab
docker exec jenkins-k8s curl -sS -o /dev/null -w "%{http_code}\n" \
    http://gitlab.local/api/v4/version
# → 200

# GitLab → Jenkins
docker exec jenkins-gitlab-mr-gitlab curl -sS -o /dev/null -w "%{http_code}\n" \
    http://jenkins-k8s:8080/login
# → 200
```

> 호스트 재부팅 후엔 다시 `docker network connect` 해줘야 한다 (영구화는
> compose 의 external network 설정으로 가능 — 데모는 그냥 매번 한 줄).

---

## Step 5. GitLab Personal Access Token 발급

Jenkins 의 GitLab Plugin 이 GitLab API 를 부를 때 사용한다.

GitLab → 우상단 아바타 → **Edit profile → Access Tokens**:

| 항목 | 값 |
|------|-----|
| Name | `jenkins` |
| Scopes | ☑ `api` ☑ `read_api` ☑ `read_repository` |
| Expiration | (비우면 1년) |

→ **Create personal access token** → **표시된 토큰 즉시 복사** (한 번만 보임)

---

## Step 6. Jenkins — GitLab Plugin 설치 + Connection 등록

### 6-1. Plugin 설치

**Manage Jenkins → Plugins → Available plugins** → "GitLab" 검색 → 설치 후 재시작.

### 6-2. Credential 등록

**Manage Jenkins → Credentials → System → Global → Add Credentials**

| 항목 | 값 |
|------|-----|
| Kind | **GitLab API token** *(일반 Secret text 아님!)* |
| Scope | Global |
| API token | (Step 5 에서 복사한 토큰) |
| ID | `gitlab-api-token` |

> "GitLab API token" 종류가 드롭다운에 없으면 → Plugin 미설치/재시작 안 됨.

### 6-3. Connection 등록

**Manage Jenkins → System → "Gitlab" 섹션**

| 항목 | 값 |
|------|-----|
| Connection name | `gitlab-local` |
| GitLab host URL | `http://gitlab.local` |
| Credentials | `gitlab-api-token` |
| ☑ Enable authentication checks to GitLab API | 체크 |

→ **Test Connection** → "Success".

> URL 에 `localhost:8090` 박으면 안 됨. Jenkins 컨테이너 안에서 보는 주소를 써야 함.

---

## Step 7. Jenkins Pipeline Job 생성

**New Item → 이름 `test` → Pipeline → OK**

### Pipeline 섹션 — 일단 SCM 없이 echo 만

GitLab "Pipelines must succeed" 게이트를 풀려면 빌드 결과를
`updateGitlabCommitStatus` 로 명시적으로 보내야 한다. 안 보내면 GitLab 이
영원히 spinning 상태로 머지 차단.

```groovy
pipeline {
    agent any

    options {
        gitLabConnection('gitlab-local')
    }

    stages {
        stage('Hello') {
            steps {
                updateGitlabCommitStatus name: 'jenkins', state: 'running'
                echo "GitLab webhook 잘 받았다"
                echo "event = ${env.gitlabActionType}"
                echo "source = ${env.gitlabSourceBranch}"
                echo "target = ${env.gitlabTargetBranch}"
            }
        }
    }

    post {
        success { updateGitlabCommitStatus name: 'jenkins', state: 'success' }
        failure { updateGitlabCommitStatus name: 'jenkins', state: 'failed' }
    }
}
```

### Build Triggers 섹션

☑ **Build when a change is pushed to GitLab** → **Advanced**:

| 항목 | 값 |
|------|-----|
| ☑ Opened Merge Request Events | 체크 |
| ☑ Accepted Merge Request Events | 체크 |
| ☐ Push Events | 해제 |
| Allowed branches | **All branches** *(나중에 master 로 좁힘)* |
| Secret token | **Generate** → 표시된 값 메모 |

→ **Save** → 한 번 **Build Now** 수동 실행 → ✅ 성공 확인 (스크립트 valid 체크).

> Pipeline script from SCM 이든 inline 이든, 첫 빌드 한 번이 돌아야 trigger
> 등록이 확정된다.

---

## Step 8. GitLab Webhook 등록

### 8-1. SSRF 차단 풀기 (사전 작업)

GitLab 14+ 는 사설망 IP / 도커 내부 호스트로의 webhook 을 기본 차단.

**Admin Area** (http://localhost:8090/admin) → **Settings → Network → Outbound requests**:

| 옵션 | 설정 |
|------|-----|
| ☑ **Allow requests to the local network from webhooks and integrations** | 체크 |

→ **Save changes**.

> 안 풀면 webhook 저장 시 "Url is blocked: Requests to the local network are not allowed" 에러.

### 8-2. Webhook 등록

프로젝트 → **Settings → Webhooks → Add new webhook**:

| 항목 | 값 |
|------|-----|
| URL | `http://jenkins-k8s:8080/project/test` |
| Secret token | (Step 7 의 Generate 값) |
| Trigger | ☑ **Merge request events** |
| ☐ Push events | 해제 |
| ☐ Enable SSL verification | 해제 |

→ **Add webhook**.

#### URL 절대 헷갈리지 말 것

| 잘못된 값 | 왜 안 됨 |
|---------|---------|
| `http://gitlab.local/project/test` | gitlab.local 은 GitLab 자기 자신 → 422 (GitLab 의 에러 페이지) |
| `http://localhost:8080/project/test` | GitLab 컨테이너 안의 localhost = GitLab 자기 자신 → 똑같이 실패 |
| `http://jenkins-k8s:8080/job/test/build` | GitLab Plugin 의 endpoint 는 `/project/<JOB>` 형식 |
| **`http://jenkins-k8s:8080/project/test`** | **정답** |

---

## Step 9. 동작 확인 — 진짜 MR 만들기

> **GitLab 의 webhook "Test" 버튼은 가짜 payload 를 보내고 빌드를 트리거하지 않는다.**
> 응답 200 떠도 Jenkins 는 "새 커밋 없음" 으로 조용히 스킵한다.
> 반드시 진짜 MR 으로 테스트.

```bash
git checkout master
git pull gitlab master 2>/dev/null || true

git checkout -b test/real-trigger
echo "-- $(date)" >> db-migration/jenkins-gitlab-mr/src/main/resources/db/migration/V1__create_sample_table.sql
git add . && git commit -m "test trigger"
git push -u gitlab HEAD
```

push 출력에 나오는 MR 링크 클릭 → Source `test/real-trigger` / Target `master`
→ **Create merge request**.

**기대 동작**: Jenkins → `test` Job 에 새 빌드 자동 시작 → 콘솔에 echo 메시지.

---

## Step 10. Merge 버튼이 안 보일 때 — 두 번째 사용자로 진행

`Pipelines must succeed` 통과되고 모든 권한도 풀었는데도 머지 버튼이
안 뜨는 경우 — 거의 항상 **MR 작성자 = root** 인데 root 가
**프로젝트 멤버가 아니어서** 발생한다. CE 의 흔한 함정.

> Admin (인스턴스 관리자) ≠ Project Member.
> Protected branch 의 "Allowed to merge" 는 **프로젝트 Member 의 role** 로
> 판정하므로, root 가 admin 이어도 멤버가 아니면 권한 0.
>
> MR 위젯의 "Ready to merge by members who can write to the target branch"
> 문구는 *현재 보고 있는 사용자가 그 권한이 없다* 는 뜻이기도 함.

### 해결 — 일반 사용자 만들어서 그 계정으로 진행 (권장)

```bash
# brunosong 계정 생성 (root 만들었던 패턴 재사용)
docker exec -i jenkins-gitlab-mr-gitlab bash -c 'cat > /tmp/create-user.rb' <<'RUBY'
password = 'Devpass!9X2qLm$Kp'
user = User.new(
  username: 'brunosong',
  email: 'brunosong@example.com',
  name: 'Bruno Song',
  password: password,
  password_confirmation: password,
  admin: false,
  confirmed_at: Time.now
)
user.assign_personal_namespace(Organizations::Organization.default_organization) if user.respond_to?(:assign_personal_namespace)
user.skip_confirmation!
user.password_automatically_set = false
puts(user.save ? "OK id=#{user.id}" : "FAIL: #{user.errors.full_messages.join(' | ')}")
RUBY
MSYS_NO_PATHCONV=1 docker exec jenkins-gitlab-mr-gitlab \
    gitlab-rails runner /tmp/create-user.rb
```

→ GitLab 프로젝트 → **Manage → Members → Invite members** → `brunosong` 을
**Maintainer** 로 추가.

→ 시크릿 창 / 다른 브라우저로 `brunosong` 로그인 → 같은 MR → 머지 버튼이 보임.

### 정착시키기

이 시점부터 **모든 개발 행위는 brunosong 으로 통일** 하는 게 자연스럽다:

| 항목 | 변경 |
|------|-----|
| `git push` 의 remote URL | `http://brunosong:Devpass!9X2qLm$Kp@localhost:8090/...` 또는 PAT |
| Jenkins Git credential | brunosong PAT 로 교체 |
| Jenkins → GitLab API token | brunosong PAT 로 교체 |
| MR 작성 / 머지 | 모두 brunosong 로 |

root 는 인스턴스 관리 (Admin Area) 만 담당.

---

## 호스트별 URL 정리 (혼동 방지)

| 출발 | 목적 | URL |
|------|-----|-----|
| 호스트 브라우저 | GitLab UI | `http://localhost:8090` |
| 호스트 브라우저 | Jenkins UI | `http://localhost:8080` |
| Jenkins 컨테이너 → GitLab | API | `http://gitlab.local` |
| GitLab 컨테이너 → Jenkins | webhook | `http://jenkins-k8s:8080` |

`localhost` 는 항상 "그 컨테이너 자신". 컨테이너 간 통신에선 절대 쓰지 않는다.

---

## 진단 명령 모음

```bash
# 컨테이너 상태 / 네트워크 한눈에
docker ps --format 'table {{.Names}}\t{{.Networks}}\t{{.Ports}}'

# GitLab ↔ Jenkins 양방향 닿는지
docker exec jenkins-k8s curl -sS -o /dev/null -w "%{http_code}\n" http://gitlab.local/api/v4/version
docker exec jenkins-gitlab-mr-gitlab curl -sS -o /dev/null -w "%{http_code}\n" http://jenkins-k8s:8080/login

# Jenkins 가 webhook 을 받았는지
docker logs --tail 200 jenkins-k8s 2>&1 | grep -iE "(WebHook|gitlab)" | tail -20

# GitLab 의 webhook 호출 내역
# UI: 프로젝트 → Settings → Webhooks → 해당 webhook → Edit → Recent events

# users 테이블 (root 안 만들어졌나 확인)
docker exec jenkins-gitlab-mr-gitlab gitlab-psql -d gitlabhq_production \
    -c "SELECT id, username, admin FROM users;"
```

---

## 막혔던 지점 요약 (다음에 또 안 만나기 위해)

| 증상 | 진짜 원인 | 해결 |
|------|---------|-----|
| `root` 로 로그인 안 됨 | `initial_root_password` 가 안 먹음 + users 테이블 0건 | rails runner 로 직접 생성 (Step 2) |
| IntelliJ clone 시 토큰 요구 | IntelliJ 의 GitLab 통합이 끼어듦 | 터미널 `git clone` + IntelliJ 는 폴더 Open (Step 3) |
| Jenkins → GitLab 안 보임 | 다른 docker network | `docker network connect` (Step 4) |
| Test Connection "Login failed" | Credential 종류가 Secret text | Kind = "GitLab API token" 으로 다시 (Step 6-2) |
| Webhook 저장 시 "Url is blocked" | GitLab SSRF 보호 | Admin → Network → Allow local network (Step 8-1) |
| Webhook URL 422 (GitLab 에러 페이지) | URL 호스트가 `gitlab.local` (자기 자신) | `jenkins-k8s:8080` 으로 (Step 8-2) |
| Webhook URL 422 (Jenkins) | secret token 불일치 / Pipeline script 비어있음 | 양쪽 token 동기화 + script 입력 (Step 7) |
| Webhook 200 인데 빌드 안 돔 | Test 버튼은 빌드 안 돌림 | 진짜 MR 만들기 (Step 9) |
| GitLab "Pipeline must succeed" 영원히 spinning | Jenkins 가 success 상태를 안 보냄 | inline script 에 `updateGitlabCommitStatus` 추가 (Step 7) |
| 모든 권한 풀었는데 Merge 버튼 안 보임 | root 가 admin 이지만 프로젝트 멤버가 아님 | 일반 사용자(brunosong) 만들어 Maintainer 로 추가 후 그 계정으로 MR (Step 10) |

---

## 다음 단계 (별도 진행)

여기까지 동작했으면:

1. Pipeline script 를 inline echo 에서 **Pipeline script from SCM** 으로 바꿔서
   `db-migration/jenkins-gitlab-mr/Jenkinsfile` 가 돌게 (실제 Flyway migrate)
2. Allowed branches 를 `master` 로 좁히기
3. **Pipelines must succeed** 옵션으로 머지 게이트 만들기 (`GUIDE.md` Step 4)
4. Jira / Teams 알림 연동 (`GUIDE.md` Step 7)
