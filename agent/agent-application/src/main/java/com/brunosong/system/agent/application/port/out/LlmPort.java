package com.brunosong.system.agent.application.port.out;

import com.brunosong.system.agent.domain.AgentAction;

import java.util.List;

/** LLM 호출 추상화. 구현체는 Ollama 등으로 갈아끼울 수 있다. */
public interface LlmPort {
    AgentAction ask(String userMessage, String model);
    List<String> listModels();
}
