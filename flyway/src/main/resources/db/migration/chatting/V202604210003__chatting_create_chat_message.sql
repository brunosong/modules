-- ============================================================
-- Domain : chatting
-- Author : brunosong
-- Description : 채팅 메시지 테이블 생성
-- ============================================================

CREATE TABLE IF NOT EXISTS chat_message (
    id          BIGINT          NOT NULL AUTO_INCREMENT,
    room_id     BIGINT          NOT NULL,
    sender_id   BIGINT          NOT NULL,
    content     TEXT            NOT NULL,
    created_at  DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_chat_message_room_id (room_id),
    KEY idx_chat_message_sender_id (sender_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
