package com.brunosong.dbmigration.flywayappnotify;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 방식 4: Flyway 어플리케이션 자체에서 알림 발송
 *
 * Flyway Callback 으로 마이그레이션 성공/실패를 직접 감지해서
 * Teams Webhook / Jira REST API 를 호출한다.
 *
 * 의도적으로 "단점이 잘 드러나도록" 구현했다.
 *  - 시크릿(Webhook URL, Jira Token)을 앱 환경변수로 주입받음
 *  - 알림 코드가 마이그레이션 모듈 안에 들어가 있음 (재사용 불가)
 *  - 파드가 못 뜨면 알림도 안 나감 (외부 관찰자 부재)
 *  - 환경(dev/prod) 분기를 앱 내부 if 로 처리
 *  - K8s Job backoffLimit 재시도 시 알림이 N번 발사
 *
 * PROBLEMS.md 에 각 단점을 어떻게 재현하는지 정리되어 있다.
 */
@SpringBootApplication
public class FlywayAppNotifyApplication {

    public static void main(String[] args) {
        SpringApplication.run(FlywayAppNotifyApplication.class, args);
    }
}