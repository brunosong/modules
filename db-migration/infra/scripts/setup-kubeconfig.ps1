# ─────────────────────────────────────────────────────────────
# Jenkins 컨테이너에 K8s 클러스터 접속 정보를 등록하는 스크립트
# 환경: Windows + Docker Desktop + Kind
#
# 현재는 ~/.kube 볼륨 마운트 방식을 사용하고 있어 이 스크립트는 불필요.
# 운영 환경(NKS 등)처럼 set-cluster 방식으로 전환할 때 사용한다.
#
# 실제 운영 환경에서의 클러스터 접근 방식:
#   1. 클러스터 CA 인증서
#   2. 클라이언트 인증서 + 키 (또는 토큰)
#   3. API Server 주소
# ─────────────────────────────────────────────────────────────

$ErrorActionPreference = "Stop"

$CLUSTER_NAME = "db-migration"
$CONTEXT_NAME = "kind-$CLUSTER_NAME"
$JENKINS_CONTAINER = "jenkins-k8s"
$CERT_DIR = "/var/jenkins_home/.kube/certs"
$TEMP_DIR = "$env:TEMP\kind-certs"

# 임시 디렉토리 생성
if (Test-Path $TEMP_DIR) { Remove-Item -Recurse -Force $TEMP_DIR }
New-Item -ItemType Directory -Path $TEMP_DIR | Out-Null

Write-Host "=== 1. Kind 클러스터에서 인증서 추출 ===" -ForegroundColor Green

# Kind kubeconfig 가져오기
$kubeconfig = kind get kubeconfig --name $CLUSTER_NAME
$kubeconfig | Out-File -FilePath "$TEMP_DIR\kubeconfig.yml" -Encoding UTF8

# YAML에서 인증서 데이터 추출 (base64 인코딩 상태)
$caData = ($kubeconfig | Select-String "certificate-authority-data:").ToString().Trim().Split(" ")[-1]
$clientCertData = ($kubeconfig | Select-String "client-certificate-data:").ToString().Trim().Split(" ")[-1]
$clientKeyData = ($kubeconfig | Select-String "client-key-data:").ToString().Trim().Split(" ")[-1]

# base64 디코딩하여 파일로 저장
[System.IO.File]::WriteAllBytes("$TEMP_DIR\ca.crt", [System.Convert]::FromBase64String($caData))
[System.IO.File]::WriteAllBytes("$TEMP_DIR\client.crt", [System.Convert]::FromBase64String($clientCertData))
[System.IO.File]::WriteAllBytes("$TEMP_DIR\client.key", [System.Convert]::FromBase64String($clientKeyData))

Write-Host "   인증서 추출 완료" -ForegroundColor Gray

Write-Host "=== 2. API Server 주소 확인 ===" -ForegroundColor Green

# API Server 주소 추출 (예: https://127.0.0.1:62345)
$serverLine = ($kubeconfig | Select-String "server:").ToString().Trim()
$originalServer = $serverLine.Split(" ")[-1]

# 포트 번호 추출
$uri = [System.Uri]$originalServer
$apiPort = $uri.Port

# Jenkins 컨테이너에서 호스트에 접근할 주소
$apiServer = "https://host.docker.internal:$apiPort"

Write-Host "   원본: $originalServer" -ForegroundColor Gray
Write-Host "   Jenkins에서 접근할 주소: $apiServer" -ForegroundColor Gray

Write-Host "=== 3. Jenkins 컨테이너에 인증서 복사 ===" -ForegroundColor Green

docker exec $JENKINS_CONTAINER mkdir -p $CERT_DIR
docker cp "$TEMP_DIR\ca.crt" "${JENKINS_CONTAINER}:${CERT_DIR}/ca.crt"
docker cp "$TEMP_DIR\client.crt" "${JENKINS_CONTAINER}:${CERT_DIR}/client.crt"
docker cp "$TEMP_DIR\client.key" "${JENKINS_CONTAINER}:${CERT_DIR}/client.key"

Write-Host "   인증서 복사 완료" -ForegroundColor Gray

Write-Host "=== 4. kubectl config 등록 (set-cluster / set-credentials / set-context) ===" -ForegroundColor Green

# 클러스터 등록
docker exec $JENKINS_CONTAINER kubectl config set-cluster $CONTEXT_NAME `
    --server=$apiServer `
    --certificate-authority="$CERT_DIR/ca.crt" `
    --embed-certs=true

# 사용자 인증 정보 등록
docker exec $JENKINS_CONTAINER kubectl config set-credentials $CONTEXT_NAME `
    --client-certificate="$CERT_DIR/client.crt" `
    --client-key="$CERT_DIR/client.key" `
    --embed-certs=true

# 컨텍스트 등록 (클러스터 + 사용자 연결)
docker exec $JENKINS_CONTAINER kubectl config set-context $CONTEXT_NAME `
    --cluster=$CONTEXT_NAME `
    --user=$CONTEXT_NAME

# 현재 컨텍스트로 설정
docker exec $JENKINS_CONTAINER kubectl config use-context $CONTEXT_NAME

Write-Host "=== 5. 연결 확인 ===" -ForegroundColor Green

docker exec $JENKINS_CONTAINER kubectl cluster-info
docker exec $JENKINS_CONTAINER kubectl get nodes

Write-Host ""
Write-Host "=== 완료! Jenkins에서 K8s 클러스터에 접근할 수 있습니다 ===" -ForegroundColor Green

# 임시 파일 정리
Remove-Item -Recurse -Force $TEMP_DIR
