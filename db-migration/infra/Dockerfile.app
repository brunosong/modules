# Spring Boot Flyway 앱 공용 이미지.
# 모든 db-migration 시나리오 (jenkins-direct / jenkins-k8s-job /
# argocd-gitops / argocd-helm / jenkins-gitlab-mr) 가 이 한 파일을 사용.
#
# 사용 예 (프로젝트 루트에서):
#   ./gradlew :db-migration:argocd-gitops:bootJar
#   docker build \
#     -f db-migration/infra/Dockerfile.app \
#     -t db-migration-argocd:latest \
#     db-migration/argocd-gitops
#
# build context = 시나리오 모듈 디렉토리 (build/libs/*.jar 가 있는 곳).
# 시나리오마다 jar 이름이 다르므로 와일드카드로 받는다.
FROM eclipse-temurin:21-jre-alpine

WORKDIR /app

ARG JAR_FILE=build/libs/*.jar
COPY ${JAR_FILE} app.jar

ENTRYPOINT ["java", "-jar", "app.jar"]
