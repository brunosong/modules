# 단점 재현용: 이 이미지를 K8s Job 으로 띄우면
#  (a) ENV 누락 → 파드 CrashLoopBackOff 인데 어떤 알림도 가지 않는다
#  (b) backoffLimit > 0 이면 실패 시마다 알림이 N번 발사된다
FROM eclipse-temurin:21-jre

WORKDIR /app
COPY build/libs/*.jar app.jar
ENTRYPOINT ["java", "-jar", "/app/app.jar"]