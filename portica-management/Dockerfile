# Build the jar first:  mvn -pl portica-management -am package -DskipTests
FROM eclipse-temurin:21-jre-alpine

RUN addgroup -S portica && adduser -S portica -G portica
WORKDIR /app
COPY target/portica-management-*.jar app.jar
USER portica

EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/app.jar"]
