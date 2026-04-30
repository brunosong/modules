package com.brunosong.dbmigration.flywayappnotify.notify;

import org.flywaydb.core.api.callback.Callback;
import org.flywaydb.core.api.callback.Context;
import org.flywaydb.core.api.callback.Event;
import org.springframework.stereotype.Component;

/**
 * Flyway Callback 으로 마이그레이션 라이프사이클 이벤트를 받는다.
 *
 *  AFTER_MIGRATE        → 전체 마이그레이션 성공
 *  AFTER_MIGRATE_ERROR  → 마이그레이션 도중 실패
 *  AFTER_EACH_MIGRATE   → 개별 스크립트 성공
 *
 * 장점 노출 포인트:
 *  - 적용된 스크립트, 버전, 실행 시간 같은 Flyway 메타정보를 직접 손에 쥘 수 있음
 *
 * 단점 노출 포인트:
 *  - 이 콜백은 "JVM 이 살아 있고 Flyway 가 시작되었을 때만" 동작한다
 *  - 즉, 파드가 못 뜨거나(이미지 Pull 실패, ENV 누락, OOM) JDBC 커넥션 자체를 못 만들면
 *    이 콜백은 단 한 번도 호출되지 않는다 → 가장 중요한 장애가 침묵한다
 */
@Component
public class MigrationNotifyCallback implements Callback {

    private final TeamsNotifier teams;
    private final JiraNotifier jira;

    public MigrationNotifyCallback(TeamsNotifier teams, JiraNotifier jira) {
        this.teams = teams;
        this.jira = jira;
    }

    @Override
    public boolean supports(Event event, Context context) {
        return event == Event.AFTER_MIGRATE || event == Event.AFTER_MIGRATE_ERROR;
    }

    @Override
    public boolean canHandleInTransaction(Event event, Context context) {
        return false;
    }

    @Override
    public void handle(Event event, Context context) {
        switch (event) {
            case AFTER_MIGRATE -> onSuccess();
            case AFTER_MIGRATE_ERROR -> onFailure();
            default -> {}
        }
    }

    private void onSuccess() {
        String msg = "DB 마이그레이션 성공 — 콜백 시점이라 적용 스크립트/버전을 첨부할 수 있다";
        teams.send("Migration Success", msg);
        jira.addComment(msg);
        jira.transitionToDone();
    }

    private void onFailure() {
        // K8s Job backoffLimit 때문에 이 메서드가 N번 호출될 수 있음 → 알림 스팸
        String msg = "DB 마이그레이션 실패 — 다만 JVM 이 살아 있어야만 이 메시지가 나간다";
        teams.send("Migration FAILED", msg);
        jira.addComment(msg);
    }

    @Override
    public String getCallbackName() {
        return "MigrationNotifyCallback";
    }
}