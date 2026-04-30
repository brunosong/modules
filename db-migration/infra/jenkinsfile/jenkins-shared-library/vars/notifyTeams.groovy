// MS Teams Incoming Webhook 으로 Adaptive Card 송신.
//
// 사전 준비 (Jenkins → Manage Jenkins → Credentials)
//   - Kind: Secret text
//     ID:   teams-webhook-url   (또는 args.credentialId 로 오버라이드)
//     Secret: <Teams 채널의 Incoming Webhook URL>
//
// 사용:
//   notifyTeams(status: 'SUCCESS')
//   notifyTeams(status: 'FAILURE', credentialId: 'teams-webhook-prod')
//
// 알림에 담는 Git 정보:
//   - 소스 레포 commit (SRC_*)        ← captureGitInfo()
//   - 매니페스트 commit (MFT_*)       ← updateGitOpsManifest() 반환값을 env 에 보존
//   - ArgoCD 상태 (CD_*)             ← captureArgoStatus()
import groovy.json.JsonOutput

def call(Map args = [:]) {
    def status       = args.status ?: 'SUCCESS'
    def credentialId = args.credentialId ?: 'teams-webhook-url'

    def ok    = status == 'SUCCESS'
    def color = ok ? 'Good' : 'Attention'
    def icon  = ok ? '✅' : '❌'
    def appLabel = env.ARGOCD_APP ?: env.JOB_NAME

    def srcLine = env.SRC_SHORT \
        ? (env.SRC_COMMIT_URL \
            ? "**[${env.SRC_SHORT}](${env.SRC_COMMIT_URL})** ${env.SRC_SUBJECT ?: ''}" \
            : "**${env.SRC_SHORT}** ${env.SRC_SUBJECT ?: ''}") \
        : '_(소스 commit 정보 없음)_'

    def srcAuthor = env.SRC_AUTHOR \
        ? "_${env.SRC_AUTHOR}${env.SRC_EMAIL ? ' <' + env.SRC_EMAIL + '>' : ''} · ${env.SRC_DATE ?: ''}_" \
        : ''

    def mftLine = env.MFT_SHORT \
        ? (env.MFT_URL \
            ? "**[${env.MFT_SHORT}](${env.MFT_URL})** ${env.MFT_MSG ?: ''}" \
            : "**${env.MFT_SHORT}** ${env.MFT_MSG ?: ''}") \
        : '_(매니페스트 commit 미생성)_'
    def mftAuthor = env.MFT_AUTHOR ? "_${env.MFT_AUTHOR}_" : ''

    def body = []
    body << [ type:'TextBlock', size:'Large', weight:'Bolder', color:color,
              text: "${icon} ${appLabel} — ${status}" ]

    body << [ type:'FactSet', facts: [
        [ title:'Image',     value: "${env.REGISTRY ?: '-'}/${env.IMAGE_NAME ?: '-'}:${env.IMAGE_TAG ?: '-'}" ],
        [ title:'Branch',    value: env.SRC_BRANCH ?: '-' ],
        [ title:'Triggered', value: env.BUILD_USER ?: 'system' ],
        [ title:'Duration',  value: currentBuild.durationString ?: '-' ],
        [ title:'Job',       value: "${env.JOB_NAME} #${env.BUILD_NUMBER}" ]
    ]]

    body << [ type:'TextBlock', weight:'Bolder', text:'📦 Source commit', spacing:'Medium', separator:true ]
    body << [ type:'TextBlock', wrap:true, text: srcLine ]
    if (srcAuthor) body << [ type:'TextBlock', wrap:true, isSubtle:true, spacing:'None', text: srcAuthor ]
    if (env.SRC_CHANGES) {
        body << [ type:'TextBlock', wrap:true, isSubtle:true, fontType:'Monospace',
                  text: env.SRC_CHANGES ]
    }

    body << [ type:'TextBlock', weight:'Bolder', text:'📝 Manifest commit', spacing:'Medium', separator:true ]
    body << [ type:'TextBlock', wrap:true, text: mftLine ]
    if (mftAuthor) body << [ type:'TextBlock', wrap:true, isSubtle:true, spacing:'None', text: mftAuthor ]

    body << [ type:'TextBlock', weight:'Bolder', text:'🚀 ArgoCD', spacing:'Medium', separator:true ]
    body << [ type:'FactSet', facts: [
        [ title:'App',          value: env.ARGOCD_APP ?: '-' ],
        [ title:'Sync',         value: env.CD_SYNC   ?: '-' ],
        [ title:'Health',       value: env.CD_HEALTH ?: '-' ],
        [ title:'Phase',        value: env.CD_PHASE  ?: '-' ],
        [ title:'Revision',     value: (env.CD_REV      ?: '').take(8) ?: '-' ],
        [ title:'Prev (rollback)', value: (env.CD_PREV_REV ?: '').take(8) ?: '-' ]
    ]]
    if (!ok && env.CD_OPERATION_MSG) {
        body << [ type:'TextBlock', wrap:true, color:'Attention',
                  text: "ArgoCD: ${env.CD_OPERATION_MSG}" ]
    }

    def actions = []
    if (env.BUILD_URL)        actions << [ type:'Action.OpenUrl', title:'Build',           url: env.BUILD_URL ]
    if (env.SRC_COMMIT_URL)   actions << [ type:'Action.OpenUrl', title:'Source commit',   url: env.SRC_COMMIT_URL ]
    if (env.MFT_URL)          actions << [ type:'Action.OpenUrl', title:'Manifest commit', url: env.MFT_URL ]

    def card = [
        type: 'message',
        attachments: [[
            contentType: 'application/vnd.microsoft.card.adaptive',
            content: [
                '$schema': 'http://adaptivecards.io/schemas/adaptive-card.json',
                type:    'AdaptiveCard',
                version: '1.4',
                body:    body,
                actions: actions
            ]
        ]]
    ]

    writeFile file: '/tmp/teams_card.json', text: JsonOutput.toJson(card)

    withCredentials([string(credentialsId: credentialId, variable: 'TEAMS_URL')]) {
        sh '''
            set -e
            HTTP_CODE=$(curl --silent --show-error --output /tmp/teams_resp.txt --write-out "%{http_code}" \
                -H "Content-Type: application/json" \
                --data @/tmp/teams_card.json \
                "$TEAMS_URL")
            echo "Teams HTTP ${HTTP_CODE}"
            case "$HTTP_CODE" in
                2*) ;;
                *)  echo "Teams notify failed"; cat /tmp/teams_resp.txt; exit 1 ;;
            esac
        '''
    }
}
