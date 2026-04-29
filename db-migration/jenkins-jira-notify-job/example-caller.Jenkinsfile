// 'jira-notify' 잡을 호출하는 예시.
// 호출 측 잡은 Jira 자격증명을 들고 있을 필요 없음 — 'jira-notify' 잡이 담당.
//
// 동작:
//   - master push 성공 → jira-notify 트리거 (코멘트 + Done transition)
//   - 어디서든 실패 → jira-notify 트리거 (코멘트만, transition 없음)
//   - MR 검증 단계는 호출 안 함

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
                if (env.IS_MR == 'true') { return }
                build job: 'jira-notify',
                      wait: false,
                      propagate: false,
                      parameters: [
                          string(name: 'EXTRACT_FROM',
                                 value: "${env.gitlabSourceBranch ?: env.GIT_BRANCH ?: ''} " +
                                        "${env.gitlabMergeRequestTitle ?: ''}"),
                          string(name: 'STATUS',        value: 'SUCCESS'),
                          string(name: 'CALLER_JOB',    value: env.JOB_NAME),
                          string(name: 'CALLER_BUILD',  value: env.BUILD_NUMBER),
                          string(name: 'CALLER_URL',    value: env.BUILD_URL),
                          string(name: 'TRANSITION_ID', value: '31'),  // Done transition ID
                          text  (name: 'MESSAGE',       value: 'DB migration 적용 완료')
                      ]
            }
        }
        failure {
            script {
                def stage = env.IS_MR == 'true' ? 'MR 검증' : '마이그레이션'
                build job: 'jira-notify',
                      wait: false,
                      propagate: false,
                      parameters: [
                          string(name: 'EXTRACT_FROM',
                                 value: "${env.gitlabSourceBranch ?: env.GIT_BRANCH ?: ''} " +
                                        "${env.gitlabMergeRequestTitle ?: ''}"),
                          string(name: 'STATUS',       value: 'FAILURE'),
                          string(name: 'CALLER_JOB',   value: env.JOB_NAME),
                          string(name: 'CALLER_BUILD', value: env.BUILD_NUMBER),
                          string(name: 'CALLER_URL',   value: env.BUILD_URL),
                          // TRANSITION_ID 안 넘김 → 실패엔 transition 절대 안 함
                          text  (name: 'MESSAGE',      value: "${stage} 실패 — Jenkins 콘솔 확인")
                      ]
            }
        }
    }
}
