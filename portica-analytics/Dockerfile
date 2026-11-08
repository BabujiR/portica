# Build the jar first:  mvn -pl portica-analytics -am package -DskipTests
FROM eclipse-temurin:21-jre-alpine

RUN addgroup -S portica && adduser -S portica -G portica
WORKDIR /app
COPY target/portica-analytics-*.jar app.jar
USER portica

EXPOSE 8084
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/app.jar"]
