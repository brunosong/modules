-- ============================================================
-- Domain : agent
-- Author : brunosong
-- Description : AI 에이전트 설정 테이블 생성
-- ============================================================

CREATE TABLE IF NOT EXISTS agent_config (
    id              BIGINT          NOT NULL AUTO_INCREMENT,
    name            VARCHAR(200)    NOT NULL,
    model_type      VARCHAR(50)     NOT NULL,
    system_prompt   TEXT            NULL,
    owner_id        BIGINT          NOT NULL,
    created_at      DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_agent_config_owner_id (owner_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
