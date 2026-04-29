// 브랜치명 / MR 제목 / 커밋 메시지에서 첫 번째 Jira 키 (PROJ-123) 추출.
// 못 찾으면 null. 호출 측에서 null 체크해서 skip 결정.
//
// 사용:
//   def key = jiraExtractKey()
//   def key = jiraExtractKey(pattern: /([A-Z]{2,}-\d+)/)
def call(Map args = [:]) {
    def pattern = args.pattern ?: ~/([A-Z][A-Z0-9]+-\d+)/
    def candidates = [
        env.gitlabSourceBranch,
        env.gitlabMergeRequestTitle,
        env.GIT_BRANCH,
        env.BRANCH_NAME,
        sh(script: 'git log -1 --pretty=%s', returnStdout: true).trim()
    ].findAll { it }
    def m = candidates.join(' ') =~ pattern
    return m ? m[0][1] : null
}
