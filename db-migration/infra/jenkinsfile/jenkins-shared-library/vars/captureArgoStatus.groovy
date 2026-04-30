// argocd app get 결과를 env.CD_* 로 노출.
// argocd CLI 가 PATH 에 있고, ARGOCD_AUTH_TOKEN 환경변수가 노출된 상태에서 호출.
//
// 노출되는 환경변수:
//   CD_REV, CD_SYNC, CD_HEALTH, CD_PREV_REV, CD_PHASE,
//   CD_OPERATION_MSG, CD_FINISHED_AT
def call(Map args) {
    def app    = args.app    ?: env.ARGOCD_APP    ?: error('captureArgoStatus: app required')
    def server = args.server ?: env.ARGOCD_SERVER ?: error('captureArgoStatus: server required')

    withEnv(["A_APP=${app}", "A_SRV=${server}"]) {
        sh '''
            set -e
            argocd app get "$A_APP" --server "$A_SRV" --insecure --grpc-web -o json > /tmp/argo.json

            if command -v jq >/dev/null 2>&1; then
                jq -r '.status.sync.revision        // ""'                       /tmp/argo.json > /tmp/cd_rev
                jq -r '.status.sync.status          // ""'                       /tmp/argo.json > /tmp/cd_sync
                jq -r '.status.health.status        // ""'                       /tmp/argo.json > /tmp/cd_health
                jq -r '.status.history | (.[length-2].revision // "")'           /tmp/argo.json > /tmp/cd_prev
                jq -r '.status.operationState.phase // ""'                       /tmp/argo.json > /tmp/cd_phase
                jq -r '.status.operationState.message // ""'                     /tmp/argo.json > /tmp/cd_opmsg
                jq -r '.status.operationState.finishedAt // ""'                  /tmp/argo.json > /tmp/cd_finished
            else
                # jq 가 없을 때를 위한 단순 fallback (정확하지 않을 수 있음)
                sed -n 's/.*"revision":"\\([0-9a-f]*\\)".*/\\1/p' /tmp/argo.json | head -n1 > /tmp/cd_rev
                sed -n 's/.*"sync":{"status":"\\([^"]*\\)".*/\\1/p'  /tmp/argo.json | head -n1 > /tmp/cd_sync
                sed -n 's/.*"health":{"status":"\\([^"]*\\)".*/\\1/p'/tmp/argo.json | head -n1 > /tmp/cd_health
                : > /tmp/cd_prev
                : > /tmp/cd_phase
                : > /tmp/cd_opmsg
                : > /tmp/cd_finished
            fi
        '''
    }

    env.CD_REV           = readFile('/tmp/cd_rev').trim()
    env.CD_SYNC          = readFile('/tmp/cd_sync').trim()
    env.CD_HEALTH        = readFile('/tmp/cd_health').trim()
    env.CD_PREV_REV      = readFile('/tmp/cd_prev').trim()
    env.CD_PHASE         = readFile('/tmp/cd_phase').trim()
    env.CD_OPERATION_MSG = readFile('/tmp/cd_opmsg').trim()
    env.CD_FINISHED_AT   = readFile('/tmp/cd_finished').trim()

    echo "ArgoCD: sync=${env.CD_SYNC} health=${env.CD_HEALTH} rev=${env.CD_REV?.take(8)}"
}
