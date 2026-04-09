package com.brunosong.system.agent.adapter.in.web;

import com.brunosong.system.agent.application.port.in.ChatWithAgentUseCase;
import com.brunosong.system.agent.domain.AgentAction;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseBody;

import java.util.List;
import java.util.Map;

@Controller
@RequiredArgsConstructor
@RequestMapping("/agent")
public class AgentController {

    private final ChatWithAgentUseCase useCase;

    /** 채팅 화면 */
    @GetMapping
    public String view() {
        return "agent";
    }

    /** 로컬에 설치된 Ollama 모델 목록 */
    @GetMapping("/models")
    @ResponseBody
    public List<String> models() {
        return useCase.availableModels();
    }

    /** 채팅 메시지 전송 */
    @PostMapping("/chat")
    @ResponseBody
    public Map<String, Object> chat(@RequestBody Map<String, String> req) {
        AgentAction result = useCase.handle(
                req.getOrDefault("message", ""),
                req.get("model"));
        return Map.of(
                "reply", result.reply() == null ? "" : result.reply(),
                "path",  result.path()  == null ? "" : result.path(),
                "saved", result.hasFile()
        );
    }
}
