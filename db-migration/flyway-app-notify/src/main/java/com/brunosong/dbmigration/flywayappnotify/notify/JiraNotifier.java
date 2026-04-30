package com.brunosong.dbmigration.flywayappnotify.notify;

import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;

/**
 * Jira REST API 호출 — 코멘트 추가 + 트랜지션.
 *
 * 단점 노출 포인트:
 *  - 앱이 "어떤 이슈를 어떤 상태로 옮길지" 알아야 함 → 마이그레이션 앱이 워크플로 ID 까지 떠안음
 *  - jiraTransition.groovy / jiraAddComment.groovy 와 동일 책임을 두 곳에서 유지보수
 *  - Basic Auth 토큰을 컨테이너 ENV 로 노출
 */
@Component
public class JiraNotifier {

    private final NotifyProperties props;
    private final WebClient webClient;

    public JiraNotifier(NotifyProperties props) {
        this.props = props;
        this.webClient = WebClient.builder().build();
    }

    public void addComment(String message) {
        if (!props.enabled() || props.jiraIssueKey() == null) {
            return;
        }
        try {
            webClient.post()
                    .uri("%s/rest/api/3/issue/%s/comment".formatted(props.jiraBaseUrl(), props.jiraIssueKey()))
                    .header("Authorization", "Basic " + basicAuth())
                    .header("Content-Type", "application/json")
                    .bodyValue(Map.of(
                            "body", Map.of(
                                    "type", "doc",
                                    "version", 1,
                                    "content", java.util.List.of(Map.of(
                                            "type", "paragraph",
                                            "content", java.util.List.of(Map.of("type", "text", "text", message))
                                    ))
                            )
                    ))
                    .retrieve()
                    .toBodilessEntity()
                    .block(Duration.ofSeconds(5));
        } catch (Exception e) {
            System.err.println("[JiraNotifier] addComment failed: " + e.getMessage());
        }
    }

    public void transitionToDone() {
        if (!props.enabled() || props.jiraIssueKey() == null || props.jiraDoneTransitionId() == null) {
            return;
        }
        try {
            webClient.post()
                    .uri("%s/rest/api/3/issue/%s/transitions".formatted(props.jiraBaseUrl(), props.jiraIssueKey()))
                    .header("Authorization", "Basic " + basicAuth())
                    .header("Content-Type", "application/json")
                    .bodyValue(Map.of("transition", Map.of("id", props.jiraDoneTransitionId())))
                    .retrieve()
                    .toBodilessEntity()
                    .block(Duration.ofSeconds(5));
        } catch (Exception e) {
            System.err.println("[JiraNotifier] transition failed: " + e.getMessage());
        }
    }

    private String basicAuth() {
        String raw = props.jiraEmail() + ":" + props.jiraApiToken();
        return Base64.getEncoder().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }
}