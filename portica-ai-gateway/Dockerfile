# Build the jar first:  mvn -pl portica-ai-gateway -am package -DskipTests
FROM eclipse-temurin:21-jre-alpine

RUN addgroup -S portica && adduser -S portica -G portica
WORKDIR /app
COPY target/portica-ai-gateway-*.jar app.jar
USER portica

EXPOSE 8082
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/app.jar"]
