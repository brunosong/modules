// 배포 알림 진입점. Teams + Jira 양쪽으로 한 번에 송신.
// 한쪽 채널이 실패해도 다른 쪽은 영향받지 않음 (UNSTABLE 로 표시).
//
// 사용:
//   post {
//     success { notifyDeploy(status: 'SUCCESS') }
//     failure { notifyDeploy(status: 'FAILURE') }
//   }
//
//   // 옵션:
//   notifyDeploy(
//       status:               'SUCCESS',
//       teamsCredentialId:    'teams-webhook-prod',
//       doneTransitionId:     '31',
//       skipTeams:            false,
//       skipJira:             false
//   )
def call(Map args = [:]) {
    def status = args.status ?: 'SUCCESS'

    if (!args.skipTeams) {
        catchError(buildResult: currentBuild.currentResult, stageResult: 'UNSTABLE',
                   message: 'Teams notify 실패') {
            notifyTeams(
                status:       status,
                credentialId: args.teamsCredentialId ?: 'teams-webhook-url'
            )
        }
    }

    if (!args.skipJira) {
        catchError(buildResult: currentBuild.currentResult, stageResult: 'UNSTABLE',
                   message: 'Jira notify 실패') {
            jiraNotifyDeploy(
                status:           status,
                doneTransitionId: args.doneTransitionId,
                issueKey:         args.issueKey
            )
        }
    }
}
