package com.brunosong.dbmigration.argocdhelm;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 방식 4: ArgoCD + Helm chart 기반 환경별 배포
 *
 * - 하나의 Helm chart(템플릿)로 dev/prod 등 여러 환경을 관리한다.
 * - 환경별 차이는 values-*.yaml 에서만 지정 → 단일 진실의 근원 + 환경별 오버레이.
 * - ArgoCD Application 이 chart path + valueFiles 를 가리키며, Sync 시 Helm 렌더링 → 적용.
 */
@SpringBootApplication
public class ArgoCdHelmMigrationApplication {

    public static void main(String[] args) {
        SpringApplication.run(ArgoCdHelmMigrationApplication.class, args);
    }
}
