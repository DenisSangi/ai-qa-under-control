# Simple runtime image: build the jar on the host first (mvn -pl analytics-service -am package)
FROM eclipse-temurin:17-jre
WORKDIR /app
COPY target/analytics-service-*.jar app.jar
EXPOSE 8081 6565
ENTRYPOINT ["java", "-jar", "app.jar"]
