// 'teams-notify' 잡 호출 예시.
// jira-notify 와 똑같이 fire-and-forget 으로 호출 (wait: false, propagate: false).

pipeline {
    agent any
    stages {
        stage('build') {
            steps { sh '../../gradlew bootJar' }
        }
    }
    post {
        success {
            build job: 'teams-notify',
                  wait: false,
                  propagate: false,
                  parameters: [
                      string(name: 'STATUS',       value: 'SUCCESS'),
                      string(name: 'TITLE',        value: 'DB Migration 성공'),
                      text  (name: 'MESSAGE',      value: "Branch: ${env.GIT_BRANCH ?: '-'}"),
                      string(name: 'CALLER_JOB',   value: env.JOB_NAME),
                      string(name: 'CALLER_BUILD', value: env.BUILD_NUMBER),
                      string(name: 'CALLER_URL',   value: env.BUILD_URL)
                  ]
        }
        failure {
            build job: 'teams-notify',
                  wait: false,
                  propagate: false,
                  parameters: [
                      string(name: 'STATUS',       value: 'FAILURE'),
                      string(name: 'TITLE',        value: 'DB Migration 실패'),
                      text  (name: 'MESSAGE',      value: '콘솔 로그 확인 필요'),
                      string(name: 'CALLER_JOB',   value: env.JOB_NAME),
                      string(name: 'CALLER_BUILD', value: env.BUILD_NUMBER),
                      string(name: 'CALLER_URL',   value: env.BUILD_URL)
                  ]
        }
    }
}
