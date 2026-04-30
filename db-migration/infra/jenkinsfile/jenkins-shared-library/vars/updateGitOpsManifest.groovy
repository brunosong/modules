// GitLab 매니페스트 레포의 image 필드 한 줄을 GitLab Commits API 로 갱신.
// 작업자 워크스페이스에 매니페스트를 clone 하지 않고, API 호출만으로 커밋을 만든다.
//
// 동작:
//   1) /api/v4/projects/{id} 로 토큰 / 프로젝트 접근 sanity check
//   2) JSON Patch (op=replace) 로 image 필드 교체 후 Commits API 로 단일 커밋 생성
//   3) 2xx 가 아니면 실패 처리 (응답 본문은 /tmp/gl_resp.json 에 저장)
//   4) 생성된 커밋의 풍부한 metadata 를 Map 으로 반환
//        [ sha, short, url, author, message ]
//      (CD 단계에서 ArgoCD --revision, 알림용 표시 등에 활용)
//
// 사용:
//   def m = updateGitOpsManifest(
//       projectId: '53',
//       filePath:  'db-migration/argocd-gitops/k8s/image-patch.yaml',
//       imageRepo: "${env.REGISTRY}/${env.IMAGE_NAME}",
//       imageTag:  env.IMAGE_TAG
//   )
//   echo "Committed ${m.short} by ${m.author}: ${m.message}  ${m.url}"
//
//   // 옵션 다 명시:
//   updateGitOpsManifest(
//       gitlabHost:   'http://host.docker.internal:8090',
//       projectId:    '53',
//       branch:       'master',
//       filePath:     'db-migration/argocd-gitops/k8s/image-patch.yaml',
//       imageRepo:    'localhost:5001/db-migration-argocd',
//       imageTag:     '20260429-42',
//       credentialId: 'gitlab-api-token'
//   )
def call(Map args) {
    def gitlabHost   = args.gitlabHost   ?: 'http://host.docker.internal:8090'
    def projectId    = args.projectId    ?: error('updateGitOpsManifest: projectId required')
    def branch       = args.branch       ?: 'master'
    def filePath     = args.filePath     ?: error('updateGitOpsManifest: filePath required')
    def imageRepo    = args.imageRepo    ?: error('updateGitOpsManifest: imageRepo required')
    def imageTag     = args.imageTag     ?: error('updateGitOpsManifest: imageTag required')
    def credentialId = args.credentialId ?: 'gitlab-api-token'

    withCredentials([string(credentialsId: credentialId, variable: 'GITLAB_TOKEN')]) {

        // sanity check: 토큰 + 프로젝트 접근 확인
        withEnv([
            "GITLAB_HOST=${gitlabHost}",
            "PROJECT_ID=${projectId}"
        ]) {
            sh '''
                set -e
                echo "Project ${PROJECT_ID} 조회"
                curl --silent --show-error --fail \
                    --header "PRIVATE-TOKEN: ${GITLAB_TOKEN}" \
                    "${GITLAB_HOST}/api/v4/projects/${PROJECT_ID}" \
                    -o /dev/null
                echo "OK"
            '''
        }

        def patchContent = """\
        - op: replace
          path: /spec/template/spec/containers/0/image
          value: ${imageRepo}:${imageTag}
        """.stripIndent()

        def commitMsg = "ci: bump image to ${imageTag} [skip ci]"

        // 멀티라인 content 는 환경변수로 받아 --data-urlencode 로 안전하게 인코딩.
        withEnv([
            "GITLAB_HOST=${gitlabHost}",
            "PROJECT_ID=${projectId}",
            "BRANCH=${branch}",
            "FILE_PATH=${filePath}",
            "PATCH_CONTENT=${patchContent}",
            "COMMIT_MSG=${commitMsg}"
        ]) {
            sh '''
                set -e
                HTTP_CODE=$(curl --silent --show-error --output /tmp/gl_resp.json --write-out "%{http_code}" \
                    --request POST \
                    --header "PRIVATE-TOKEN: ${GITLAB_TOKEN}" \
                    --data-urlencode "branch=${BRANCH}" \
                    --data-urlencode "commit_message=${COMMIT_MSG}" \
                    --data-urlencode "actions[][action]=update" \
                    --data-urlencode "actions[][file_path]=${FILE_PATH}" \
                    --data-urlencode "actions[][content]=${PATCH_CONTENT}" \
                    "${GITLAB_HOST}/api/v4/projects/${PROJECT_ID}/repository/commits")

                echo "HTTP ${HTTP_CODE}"
                cat /tmp/gl_resp.json
                echo

                case "$HTTP_CODE" in
                    2*) ;;
                    *)  echo "GitLab commit failed"; exit 1 ;;
                esac

                # 풍부한 metadata 파싱. jq 가 있으면 사용, 없으면 sed fallback.
                if command -v jq >/dev/null 2>&1; then
                    jq -r '.id'                  /tmp/gl_resp.json > /tmp/mft_sha
                    jq -r '.short_id'            /tmp/gl_resp.json > /tmp/mft_short
                    jq -r '.web_url        // ""'/tmp/gl_resp.json > /tmp/mft_url
                    jq -r '.author_name    // ""'/tmp/gl_resp.json > /tmp/mft_author
                    jq -r '.title          // ""'/tmp/gl_resp.json > /tmp/mft_msg
                else
                    sed -n 's/.*"id":"\\([0-9a-f]*\\)".*/\\1/p'        /tmp/gl_resp.json | head -n1 > /tmp/mft_sha
                    sed -n 's/.*"short_id":"\\([^"]*\\)".*/\\1/p'      /tmp/gl_resp.json | head -n1 > /tmp/mft_short
                    sed -n 's/.*"web_url":"\\([^"]*\\)".*/\\1/p'       /tmp/gl_resp.json | head -n1 > /tmp/mft_url
                    sed -n 's/.*"author_name":"\\([^"]*\\)".*/\\1/p'   /tmp/gl_resp.json | head -n1 > /tmp/mft_author
                    sed -n 's/.*"title":"\\([^"]*\\)".*/\\1/p'         /tmp/gl_resp.json | head -n1 > /tmp/mft_msg
                fi

                SHA=$(cat /tmp/mft_sha)
                if [ -z "$SHA" ]; then
                    echo "Failed to parse commit SHA"; exit 1
                fi
                SHORT=$(cat /tmp/mft_short)
                echo "Committed: ${SHORT} (${SHA})"
            '''

            return [
                sha:     readFile('/tmp/mft_sha').trim(),
                short:   readFile('/tmp/mft_short').trim(),
                url:     readFile('/tmp/mft_url').trim(),
                author:  readFile('/tmp/mft_author').trim(),
                message: readFile('/tmp/mft_msg').trim()
            ]
        }
    }
}
