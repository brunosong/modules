package com.brunosong.dbmigration.jenkinsgitlabmr;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * GitLab MR(=PR) 트리거 기반 Jenkins 동작 예제.
 *
 * 흐름:
 *   1) GitLab 에 MR 생성/업데이트 → GitLab Webhook 발사
 *   2) Jenkins (GitLab Plugin) 가 수신 → Pipeline 실행
 *   3) MR 상태에 따라 Flyway validate(검증) 또는 migrate(반영) 수행
 *   4) Jenkins → GitLab Commit Status 로 결과 회신 (MR 화면에 ✅/❌ 표시)
 */
@SpringBootApplication
public class JenkinsGitlabMrApplication {

    public static void main(String[] args) {
        SpringApplication.run(JenkinsGitlabMrApplication.class, args);
    }
}
