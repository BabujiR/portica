# Build the jar first:  mvn -pl portica-portal -am package -DskipTests
FROM eclipse-temurin:21-jre-alpine

RUN addgroup -S portica && adduser -S portica -G portica
WORKDIR /app
COPY target/portica-portal-*.jar app.jar
USER portica

EXPOSE 8085
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/app.jar"]
