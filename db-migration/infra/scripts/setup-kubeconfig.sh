#!/bin/bash
# ─────────────────────────────────────────────────────────────
# Jenkins 컨테이너에 K8s 클러스터 접속 정보를 등록하는 스크립트
# 환경: Windows Git Bash + Docker Desktop + Kind
#
# 현재는 ~/.kube 볼륨 마운트 방식을 사용하고 있어 이 스크립트는 불필요.
# 운영 환경(NKS 등)처럼 set-cluster 방식으로 전환할 때 사용한다.
#
# 실제 운영 환경에서의 클러스터 접근 방식:
#   1. 클러스터 CA 인증서
#   2. 클라이언트 인증서 + 키 (또는 토큰)
#   3. API Server 주소
# ─────────────────────────────────────────────────────────────

set -e

CLUSTER_NAME="db-migration"
CONTEXT_NAME="kind-${CLUSTER_NAME}"
JENKINS_CONTAINER="jenkins-k8s"
CERT_DIR="/var/jenkins_home/.kube/certs"

# Windows 임시 디렉토리 사용
TEMP_DIR="${TEMP:-/tmp}/kind-certs"
mkdir -p "${TEMP_DIR}"

echo "=== 1. Kind 클러스터에서 인증서 추출 ==="

# Kind kubeconfig 가져오기
kind get kubeconfig --name ${CLUSTER_NAME} > "${TEMP_DIR}/kubeconfig.yml"

# 인증서 데이터 추출 & base64 디코딩
# Git Bash의 base64는 --decode 사용 (Linux의 -d 대신)
grep "certificate-authority-data:" "${TEMP_DIR}/kubeconfig.yml" | awk '{print $2}' | base64 --decode > "${TEMP_DIR}/ca.crt"
grep "client-certificate-data:" "${TEMP_DIR}/kubeconfig.yml" | awk '{print $2}' | base64 --decode > "${TEMP_DIR}/client.crt"
grep "client-key-data:" "${TEMP_DIR}/kubeconfig.yml" | awk '{print $2}' | base64 --decode > "${TEMP_DIR}/client.key"

echo "   인증서 추출 완료"

echo "=== 2. API Server 주소 확인 ==="

# API Server 주소에서 포트 추출
ORIGINAL_SERVER=$(grep "server:" "${TEMP_DIR}/kubeconfig.yml" | awk '{print $2}')
# sed로 포트 추출 (grep -oP 대신, Windows Git Bash 호환)
API_PORT=$(echo "${ORIGINAL_SERVER}" | sed 's/.*:\([0-9]*\)$/\1/')

# Jenkins 컨테이너에서 호스트에 접근할 주소
# Docker Desktop(Windows): host.docker.internal 사용
API_SERVER="https://host.docker.internal:${API_PORT}"

echo "   원본: ${ORIGINAL_SERVER}"
echo "   Jenkins에서 접근할 주소: ${API_SERVER}"

echo "=== 3. Jenkins 컨테이너에 인증서 복사 ==="

docker exec ${JENKINS_CONTAINER} mkdir -p ${CERT_DIR}
docker cp "${TEMP_DIR}/ca.crt" "${JENKINS_CONTAINER}:${CERT_DIR}/ca.crt"
docker cp "${TEMP_DIR}/client.crt" "${JENKINS_CONTAINER}:${CERT_DIR}/client.crt"
docker cp "${TEMP_DIR}/client.key" "${JENKINS_CONTAINER}:${CERT_DIR}/client.key"

echo "   인증서 복사 완료"

echo "=== 4. kubectl config 등록 (set-cluster / set-credentials / set-context) ==="

# 클러스터 등록
docker exec ${JENKINS_CONTAINER} kubectl config set-cluster ${CONTEXT_NAME} \
    --server=${API_SERVER} \
    --certificate-authority=${CERT_DIR}/ca.crt \
    --embed-certs=true

# 사용자 인증 정보 등록
docker exec ${JENKINS_CONTAINER} kubectl config set-credentials ${CONTEXT_NAME} \
    --client-certificate=${CERT_DIR}/client.crt \
    --client-key=${CERT_DIR}/client.key \
    --embed-certs=true

# 컨텍스트 등록 (클러스터 + 사용자 연결)
docker exec ${JENKINS_CONTAINER} kubectl config set-context ${CONTEXT_NAME} \
    --cluster=${CONTEXT_NAME} \
    --user=${CONTEXT_NAME}

# 현재 컨텍스트로 설정
docker exec ${JENKINS_CONTAINER} kubectl config use-context ${CONTEXT_NAME}

echo "=== 5. 연결 확인 ==="

docker exec ${JENKINS_CONTAINER} kubectl cluster-info
docker exec ${JENKINS_CONTAINER} kubectl get nodes

echo ""
echo "=== 완료! Jenkins에서 K8s 클러스터에 접근할 수 있습니다 ==="

# 임시 파일 정리
rm -rf "${TEMP_DIR}"
