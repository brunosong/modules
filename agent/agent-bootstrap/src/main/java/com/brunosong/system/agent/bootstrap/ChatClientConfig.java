package com.brunosong.system.agent.bootstrap;

import com.brunosong.system.agent.adapter.out.pdf.PdfReaderTool;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.ollama.OllamaChatModel;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ChatClientConfig {

    @Bean
    public ChatClient chatClient(OllamaChatModel chatModel, PdfReaderTool pdfReaderTool) {
        return ChatClient.builder(chatModel)
                .defaultSystem("당신은 유용한 AI 어시스턴트입니다. 사용자가 PDF 파일 경로를 언급하면 readPdf 도구를 사용하여 파일을 분석하세요. 한국어로 응답하세요.")
                .defaultTools(pdfReaderTool)
                .build();
    }
}
