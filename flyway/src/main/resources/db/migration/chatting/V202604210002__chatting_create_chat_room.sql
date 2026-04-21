-- ============================================================
-- Domain : chatting
-- Author : brunosong
-- Description : 채팅방 테이블 생성
-- ============================================================

CREATE TABLE IF NOT EXISTS chat_room (
    id          BIGINT          NOT NULL AUTO_INCREMENT,
    name        VARCHAR(200)    NOT NULL,
    created_by  BIGINT          NOT NULL,
    created_at  DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_chat_room_created_by (created_by)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
