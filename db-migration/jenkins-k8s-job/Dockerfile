FROM eclipse-temurin:21-jre-alpine

WORKDIR /app

COPY build/libs/jenkins-k8s-job-1.0-SNAPSHOT.jar app.jar

ENTRYPOINT ["java", "-jar", "app.jar"]
