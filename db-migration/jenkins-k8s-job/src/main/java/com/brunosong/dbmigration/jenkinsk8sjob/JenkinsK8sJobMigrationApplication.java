package com.brunosong.dbmigration.jenkinsk8sjob;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 방식 2: Jenkins + Kubernetes Job
 *
 * Jenkins가 K8s Job을 생성하여 클러스터 내부에서 Flyway 마이그레이션을 실행한다.
 * - 개발/스테이징: PR 승인 후 Jenkins가 K8s Job 자동 생성·실행
 * - 운영: PR 승인 후 관리자가 Jenkins Job 수동 실행
 * - DB 접근이 클러스터 내부로 제한되어 보안 경계 유지
 */
@SpringBootApplication
public class JenkinsK8sJobMigrationApplication {

    public static void main(String[] args) {
        SpringApplication.run(JenkinsK8sJobMigrationApplication.class, args);
    }
}
