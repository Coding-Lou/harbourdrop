ARG JAVA_RUNTIME_IMAGE=eclipse-temurin:21-jre
FROM ${JAVA_RUNTIME_IMAGE}
WORKDIR /app
COPY app.jar /app/app.jar
EXPOSE 8081
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
