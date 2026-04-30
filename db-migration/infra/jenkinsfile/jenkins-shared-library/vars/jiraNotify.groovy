// 위 3개 (jiraExtractKey + jiraAddComment + jiraTransition) 를 묶은 한 줄 호출.
// 보통 Jenkinsfile post 블록에서 success / failure 시 한 번씩 호출하면 끝.
//
// 동작:
//   1) 브랜치/커밋에서 Jira 키 추출 (없으면 조용히 skip)
//   2) 코멘트 추가 (Job/Build 정보 + 사용자 메시지)
//   3) status == 'SUCCESS' && doneTransitionId 있으면 → Done transition
//      (실패 시엔 transition 안 함 — 잘못된 Done 처리 방지)
//
// 사용:
//   post {
//     success { jiraNotify(status: 'SUCCESS') }
//     failure { jiraNotify(status: 'FAILURE', message: '마이그레이션 실패') }
//   }
//
//   // 옵션 다 명시:
//   jiraNotify(
//     status:           'SUCCESS',
//     message:          '추가로 남길 한 줄',
//     doneTransitionId: '31',
//     issueKey:         'PROJ-123'  // 직접 지정 (자동 추출 무시)
//   )
def call(Map args) {
    def status = args.status ?: error("jiraNotify: status required ('SUCCESS' or 'FAILURE')")
    def message          = args.message ?: ''
    def doneTransitionId = args.doneTransitionId ?: env.JIRA_DONE_TRANSITION_ID

    def issueKey = args.issueKey ?: jiraExtractKey()
    if (!issueKey) {
        echo 'jiraNotify: Jira 키 못 찾음 (브랜치/커밋에 PROJ-123 형식 필요) — skip'
        return
    }

    def icon = status == 'SUCCESS' ? '[OK]' : '[FAIL]'
    def text = "${icon} ${status}\n" +
               "Job: ${env.JOB_NAME} #${env.BUILD_NUMBER}\n" +
               "Build URL: ${env.BUILD_URL}" +
               (message ? "\n${message}" : '')

    jiraAddComment(issueKey: issueKey, text: text)

    if (status == 'SUCCESS' && doneTransitionId) {
        jiraTransition(issueKey: issueKey, transitionId: doneTransitionId)
    }
}
