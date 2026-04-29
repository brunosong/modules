# Jenkins 직접 실행 DB 마이그레이션 가이드

> ⚠️ **2026-04 변경**: 도커 파일이 모두 `db-migration/infra/` 로 통합됨.
> 이 가이드의 `cd db-migration/jenkins-direct; docker compose up` 명령은
> `docker compose -f db-migration/infra/docker-compose.jenkins.yml up`
> (Jenkins/DinD/MySQL 통합본 사용) 로 대체. 자세한 내용은 `db-migration/infra/README.md` 참고.

Jenkins 에이전트에서 Spring Boot + Flyway 앱을 직접 실행하여 DB 마이그레이션을 수행하는 방법.
가장 단순한 방식으로, Jenkins가 DB에 직접 접근한다.

## 전체 아키텍처

```
┌────────────────────────────────────────────────────────┐
│  docker-compose                                        │
│                                                        │
│  ┌────────────────┐         ┌────────────────────────┐ │
│  │ Jenkins :8081  │         │ MySQL :3307            │ │
│  │                │         │ (외부 DB 역할)          │ │
│  │ 1. gradlew     │         │                        │ │
│  │    bootJar     │         │                        │ │
│  │ 2. java -jar   │────────▶│ modules_db             │ │
│  │    (Flyway)    │  직접    │                        │ │
│  │                │  접근    │                        │ │
│  └────────────────┘         └────────────────────────┘ │
│                                                        │
│  같은 docker network (jenkins-direct-net)               │
└────────────────────────────────────────────────────────┘
```

**특징**: K8s 없이 Jenkins가 DB에 직접 접근하여 마이그레이션을 실행한다.

---

## 파일 구성

```
jenkins-direct/
├── docker-compose.yml            # Jenkins + MySQL
├── Jenkinsfile                   # Jenkins 파이프라인
├── build.gradle
└── src/
    └── main/
        ├── java/...              # Spring Boot + Flyway 앱
        └── resources/
            ├── application.yml
            └── db/migration/     # SQL 마이그레이션 파일
                └── V1__create_sample_table.sql
```

---

## Step 1: docker-compose 실행

```bash
cd db-migration/jenkins-direct

# Jenkins + MySQL 실행
docker compose up -d

# MySQL이 Ready 될 때까지 대기 (약 10~20초)
docker compose logs -f mysql
# "ready for connections" 로그가 나오면 Ctrl+C
```

| 서비스 | 포트 | 접속 |
|--------|------|------|
| Jenkins | 8081 | http://localhost:8081 |
| MySQL | 3307 | `mysql -h localhost -P 3307 -umigration -pmigration1234` |

---

## Step 2: 로컬에서 직접 마이그레이션 테스트

Jenkins 없이 로컬에서 바로 실행해볼 수 있다.

```bash
# 프로젝트 루트로 이동
cd ../../

# bootRun으로 마이그레이션 실행
./gradlew :db-migration:jenkins-direct:bootRun

# 또는 bootJar 후 직접 실행
./gradlew :db-migration:jenkins-direct:bootJar
java -jar db-migration/jenkins-direct/build/libs/jenkins-direct-1.0-SNAPSHOT.jar
```

### 결과 확인

```bash
docker exec -it jenkins-direct-mysql mysql -umigration -pmigration1234 modules_db

SHOW TABLES;
SELECT * FROM flyway_schema_history;
DESC sample_jenkins_direct;
```

---

## Step 3: Jenkins 초기 설정

```bash
# Jenkins 초기 비밀번호 확인
docker exec jenkins-direct cat /var/jenkins_home/secrets/initialAdminPassword
```

1. `http://localhost:8081` 접속 → 비밀번호 입력
2. **Install suggested plugins** 선택
3. 관리자 계정 생성

---

## Step 4: Jenkins Credentials 등록

Jenkins가 DB에 접근할 수 있도록 Credentials를 등록한다.

**Jenkins 관리 → Credentials → Global → Add Credentials**

| ID | 종류 | 값 |
|----|------|-----|
| `db-url` | Secret text | `jdbc:mysql://mysql:3306/modules_db?useSSL=false&allowPublicKeyRetrieval=true` |
| `db-username` | Secret text | `migration` |
| `db-password` | Secret text | `migration1234` |

> **주의**: `db-url`의 호스트가 `mysql`인 이유 → Jenkins와 MySQL이 같은 docker network에 있어서
> docker-compose의 서비스 이름으로 접근한다. `localhost`가 아님!

---

## Step 5: Jenkins Pipeline Job 생성

1. **새로운 Item** → 이름 입력 → **Pipeline** 선택
2. Pipeline 설정:
   - **Definition**: Pipeline script from SCM
   - **SCM**: Git
   - **Repository URL**: 프로젝트 Git URL
   - **Script Path**: `db-migration/jenkins-direct/Jenkinsfile`
3. **저장** → **Build Now**

### Jenkins 컨테이너에 Java 21 확인

이 이미지(`jenkins/jenkins:lts-jdk21`)에는 Java 21이 이미 포함되어 있다.

```bash
docker exec jenkins-direct java --version
```

### Jenkins 컨테이너에서 Gradle 빌드를 위한 추가 설정

Jenkinsfile에서 `../../gradlew bootJar`를 실행하므로, SCM checkout 경로에
프로젝트 루트가 포함되어야 한다.

---

## 배포 방식 정리

| 환경 | 배포 방식 |
|------|----------|
| 개발 / 스테이징 | PR 승인 후 Jenkins Webhook → 파이프라인 자동 실행 |
| 운영 | PR 승인 후 관리자가 Jenkins Job **수동 실행** (Build with Parameters) |

---

## Jenkinsfile 흐름 설명

```
Checkout → Build (gradlew bootJar) → Run Migration (java -jar)
```

1. **Checkout**: Git에서 소스 코드 체크아웃
2. **Build Migration App**: `gradlew bootJar`로 Spring Boot JAR 빌드
3. **Run Migration**: JAR를 직접 실행하여 Flyway 마이그레이션 수행
   - DB 접속 정보는 Jenkins Credentials에서 주입

---

## 운영 환경 적용 시 변경사항

로컬에서 테스트한 뒤 운영에 적용할 때 변경할 부분:

| 항목 | 로컬 | 운영 |
|------|------|------|
| Jenkins Credential `db-url` | `jdbc:mysql://mysql:3306/modules_db` | `jdbc:mysql://xxx.cdb.ntruss.com:3306/modules_db` |
| Jenkins Credential `db-username` | `migration` | 운영 DB 계정 |
| Jenkins Credential `db-password` | `migration1234` | 운영 DB 비밀번호 |
| Jenkins 네트워크 | docker network | Jenkins 에이전트 → DB 네트워크 경로 확보 필요 |

> **보안 주의**: 이 방식은 Jenkins가 DB에 직접 접근하므로, Jenkins 에이전트에서 DB까지의
> 네트워크 경로와 접속 정보가 Jenkins에 저장된다.

---

## 자주 쓰는 명령어

```bash
# ─── docker-compose ───
docker compose up -d                  # 전체 시작
docker compose down                   # 전체 중지
docker compose down -v                # 전체 중지 + 데이터 삭제
docker compose logs -f jenkins        # Jenkins 로그

# ─── MySQL 접속 ───
docker exec -it jenkins-direct-mysql mysql -umigration -pmigration1234 modules_db

# ─── 로컬 마이그레이션 ───
./gradlew :db-migration:jenkins-direct:bootRun
./gradlew :db-migration:jenkins-direct:bootJar
```

---

## 정리 (Cleanup)

```bash
docker compose down -v
```

---

## 트러블슈팅

### Jenkins에서 DB 연결 실패

```bash
# 1. MySQL 컨테이너가 실행 중인지 확인
docker ps | grep jenkins-direct-mysql

# 2. Credentials의 db-url 호스트가 'mysql'인지 확인 (localhost 아님!)
#    Jenkins 컨테이너와 MySQL 컨테이너는 같은 docker network에 있으므로
#    서비스 이름 'mysql'로 접근해야 한다

# 3. docker network 확인
docker network inspect jenkins-direct_jenkins-direct-net
```

### Flyway 마이그레이션이 이미 적용되어 있다고 나오는 경우

```bash
# flyway_schema_history 테이블 확인
docker exec -it jenkins-direct-mysql mysql -umigration -pmigration1234 modules_db \
    -e "SELECT * FROM flyway_schema_history;"

# 데이터 초기화가 필요하면 볼륨 삭제 후 재시작
docker compose down -v && docker compose up -d
```
