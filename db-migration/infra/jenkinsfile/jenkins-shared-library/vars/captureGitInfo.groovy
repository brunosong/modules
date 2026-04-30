// 앱 레포 git metadata 를 env.SRC_* 로 노출.
// checkout scm 직후 1회 호출.
//
// 노출되는 환경변수:
//   SRC_SHA, SRC_SHORT, SRC_AUTHOR, SRC_EMAIL, SRC_SUBJECT, SRC_DATE,
//   SRC_BODY, SRC_CHANGES, SRC_REPO_URL, SRC_BRANCH, SRC_COMMIT_URL
def call(Map args = [:]) {
    def maxChanges = args.maxChanges ?: 30

    withEnv(["MAX_CHANGES=${maxChanges}"]) {
        sh '''
            set -e
            git log -1 --pretty=format:'%H'   > /tmp/src_sha
            git log -1 --pretty=format:'%h'   > /tmp/src_short
            git log -1 --pretty=format:'%an'  > /tmp/src_author
            git log -1 --pretty=format:'%ae'  > /tmp/src_email
            git log -1 --pretty=format:'%s'   > /tmp/src_subject
            git log -1 --pretty=format:'%cI'  > /tmp/src_date
            git log -1 --pretty=format:'%b'   > /tmp/src_body
            git diff --stat HEAD~1..HEAD 2>/dev/null | tail -n ${MAX_CHANGES} > /tmp/src_changes \
                || echo '(initial commit)' > /tmp/src_changes
            git remote get-url origin 2>/dev/null | sed -E 's#\\.git$##' > /tmp/src_repo_url \
                || echo '' > /tmp/src_repo_url
        '''
    }

    env.SRC_SHA      = readFile('/tmp/src_sha').trim()
    env.SRC_SHORT    = readFile('/tmp/src_short').trim()
    env.SRC_AUTHOR   = readFile('/tmp/src_author').trim()
    env.SRC_EMAIL    = readFile('/tmp/src_email').trim()
    env.SRC_SUBJECT  = readFile('/tmp/src_subject').trim()
    env.SRC_DATE     = readFile('/tmp/src_date').trim()
    env.SRC_BODY     = readFile('/tmp/src_body').trim()
    env.SRC_CHANGES  = readFile('/tmp/src_changes').trim()
    env.SRC_REPO_URL = readFile('/tmp/src_repo_url').trim()
    env.SRC_BRANCH   = env.GIT_BRANCH ?: env.BRANCH_NAME ?: 'unknown'
    env.SRC_COMMIT_URL = env.SRC_REPO_URL ? "${env.SRC_REPO_URL}/-/commit/${env.SRC_SHA}" : ''

    echo "Source: ${env.SRC_SHORT} on ${env.SRC_BRANCH} by ${env.SRC_AUTHOR} — ${env.SRC_SUBJECT}"
}
