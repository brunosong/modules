package com.brunosong.system.agent.application.service;

import com.brunosong.system.agent.application.port.in.ChatWithAgentUseCase;
import com.brunosong.system.agent.application.port.out.FileWriterPort;
import com.brunosong.system.agent.application.port.out.LlmPort;
import com.brunosong.system.agent.domain.AgentAction;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 1단계 에이전트 흐름:
 *   1) 사용자 입력을 LLM 에 전달 (모델 선택 가능)
 *   2) LLM 이 "파일을 만들어라"고 응답하면 파일 어댑터로 실제 저장
 *   3) 결과(저장 경로 포함)를 그대로 반환
 */
@Service
@RequiredArgsConstructor
public class AgentService implements ChatWithAgentUseCase {

    private final LlmPort llmPort;
    private final FileWriterPort fileWriterPort;

    @Override
    public AgentAction handle(String userMessage, String model) {
        AgentAction action = llmPort.ask(userMessage, model);
        if (action.hasFile()) {
            String savedPath = fileWriterPort.write(action.path(), action.content());
            return new AgentAction(savedPath, action.content(),
                    action.reply() + "\n[saved] " + savedPath);
        }
        return action;
    }

    @Override
    public List<String> availableModels() {
        return llmPort.listModels();
    }
}
