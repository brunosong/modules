package com.brunosong.dbmigration.flywayappnotify.notify;

import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.util.Map;

/**
 * Teams Incoming Webhook 호출.
 *
 * 단점 노출 포인트:
 *  - jenkins-shared-library 의 teamsNotify.groovy 와 동일 로직을 모듈마다 중복 구현해야 함
 *  - 호출이 동기적이라 Teams 가 느리면 마이그레이션 Job 종료가 지연됨
 *  - Webhook 자체가 죽어 있으면 예외 처리 정책을 마이그레이션 앱이 떠안아야 함
 */
@Component
public class TeamsNotifier {

    private final NotifyProperties props;
    private final WebClient webClient;

    public TeamsNotifier(NotifyProperties props) {
        this.props = props;
        this.webClient = WebClient.builder().build();
    }

    public void send(String title, String text) {
        if (!props.enabled()) {
            return; // dev 환경은 침묵 — 환경 분기 if 가 앱 안에 박힘
        }
        if (props.teamsWebhookUrl() == null || props.teamsWebhookUrl().isBlank()) {
            // 시크릿 누락 시 어디서 어떻게 처리할지 정책이 앱 책임이 됨
            System.err.println("[TeamsNotifier] webhook url not configured — skip");
            return;
        }

        Map<String, Object> payload = Map.of(
                "@type", "MessageCard",
                "@context", "https://schema.org/extensions",
                "summary", title,
                "themeColor", "0076D7",
                "title", "[%s] %s".formatted(props.environment(), title),
                "text", text
        );

        try {
            webClient.post()
                    .uri(props.teamsWebhookUrl())
                    .bodyValue(payload)
                    .retrieve()
                    .toBodilessEntity()
                    .block(Duration.ofSeconds(5));
        } catch (Exception e) {
            // 알림 실패가 마이그레이션 결과에 영향을 주면 안 되지만,
            // 반대로 "알림이 갔는지 안 갔는지" 책임이 모호해짐
            System.err.println("[TeamsNotifier] failed: " + e.getMessage());
        }
    }
}