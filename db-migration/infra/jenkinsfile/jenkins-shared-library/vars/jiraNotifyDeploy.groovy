// 배포 전용 Jira 알림. 기존 jiraNotify 보다 풍부한 Git/ArgoCD 정보를 wiki markup 으로 코멘트.
//
// 동작:
//   1) jiraExtractKey() 로 브랜치/커밋에서 Jira 키 추출 (없으면 조용히 skip)
//   2) Source/Manifest commit + ArgoCD 상태를 표·코드블록 으로 코멘트
//   3) status == 'SUCCESS' && doneTransitionId 있으면 → Done transition
//
// 사용:
//   post {
//     success { jiraNotifyDeploy(status: 'SUCCESS') }
//     failure { jiraNotifyDeploy(status: 'FAILURE') }
//   }
//
//   jiraNotifyDeploy(
//     status:           'SUCCESS',
//     doneTransitionId: '31',
//     issueKey:         'PROJ-123'
//   )
def call(Map args) {
    def status   = args.status ?: error("jiraNotifyDeploy: status required ('SUCCESS' or 'FAILURE')")
    def doneTransitionId = args.doneTransitionId ?: env.JIRA_DONE_TRANSITION_ID

    def issueKey = args.issueKey ?: jiraExtractKey()
    if (!issueKey) {
        echo 'jiraNotifyDeploy: Jira 키 못 찾음 (브랜치/커밋에 PROJ-123 형식 필요) — skip'
        return
    }

    def ok    = status == 'SUCCESS'
    def icon  = ok ? '(/)' : '(x)'
    def appLabel = env.ARGOCD_APP ?: env.JOB_NAME

    def srcCommitCell = env.SRC_SHORT \
        ? (env.SRC_COMMIT_URL ? "[${env.SRC_SHORT}|${env.SRC_COMMIT_URL}]" : env.SRC_SHORT) \
        : '-'
    def mftCommitCell = env.MFT_SHORT \
        ? (env.MFT_URL ? "[${env.MFT_SHORT}|${env.MFT_URL}]" : env.MFT_SHORT) \
        : '-'

    def text = """\
${icon} *${appLabel} — ${status}*

||Field||Value||
|Build|[#${env.BUILD_NUMBER}|${env.BUILD_URL}] (${currentBuild.durationString ?: '-'})|
|Image|{{${env.REGISTRY ?: '-'}/${env.IMAGE_NAME ?: '-'}:${env.IMAGE_TAG ?: '-'}}}|
|Branch|{{${env.SRC_BRANCH ?: '-'}}}|
|Triggered by|${env.BUILD_USER ?: 'system'}|

h3. Source commit
${srcCommitCell} — ${env.SRC_SUBJECT ?: '-'}
_${env.SRC_AUTHOR ?: '-'}${env.SRC_EMAIL ? ' <' + env.SRC_EMAIL + '>' : ''} · ${env.SRC_DATE ?: '-'}_

{code:title=changed files}
${env.SRC_CHANGES ?: '(no diff info)'}
{code}

h3. Manifest commit
${mftCommitCell} — ${env.MFT_MSG ?: '-'}
_${env.MFT_AUTHOR ?: '-'}_

h3. ArgoCD
||App||Sync||Health||Phase||Revision||Prev (rollback)||
|${env.ARGOCD_APP ?: '-'}|${env.CD_SYNC ?: '-'}|${env.CD_HEALTH ?: '-'}|${env.CD_PHASE ?: '-'}|{{${(env.CD_REV ?: '').take(8) ?: '-'}}}|{{${(env.CD_PREV_REV ?: '').take(8) ?: '-'}}}|
""".stripIndent()

    if (!ok && env.CD_OPERATION_MSG) {
        text += "\n{panel:title=ArgoCD operation message|borderColor=#c00|bgColor=#fee}\n${env.CD_OPERATION_MSG}\n{panel}\n"
    }

    jiraAddComment(issueKey: issueKey, text: text)

    if (ok && doneTransitionId) {
        jiraTransition(issueKey: issueKey, transitionId: doneTransitionId)
    }
}
