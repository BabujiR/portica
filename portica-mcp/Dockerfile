# Build the jar first:  mvn -pl portica-mcp -am package -DskipTests
FROM eclipse-temurin:21-jre-alpine

RUN addgroup -S portica && adduser -S portica -G portica
WORKDIR /app
COPY target/portica-mcp-*.jar app.jar
USER portica

EXPOSE 8083
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/app.jar"]
