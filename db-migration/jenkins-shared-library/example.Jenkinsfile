// Shared Library 사용 예제.
// 위에 @Library 한 줄만 있으면 어떤 파이프라인에서든 jiraNotify(...) 호출 가능.
@Library('shared-jira') _

pipeline {
    agent any

    options {
        gitLabConnection('gitlab-local')
        gitlabBuilds(builds: ['build', 'migrate'])
        timestamps()
    }

    triggers {
        gitlab(
            triggerOnPush: true,
            triggerOnMergeRequest: true,
            skipWorkInProgressMergeRequest: true,
            branchFilterType: 'NameBasedFilter',
            includeBranchesSpec: 'master'
        )
    }

    environment {
        DB_URL      = credentials('db-url')
        DB_USERNAME = credentials('db-username')
        DB_PASSWORD = credentials('db-password')

        // Library 의 jira* step 들이 자동으로 읽어가는 환경변수
        JIRA_BASE_URL           = credentials('jira-base-url')
        JIRA_AUTH               = credentials('jira-auth')
        JIRA_DONE_TRANSITION_ID = credentials('jira-done-transition-id')

        IS_MR = "${env.gitlabActionType == 'MERGE'}"
    }

    stages {
        stage('build') {
            steps {
                gitlabCommitStatus(name: 'build') {
                    dir('db-migration/jenkins-gitlab-mr') {
                        sh '../../gradlew bootJar'
                    }
                }
            }
        }

        stage('migrate') {
            when { expression { env.IS_MR != 'true' } }
            steps {
                gitlabCommitStatus(name: 'migrate') {
                    dir('db-migration/jenkins-gitlab-mr') {
                        sh """
                            java -jar build/libs/jenkins-gitlab-mr-1.0-SNAPSHOT.jar \
                                --spring.datasource.url=${DB_URL} \
                                --spring.datasource.username=${DB_USERNAME} \
                                --spring.datasource.password=${DB_PASSWORD}
                        """
                    }
                }
            }
        }
    }

    post {
        success {
            script {
                if (env.IS_MR != 'true') {
                    jiraNotify(status: 'SUCCESS')
                }
            }
        }
        failure {
            script {
                def stage = env.IS_MR == 'true' ? 'MR 검증' : '마이그레이션'
                jiraNotify(status: 'FAILURE', message: "${stage} 실패 — 콘솔 로그 확인")
            }
        }
    }
}
