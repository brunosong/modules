package com.brunosong.system.agent.application.port.in;

import com.brunosong.system.agent.domain.AgentAction;

import java.util.List;

/** 사용자의 한 줄 입력 → 에이전트가 수행한 행동 결과(파일 생성 + 응답 메시지). */
public interface ChatWithAgentUseCase {
    AgentAction handle(String userMessage, String model);
    List<String> availableModels();
}
