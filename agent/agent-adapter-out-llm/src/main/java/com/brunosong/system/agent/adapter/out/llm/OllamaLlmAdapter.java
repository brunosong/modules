package com.brunosong.system.agent.adapter.out.llm;

import com.brunosong.system.agent.application.port.out.LlmPort;
import com.brunosong.system.agent.domain.AgentAction;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Ollama 어댑터.
 *  - /api/tags     : 로컬에 설치된 모델 목록
 *  - /api/generate : 프롬프트 실행 (format=json 으로 강제)
 */
@Slf4j
@Component
public class OllamaLlmAdapter implements LlmPort {

    private static final String SYSTEM_PROMPT = """
            You are a local file-creating agent.
            The user will describe a file they want to create.
            You MUST respond with ONLY a JSON object of the form:
              {"path": "<relative file path>", "content": "<file body>", "reply": "<short message in Korean>"}
            Rules:
              - Never include markdown fences.
              - If the user does not want a file, set path and content to empty string "".
              - Keep paths relative (e.g. "notes/hello.txt"). No absolute paths, no "..".
            """;

    private final RestClient http;
    private final ObjectMapper om = new ObjectMapper();
    private final String defaultModel;

    public OllamaLlmAdapter(@Value("${agent.ollama.base-url:http://localhost:11434}") String baseUrl,
                            @Value("${agent.ollama.model:llama3.1}") String defaultModel) {
        this.http = RestClient.builder().baseUrl(baseUrl).build();
        this.defaultModel = defaultModel;
    }

    @Override
    public AgentAction ask(String userMessage, String model) {
        String useModel = (model == null || model.isBlank()) ? defaultModel : model;
        Map<String, Object> body = Map.of(
                "model", useModel,
                "system", SYSTEM_PROMPT,
                "prompt", userMessage,
                "format", "json",
                "stream", false
        );

        try {
            String raw = http.post()
                    .uri("/api/generate")
                    .body(body)
                    .retrieve()
                    .body(String.class);

            JsonNode root = om.readTree(raw);
            String responseJson = root.path("response").asText();
            JsonNode parsed = om.readTree(responseJson);

            String path    = parsed.path("path").asText("");
            String content = parsed.path("content").asText("");
            String reply   = parsed.path("reply").asText("");
            return new AgentAction(path, content, reply);
        } catch (Exception e) {
            log.error("Ollama call failed", e);
            return AgentAction.replyOnly("LLM 호출 실패: " + e.getMessage());
        }
    }

    @Override
    public List<String> listModels() {
        try {
            String raw = http.get().uri("/api/tags").retrieve().body(String.class);
            JsonNode arr = om.readTree(raw).path("models");
            List<String> names = new ArrayList<>();
            arr.forEach(n -> names.add(n.path("name").asText()));
            return names;
        } catch (Exception e) {
            log.error("Ollama listModels failed", e);
            return List.of();
        }
    }
}
