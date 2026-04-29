// Jira Cloud REST v3: 이슈에 코멘트 추가.
// 본문은 ADF (Atlassian Document Format) 로 자동 변환 — 줄바꿈마다 paragraph 노드 생성.
//
// 사용:
//   jiraAddComment(issueKey: 'PROJ-123', text: '빌드 성공\nlink: ...')
//   jiraAddComment(
//     issueKey: 'PROJ-123',
//     text:     '...',
//     baseUrl:  'https://your-org.atlassian.net',  // 생략 시 env.JIRA_BASE_URL
//     auth:     'email:token'                      // 생략 시 env.JIRA_AUTH
//   )
def call(Map args) {
    def issueKey = args.issueKey ?: error('jiraAddComment: issueKey is required')
    def text     = args.text     ?: error('jiraAddComment: text is required')
    def baseUrl  = args.baseUrl  ?: env.JIRA_BASE_URL
    def auth     = args.auth     ?: env.JIRA_AUTH

    if (!baseUrl || !auth) {
        error('jiraAddComment: baseUrl/auth 가 환경변수에도 인자에도 없음')
    }

    def body = groovy.json.JsonOutput.toJson([
        body: [
            type   : 'doc',
            version: 1,
            content: text.split('\n').collect { line ->
                [ type: 'paragraph', content: [[ type: 'text', text: line ]] ]
            }
        ]
    ])

    def resp = sh(
        returnStdout: true,
        script: """
            curl -sS -o /tmp/jira-comment.txt -w '%{http_code}' \\
                -u '${auth}' \\
                -H 'Content-Type: application/json' \\
                -X POST '${baseUrl}/rest/api/3/issue/${issueKey}/comment' \\
                --data-binary @- <<'JSON'
${body}
JSON
        """
    ).trim()

    if (resp == '201') {
        echo "Jira ${issueKey} 코멘트 추가 완료"
    } else {
        def errBody = sh(script: 'cat /tmp/jira-comment.txt', returnStdout: true).trim()
        echo "Jira 코멘트 실패 (HTTP ${resp}): ${errBody}"
    }
    return resp
}
