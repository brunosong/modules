-- 의도적으로 깨지는 스크립트 — PROBLEMS.md 의 "실패 알림 스팸" 시나리오용.
-- 사용 시점에는 file 이름을 V2__broken.sql 로 두고 V3 보다 먼저 실행되게 한다.
ALTER TABLE orders ADD COLUMN amount DECIMAL(12,2) NOT NULL;  -- 중복 컬럼 → 실패