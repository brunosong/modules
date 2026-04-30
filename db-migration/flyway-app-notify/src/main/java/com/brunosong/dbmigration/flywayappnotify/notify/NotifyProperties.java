package com.brunosong.dbmigration.flywayappnotify.notify;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 알림 관련 설정.
 *
 * 단점 노출 포인트:
 *  - Teams Webhook, Jira 토큰을 마이그레이션 앱이 직접 들고 있다.
 *    → DB 권한 + 외부 API 권한이 한 컨테이너에 모임 (Blast radius 증가)
 *  - 환경별 정책(dev: silent, prod: notify) 을 앱 설정으로 분기해야 함
 */
@ConfigurationProperties(prefix = "notify")
public record NotifyProperties(
        String environment,         // dev / stage / prod
        boolean enabled,            // 환경별 분기 (앱 재배포 없이 못 바꿈)
        String teamsWebhookUrl,     // 시크릿 1
        String jiraBaseUrl,
        String jiraEmail,
        String jiraApiToken,        // 시크릿 2
        String jiraIssueKey,        // 어떤 이슈를 transition 할지 — 앱이 알아야 한다는 점 자체가 이상
        String jiraDoneTransitionId
) {
}