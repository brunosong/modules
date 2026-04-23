package com.brunosong.dbmigration.jenkinsdirect;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 방식 1: Jenkins 직접 실행
 *
 * Jenkins 에이전트에서 Flyway를 직접 실행하여 DB 마이그레이션을 수행한다.
 * - 개발/스테이징: PR 승인 후 Jenkins가 자동 실행
 * - 운영: PR 승인 후 Jenkins Job 수동 실행
 */
@SpringBootApplication
public class JenkinsDirectMigrationApplication {

    public static void main(String[] args) {
        SpringApplication.run(JenkinsDirectMigrationApplication.class, args);
    }
}
