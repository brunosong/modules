package com.brunosong.system.agent.adapter.out.llm;

import com.brunosong.system.agent.application.port.out.LlmPort;
import com.brunosong.system.agent.domain.AgentAction;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.ollama.api.OllamaApi;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Spring AI ChatClient를 이용한 LLM 어댑터.
 * Tool Calling을 통해 PdfReaderTool 등을 자동으로 실행한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SpringAiLlmAdapter implements LlmPort {

    private final ChatClient chatClient;
    private final OllamaApi ollamaApi;

    @Override
    public AgentAction ask(String userMessage, String model) {
        try {
            String response = chatClient.prompt()
                    .user(userMessage)
                    .call()
                    .content();
            return AgentAction.replyOnly(response);
        } catch (Exception e) {
            log.error("LLM 호출 실패", e);
            return AgentAction.replyOnly("LLM 호출 실패: " + e.getMessage());
        }
    }

    @Override
    public List<String> listModels() {
        try {
            return ollamaApi.listModels()
                    .models()
                    .stream()
                    .map(OllamaApi.Model::name)
                    .toList();
        } catch (Exception e) {
            log.error("모델 목록 조회 실패", e);
            return List.of();
        }
    }
}
