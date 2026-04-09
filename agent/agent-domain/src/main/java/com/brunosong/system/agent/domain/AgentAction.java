package com.brunosong.system.agent.domain;

/**
 * LLM이 결정한 "할 일" 한 건. 1단계에서는 파일 생성만 다룬다.
 *  - path:    저장할 상대/절대 경로
 *  - content: 파일에 쓸 내용
 *  - reply:   사용자에게 보여줄 LLM의 자연어 응답(설명)
 */
public record AgentAction(String path, String content, String reply) {

    public static AgentAction replyOnly(String reply) {
        return new AgentAction(null, null, reply);
    }

    public boolean hasFile() {
        return path != null && !path.isBlank();
    }
}
