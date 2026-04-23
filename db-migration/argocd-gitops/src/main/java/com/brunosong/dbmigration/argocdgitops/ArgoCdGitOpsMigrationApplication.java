package com.brunosong.dbmigration.argocdgitops;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 방식 3: ArgoCD 기반 GitOps + K8s Job
 *
 * ArgoCD가 Git 저장소 변경을 감지하여 K8s Job으로 Flyway 마이그레이션을 실행한다.
 * - 개발/스테이징: PR 승인·머지 후 ArgoCD 자동 감지·배포
 * - 운영: PR 승인·머지 후 DBA 또는 관리자가 ArgoCD UI에서 수동 승인 배포
 * - Git 이력 = DB 변경 이력으로 추적성 확보
 */
@SpringBootApplication
public class ArgoCdGitOpsMigrationApplication {

    public static void main(String[] args) {
        SpringApplication.run(ArgoCdGitOpsMigrationApplication.class, args);
    }
}
