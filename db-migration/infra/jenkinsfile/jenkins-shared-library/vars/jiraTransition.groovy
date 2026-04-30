// Jira Cloud REST v3: 이슈 transition (Done 처리 등).
// transitionId 는 워크플로우마다 다름:
//   GET {baseUrl}/rest/api/3/issue/{key}/transitions  로 미리 조회.
//
// 사용:
//   jiraTransition(issueKey: 'PROJ-123', transitionId: '31')
//   jiraTransition(
//     issueKey:     'PROJ-123',
//     transitionId: '31',
//     baseUrl:      'https://your-org.atlassian.net',  // 생략 시 env.JIRA_BASE_URL
//     auth:         'email:token'                      // 생략 시 env.JIRA_AUTH
//   )
def call(Map args) {
    def issueKey     = args.issueKey     ?: error('jiraTransition: issueKey is required')
    def transitionId = args.transitionId ?: error('jiraTransition: transitionId is required')
    def baseUrl      = args.baseUrl      ?: env.JIRA_BASE_URL
    def auth         = args.auth         ?: env.JIRA_AUTH

    if (!baseUrl || !auth) {
        error('jiraTransition: baseUrl/auth 가 환경변수에도 인자에도 없음')
    }

    def payload = groovy.json.JsonOutput.toJson([transition: [id: transitionId.toString()]])
    def resp = sh(
        returnStdout: true,
        script: """
            curl -sS -o /tmp/jira-trans.txt -w '%{http_code}' \\
                -u '${auth}' \\
                -H 'Content-Type: application/json' \\
                -X POST '${baseUrl}/rest/api/3/issue/${issueKey}/transitions' \\
                --data-binary @- <<'JSON'
${payload}
JSON
        """
    ).trim()

    if (resp == '204') {
        echo "Jira ${issueKey} → transition(${transitionId}) 완료"
    } else {
        def errBody = sh(script: 'cat /tmp/jira-trans.txt', returnStdout: true).trim()
        echo "Jira transition 실패 (HTTP ${resp}): ${errBody}"
    }
    return resp
}
