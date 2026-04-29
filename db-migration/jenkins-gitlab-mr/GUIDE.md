# GitLab MR(=PR) 트리거 → Jenkins DB 마이그레이션 가이드

> ⚠️ **2026-04 변경**: `docker-compose.yml` 이 `db-migration/infra/` 로 이전됨.
> - GitLab → `db-migration/infra/docker-compose.gitlab.yml`
> - Jenkins / DinD / MySQL → `db-migration/infra/docker-compose.jenkins.yml` (재사용)
>
> 이 가이드의 `cd db-migration/jenkins-gitlab-mr; docker compose up` 명령은
> `docker compose -f db-migration/infra/docker-compose.gitlab.yml up` 으로 대체.
> 자세한 내용은 `db-migration/infra/README.md`.

GitLab 에 Merge Request(=Pull Request) 가 열리거나 업데이트되면 Webhook 으로
Jenkins 가 트리거되어 파이프라인을 실행하고, 결과를 다시 GitLab MR 화면에
✅ / ❌ 로 회신하는 예제.

> GitLab 의 "Merge Request" 는 GitHub 의 "Pull Request" 와 같은 개념이다.
> 이 문서에선 MR / PR 을 같은 의미로 사용한다.

## 전체 아키텍처

```
┌─────────────────────────────────────────────────────────────────────┐
│  docker-compose                                                     │
│                                                                     │
│  ┌──────────────────────┐       ┌──────────────────────────┐        │
│  │ GitLab CE :8090      │       │ Jenkins :8083            │        │
│  │  (gitlab.local)      │       │                          │        │
│  │                      │       │  GitLab Plugin           │        │
│  │  1. 개발자가 MR 생성  │       │                          │        │
│  │     ───────────────▶ │       │                          │        │
│  │                      │  ②    │                          │        │
│  │  2. Webhook 발사     │──────▶│ 3. 파이프라인 실행        │        │
│  │                      │       │   - checkout (MR 브랜치) │        │
│  │  6. MR 화면에 ✅/❌  │◀──────│   - bootJar              │        │
│  │     (Commit Status)  │  ⑤    │   - (MR 검증) 또는       │        │
│  │                      │       │     (push → migrate)     │        │
│  └──────────────────────┘       └────────────┬─────────────┘        │
│                                              │ 4. java -jar         │
│                                              ▼                      │
│                                   ┌──────────────────────┐          │
│                                   │ MySQL :3309           │          │
│                                   │  modules_db           │          │
│                                   │  (Flyway 마이그레이션) │          │
│                                   └──────────────────────┘          │
│                                                                     │
│  같은 docker network: jenkins-gitlab-mr-net                          │
└─────────────────────────────────────────────────────────────────────┘
```

**핵심**: GitLab 컨테이너의 hostname 을 `gitlab.local` 로 띄우고, 같은
docker network 안에서 Jenkins 가 `http://gitlab.local` 로 접근한다.
Webhook URL 도 마찬가지로 `http://jenkins-gitlab-mr-jenkins:8080/...` 또는
브라우저에서 등록할 땐 `http://jenkins:8080/...` 로 잡으면 된다.

---

## 파일 구성

```
jenkins-gitlab-mr/
├── docker-compose.yml      # GitLab CE + Jenkins + MySQL
├── Jenkinsfile             # MR/Push 트리거 파이프라인
├── build.gradle
├── GUIDE.md                # ← 이 파일
└── src/
    └── main/
        ├── java/...        # Spring Boot + Flyway 앱
        └── resources/
            ├── application.yml
            └── db/migration/
                └── V1__create_sample_table.sql
```

---

## Step 1: docker-compose 실행

```bash
cd db-migration/jenkins-gitlab-mr
docker compose up -d
```

> ⚠️ GitLab CE 는 첫 부팅에 **5~10분** 걸린다. 메모리 4GB 이상 권장.
> `docker compose logs -f gitlab` 으로 `gitlab Reconfigured!` 메시지가 뜰 때까지 기다린다.

| 서비스 | 호스트 포트 | 접속 |
|--------|------------|------|
| GitLab | 8090 | http://localhost:8090 |
| Jenkins | 8083 | http://localhost:8083 |
| MySQL | 3309 | `mysql -h 127.0.0.1 -P 3309 -umigration -pmigration1234` |

### 호스트 hosts 파일에 gitlab.local 등록 (선택)

브라우저에서 GitLab 이 만드는 clone URL (`http://gitlab.local/...`) 을 그대로
쓰려면 호스트 OS 의 hosts 파일에 추가한다.

- Windows: `C:\Windows\System32\drivers\etc\hosts`
- Linux/macOS: `/etc/hosts`

```
127.0.0.1   gitlab.local
```

> 등록하지 않아도 데모는 동작한다. 그 경우 브라우저는 `http://localhost:8090`
> 으로 접속하고, Jenkins ↔ GitLab 간 통신은 docker network 안의
> `gitlab.local` 로 알아서 잡힌다.

---

## Step 2: GitLab 초기 로그인 + 프로젝트 생성

1. http://localhost:8090 접속
2. ID `root` / PW `gitlabroot1234!` (docker-compose 의 `initial_root_password`)
3. **New project → Create blank project**
   - Project name: `db-migration-demo`
   - Visibility: Internal (또는 Private)
4. 로컬에서 push:

```bash
# 프로젝트 루트(modules)에서
git remote add gitlab http://root:gitlabroot1234!@localhost:8090/root/db-migration-demo.git
git push gitlab master
```

> 운영에선 personal access token 을 따로 만들어서 git remote URL 에 넣는다.
> 데모라 root 비번을 그대로 사용했다.

### GitLab Personal Access Token 생성 (Jenkins 에서 사용)

1. GitLab → 우상단 아바타 → **Edit profile → Access Tokens**
2. Name: `jenkins`, Scopes: `api`, `read_repository`
3. **Create personal access token** → 토큰 값 복사 (한 번만 보임!)

---

## Step 3: Jenkins 초기 설정

```bash
# 초기 비밀번호
docker exec jenkins-gitlab-mr-jenkins \
    cat /var/jenkins_home/secrets/initialAdminPassword
```

1. http://localhost:8083 접속 → 비밀번호 입력
2. **Install suggested plugins** 선택
3. 관리자 계정 생성

### GitLab Plugin 설치

**Manage Jenkins → Plugins → Available plugins** 에서
**GitLab** 을 검색해서 설치 후 재시작.

### GitLab Connection 등록

**Manage Jenkins → System → GitLab**

| 항목 | 값 |
|------|-----|
| Connection name | `gitlab-local` *(Jenkinsfile 의 `gitLabConnection` 값과 동일해야 함)* |
| GitLab host URL | `http://gitlab.local` |
| Credentials | (아래 Credentials 섹션에서 만든 GitLab API token) |

**Test Connection** 으로 ✅ 확인.

### Credentials 등록

**Manage Jenkins → Credentials → System → Global → Add Credentials**

| ID | 종류 | 값 |
|----|------|-----|
| `gitlab-api-token` | **GitLab API token** *(GitLab Plugin 이 추가하는 종류)* | Step 2 에서 만든 토큰 |
| `db-url` | Secret text | `jdbc:mysql://mysql:3306/modules_db?useSSL=false&allowPublicKeyRetrieval=true` |
| `db-username` | Secret text | `migration` |
| `db-password` | Secret text | `migration1234` |

> `db-url` 의 호스트가 `mysql` 인 이유 — Jenkins / MySQL 이 같은 docker network
> 안이라 컨테이너 서비스명으로 접근한다.

---

## Step 4: Jenkins Pipeline Job 생성

1. **새로운 Item** → 이름: `db-migration-gitlab-mr` → **Pipeline** 선택
2. **Build Triggers**: `Build when a change is pushed to GitLab` 체크
   - 표시되는 webhook URL 을 메모해둔다 — 보통 `http://<JENKINS_URL>/project/db-migration-gitlab-mr` 형태.
   - **Advanced → Generate** 로 secret token 생성 → 메모.
3. **Pipeline**:
   - Definition: **Pipeline script from SCM**
   - SCM: **Git**
   - Repository URL: `http://gitlab.local/root/db-migration-demo.git`
   - Credentials: GitLab username/password (root / gitlabroot1234!) 또는 token
   - Script Path: `db-migration/jenkins-gitlab-mr/Jenkinsfile`
4. 저장 → 한 번 **Build Now** 로 수동 빌드해서 SCM 연결 확인.

---

## Step 5: GitLab Webhook 등록

1. GitLab 프로젝트 → **Settings → Webhooks**
2. URL: `http://jenkins-gitlab-mr-jenkins:8080/project/db-migration-gitlab-mr`
   - GitLab 컨테이너에서 Jenkins 컨테이너로 가는 호스트명 사용.
   - 호스트 브라우저에서 보이는 `localhost:8083` 이 아님!
3. Secret Token: Step 4 에서 메모한 값
4. Trigger 체크:
   - ✅ Push events
   - ✅ Merge request events
   - (선택) ✅ Comments
5. **Add webhook**
6. **Test → Push events / Merge request events** 클릭해서 응답 200 확인.

> SSL self-signed 경고가 나오면 webhook 설정의
> "Enable SSL verification" 체크를 해제 (데모 한정).

---

## Step 6: 동작 확인

### A. 일반 push 트리거

```bash
git commit --allow-empty -m "trigger jenkins"
git push gitlab master
```

→ Jenkins 에 새 빌드가 자동 시작되고, `migrate` 단계에서 실제로
Flyway 가 MySQL 에 V1 마이그레이션을 적용한다.

```bash
docker exec -it jenkins-gitlab-mr-mysql \
    mysql -umigration -pmigration1234 modules_db -e "SHOW TABLES;"
# → flyway_schema_history_jenkins_gitlab_mr, sample_jenkins_gitlab_mr
```

### B. MR 트리거

1. GitLab 에서 새 브랜치 생성 → SQL 한 줄 추가 (예: V2 파일)
2. **Create merge request**
3. Jenkins 에 빌드가 자동 시작 (`gitlabActionType=MERGE`)
4. MR 화면 하단 → "Pipelines / Commits" 탭에 Jenkins build / migrate 상태가
   ⏳ → ✅ 로 갱신된다.
5. MR 단계에서는 **DB 변경 없이 빌드만** 수행한다 (Jenkinsfile 의 `IS_MR` 분기 참고).

### C. 머지

MR 을 머지하면 target branch (master) 에 push 가 일어나 다시 파이프라인이
돌고, 이번엔 `IS_MR=false` 라 실제 `migrate` 가 수행된다.

---

## Step 7: Jira 완료 + Teams 알림 연동

머지 후 마이그레이션이 성공하면 ① 관련 Jira 티켓을 **Done** 으로 transition 하고,
② Teams 채널에 결과 카드를 보낸다. 실패 시엔 Teams 알림만(에러 노출).

### 7-1. 추적 컨벤션

브랜치명 또는 MR 제목에 Jira 키를 포함시킨다. Jenkinsfile 의 `extractJiraKey()`
는 정규식 `[A-Z][A-Z0-9]+-\d+` 로 첫 번째 매치를 가져온다.

```
feature/PROJ-123-add-user-table
bugfix/INFRA-42-fix-flyway-history
```

이 컨벤션을 강제하려면 GitLab 의 **Push rules → Branch name regex** 또는 MR
template 에 안내 문구를 추가한다.

### 7-2. Jira 설정

#### Jira Cloud (atlassian.net)

1. https://id.atlassian.com/manage-profile/security/api-tokens → **Create API token**
2. 토큰 값 + 본인 이메일을 메모. Jenkins 에 넣을 값:
   ```
   your.email@company.com:<API_TOKEN>
   ```

#### Jira Server / DC

자체 호스팅이면 username:password 또는 PAT 사용. 위와 같은 형식.

#### Done transition ID 조회

Jira 워크플로우마다 transition ID 가 다르므로 한 번만 조회해서 Jenkinsfile
의 `JIRA_DONE_TRANSITION_ID` 에 박는다.

```bash
curl -sS -u 'you@company.com:<TOKEN>' \
    https://your-org.atlassian.net/rest/api/3/issue/PROJ-1/transitions \
    | jq '.transitions[] | {id, name}'
```

출력 예:
```
{ "id": "11", "name": "To Do" }
{ "id": "21", "name": "In Progress" }
{ "id": "31", "name": "Done" }
```

→ Jenkinsfile 의 `JIRA_DONE_TRANSITION_ID = '31'` 수정.

#### Jenkins Credentials

| ID | 종류 | 값 |
|----|------|-----|
| `jira-basic-auth` | Secret text | `you@company.com:<API_TOKEN>` |

> Username/Password Credential 종류로 만들면 `${JIRA_AUTH_USR}:${JIRA_AUTH_PSW}`
> 식으로 분리해서 써야 해서 코드가 길어진다. Secret text 한 줄로 박는 게 단순.

### 7-3. Teams 설정 (Power Automate Workflows 권장)

> Microsoft 가 기존 **Office 365 Connectors** (`*.webhook.office.com`) 를
> 2025 년부터 단계적으로 종료 중이다. 신규 채널은 **Power Automate Workflows**
> 의 "Post to a channel when a webhook request is received" 템플릿을 사용한다.

1. Teams 채널 → **⋯ → Workflows → "Post to a channel when a webhook request is received"**
2. 채널 / 게시자(보통 Flow bot) 선택 → **Add workflow**
3. 생성되면 webhook URL 이 표시됨 — 복사해서 메모.

#### Jenkins Credentials

| ID | 종류 | 값 |
|----|------|-----|
| `teams-webhook-url` | Secret text | (위에서 복사한 webhook URL) |

> 기존 **Office 365 Connector** (deprecated) 를 이미 쓰고 있다면 위 코드의
> MessageCard payload 가 그대로 동작한다. 신규 Workflows 도 MessageCard /
> Adaptive Card 둘 다 받지만, Adaptive Card 가 정식 권장 포맷이다. 데모는
> 호환성 우선으로 MessageCard 로 작성됨.

### 7-4. Jenkinsfile 흐름

```
post {
  success {
    if (push 트리거) {       // MR 검증 단계엔 알림 안 보냄
      Jira: 티켓 → Done
      Teams: 초록 카드
    }
  }
  failure {
    Teams: 빨간 카드 (MR/push 모두)
  }
}
```

추출/호출 로직은 Jenkinsfile 하단 helper 함수 (`extractJiraKey`,
`transitionJira`, `notifyTeams`) 를 그대로 가져다 쓰면 된다.

### 7-5. 권장 운영 팁

| 주제 | 권장 |
|------|-----|
| Jira 키 누락 | 브랜치명 컨벤션을 GitLab Push Rule 로 강제 + MR template 에 안내 |
| 알림 소음 | MR 검증 성공은 알림 X (코드처럼). 실패만 알림 |
| 동일 티켓에 코멘트도 남기기 | `POST /rest/api/3/issue/{key}/comment` 추가 호출 |
| 여러 티켓이 한 MR 에 | `extractJiraKey()` 를 모든 매치를 반환하게 바꾸고 loop |
| 운영 PROD | Jira transition 은 PROD 배포 성공 시점에만. 스테이징은 코멘트만 추천 |
| Adaptive Card | 신규 채널은 Adaptive Card payload 권장 — 액션 버튼(빌드 보기 등) 추가 가능 |
| 시크릿 회전 | Jira API 토큰 / Teams URL 은 90일 주기 회전. Jenkins Credentials 로만 보관 |

---

## 운영 환경 적용 시 변경사항

| 항목 | 로컬 | 운영 |
|------|------|------|
| GitLab 호스트 | `gitlab.local` (docker network) | 사내 GitLab 도메인 |
| Webhook URL | `http://jenkins-gitlab-mr-jenkins:8080/...` | Jenkins 외부 접근 가능 URL |
| Secret Token | 데모 token | 사내 정책에 맞는 강한 token, Vault 관리 |
| DB 접속 | docker network 의 `mysql` | 운영 DB 엔드포인트 + 보안그룹 / VPN |
| MR 검증 | 빌드 성공으로 갈음 | shadow DB 띄워 실제 dry-run migrate 권장 |

> **MR 단계에서 진짜 dry-run 을 하고 싶다면**
> Jenkinsfile 의 MR 분기에서 임시 schema (예: `modules_db_mr_${MR_IID}`) 를
> 만들어 거기에 migrate 한 뒤 drop 하는 식으로 확장하면 된다.
> 데모에선 단순화를 위해 빌드 성공으로 검증을 갈음했다.

---

## 자주 쓰는 명령어

```bash
# ─── docker-compose ───
docker compose up -d
docker compose down
docker compose down -v                  # 데이터 포함 전부 삭제
docker compose logs -f gitlab           # GitLab 부팅 로그
docker compose logs -f jenkins

# ─── GitLab 초기 비번 재설정 (initial_root_password 가 안 먹었을 때) ───
docker exec -it jenkins-gitlab-mr-gitlab gitlab-rake "gitlab:password:reset[root]"

# ─── Jenkins 초기 비번 ───
docker exec jenkins-gitlab-mr-jenkins \
    cat /var/jenkins_home/secrets/initialAdminPassword

# ─── MySQL 접속 ───
docker exec -it jenkins-gitlab-mr-mysql \
    mysql -umigration -pmigration1234 modules_db

# ─── 로컬 마이그레이션 (Jenkins 없이 단독 실행) ───
./gradlew :db-migration:jenkins-gitlab-mr:bootRun
```

---

## 정리 (Cleanup)

```bash
docker compose down -v
```

GitLab 데이터(`gitlab-config`, `gitlab-logs`, `gitlab-data`) 는 수 GB 단위로
커지니 데모가 끝나면 꼭 `-v` 로 같이 지우자.

---

## 트러블슈팅

### root 로그인이 안 됨 / "Invalid login or password"

`initial_root_password` 는 **최초 부팅** 시점에만 적용된다. 이미 부팅이
끝난 컨테이너의 환경변수를 바꿔도 안 먹는다. 또 GitLab 이 자동 생성한
임시 비번이 우선하는 경우도 있다.

```bash
# 1) 자동 생성된 임시 비번 확인 (있으면 이게 우선)
docker exec jenkins-gitlab-mr-gitlab cat /etc/gitlab/initial_root_password

# 2) rake 로 강제 재설정
docker exec -it jenkins-gitlab-mr-gitlab \
    gitlab-rake "gitlab:password:reset[root]"

# 3) 깨끗이 다시 — 데이터 전부 날아감 (데모니까 OK)
docker compose down -v && docker compose up -d
```

> `/etc/gitlab/initial_root_password` 파일은 첫 로그인 후 24시간이 지나면
> 자동 삭제된다. 그 전에 비번을 바꿔두는 게 좋다.

#### users 테이블이 비어 있는 경우 (seed 누락)

부팅이 끝났는데도 `User.all` 이 빈 배열이고 PostgreSQL `users` 가 0건이면
초기 seed 가 어떤 이유로 스킵된 것이다. 직접 root 를 만든다.

```bash
# 현재 상태 확인
docker exec jenkins-gitlab-mr-gitlab gitlab-psql -d gitlabhq_production \
    -c "SELECT id, username, admin FROM users;"
```

`(0 rows)` 면 아래 스크립트를 실행:

```bash
# 컨테이너 안에 스크립트 작성 후 실행
docker exec -i jenkins-gitlab-mr-gitlab bash -c 'cat > /tmp/create-root.rb' <<'RUBY'
password = 'Xq7$kP9wL2vN4mR8'   # 충분히 강한 비번 — GitLab 이 weak 패스워드를 거부함
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

# Git Bash 사용 시 경로 변환 끄기 (MSYS_NO_PATHCONV=1). PowerShell 은 그냥 실행.
MSYS_NO_PATHCONV=1 docker exec jenkins-gitlab-mr-gitlab \
    gitlab-rails runner /tmp/create-root.rb
```

> 비번은 `Xq7$kP9wL2vN4mR8` 같은 영/숫/특/대소문자 섞인 강한 값을 사용해야 한다.
> `gitlabroot1234!` 류는 GitLab 의 "commonly used combinations" 검사에서 거부된다.

### GitLab 이 안 뜸 / 502

GitLab 첫 부팅은 5~10분 걸린다. 메모리 부족이 가장 흔한 원인.

```bash
docker stats jenkins-gitlab-mr-gitlab     # MEM USAGE 확인
docker compose logs -f gitlab             # "gitlab Reconfigured!" 까지 대기
```

### Webhook 등록 시 "Url is blocked" 에러

GitLab 14+ 는 기본적으로 사설 IP(localhost, 172.x.x.x) 로의 webhook 을 막는다.
Admin 으로 로그인 → **Admin Area → Settings → Network → Outbound requests** →
"Allow requests to the local network from webhooks and integrations" 체크.

### MR 화면에 Jenkins 상태가 안 뜸

- Jenkins → System → GitLab connection 의 **Test Connection** 통과 여부
- Credentials `gitlab-api-token` 이 GitLab Plugin 이 추가한
  "GitLab API token" 종류로 등록됐는지 (일반 Secret text 와 다름)
- Jenkinsfile 의 `gitLabConnection('gitlab-local')` 값과 System 설정의
  Connection name 일치 여부

### Flyway 가 "이미 적용됨" 으로 나오는 경우

```bash
docker exec -it jenkins-gitlab-mr-mysql \
    mysql -umigration -pmigration1234 modules_db \
    -e "SELECT * FROM flyway_schema_history_jenkins_gitlab_mr;"

# 데이터를 깨끗이 밀고 싶으면
docker compose down -v && docker compose up -d
```
