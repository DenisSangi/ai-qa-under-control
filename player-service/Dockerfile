# Simple runtime image: build the jar on the host first (mvn -pl player-service -am package)
FROM eclipse-temurin:17-jre
WORKDIR /app
COPY target/player-service-*.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
