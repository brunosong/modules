# ─────────────────────────────────────────────────────────
# Kind kubeconfig를 Jenkins용으로 생성하는 스크립트
#
# 호스트의 ~/.kube/config는 건드리지 않는다.
# Kind kubeconfig를 별도 파일(kubeconfig)로 추출한 뒤:
#   1) API Server 주소: 127.0.0.1 → host.docker.internal
#   2) TLS 검증 스킵: certificate-authority-data 제거 + insecure-skip-tls-verify 추가
#      (Kind 인증서에 host.docker.internal이 등록되어 있지 않아 TLS 검증 실패 방지)
# ─────────────────────────────────────────────────────────

$ErrorActionPreference = "Stop"

$CLUSTER_NAME = "db-migration"
$OUTPUT_FILE = "kubeconfig"

Write-Host "=== Kind kubeconfig 추출 ===" -ForegroundColor Green
kind get kubeconfig --name $CLUSTER_NAME | Out-File -FilePath $OUTPUT_FILE -Encoding ASCII

Write-Host "=== 127.0.0.1 → host.docker.internal 변경 ===" -ForegroundColor Green
(Get-Content $OUTPUT_FILE) -replace '127\.0\.0\.1', 'host.docker.internal' | Set-Content $OUTPUT_FILE -Encoding ASCII

Write-Host "=== TLS 검증 스킵 설정 ===" -ForegroundColor Green
# certificate-authority-data 라인을 insecure-skip-tls-verify: true 로 교체
$lines = Get-Content $OUTPUT_FILE
$result = foreach ($line in $lines) {
    if ($line -match '^\s*certificate-authority-data:') {
        $indent = $line -replace '\S.*', ''
        "${indent}insecure-skip-tls-verify: true"
    } else {
        $line
    }
}
$result | Set-Content $OUTPUT_FILE -Encoding ASCII

Write-Host "=== 생성 완료 ===" -ForegroundColor Green
Write-Host "파일: $OUTPUT_FILE"
Select-String -Path $OUTPUT_FILE -Pattern "server:|insecure-skip-tls-verify:"

Write-Host ""
Write-Host "이제 Jenkins 컨테이너를 재시작하세요:" -ForegroundColor Yellow
Write-Host "  docker restart jenkins-k8s" -ForegroundColor Yellow
