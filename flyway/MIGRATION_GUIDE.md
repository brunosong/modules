# Flyway 마이그레이션 가이드

## 개요

여러 서비스(chatting, agent 등)가 **하나의 DB**를 공유하는 환경에서,
이 `flyway` 모듈이 스키마 변경의 **Single Source of Truth** 역할을 합니다.

어떤 서비스에서든 DB 변경이 필요하면 **반드시 이 모듈에 SQL 파일을 추가**하고 PR을 올려야 합니다.

## 디렉토리 구조

```
src/main/resources/db/migration/
├── common/         ← 공통 테이블 (users, audit_log 등)
├── chatting/       ← chatting 서비스 소유 테이블
├── agent/          ← agent 서비스 소유 테이블
└── {new-domain}/   ← 새 서비스 추가 시 디렉토리 생성
```

## 네이밍 규칙

```
V{YYYYMMDDHHmm}__{domain}_{description}.sql
```

| 항목 | 설명 | 예시 |
|------|------|------|
| Version | 타임스탬프 (연월일시분) | `202604210001` |
| Domain | 소유 서비스명 | `chatting`, `agent`, `common` |
| Description | 변경 내용 (snake_case) | `create_chat_room`, `add_index_sender_id` |

**예시**: `V202604210002__chatting_create_chat_room.sql`

## SQL 파일 헤더 (권장)

```sql
-- ============================================================
-- Domain : chatting
-- Author : brunosong
-- Description : 채팅방 테이블 생성
-- ============================================================
```

## 새 도메인 추가 시

1. `src/main/resources/db/migration/{domain}/` 디렉토리 생성
2. `application.yml`의 `spring.flyway.locations`에 경로 추가
3. 첫 마이그레이션 SQL 작성

## 실행 방법

```bash
# 마이그레이션 실행 (앱 기동 시 자동)
./gradlew :flyway:bootRun

# 빌드만
./gradlew :flyway:build
```

## PR 프로세스

1. `flyway` 모듈에 SQL 파일 추가
2. PR 생성 — 제목에 `[DB Migration]` 접두사 권장
3. 리뷰어: 해당 도메인 담당자 + DBA (있다면)
4. merge 후 배포 파이프라인에서 flyway 자동 실행

## 주의사항

- **이미 적용된 마이그레이션 파일은 절대 수정하지 마세요** (checksum 불일치 에러 발생)
- 수정이 필요하면 새로운 V 파일로 ALTER TABLE 작성
- 롤백이 필요한 경우 별도의 마이그레이션으로 처리
- 다른 도메인 테이블을 변경해야 하면 해당 도메인 담당자와 협의 후 진행
