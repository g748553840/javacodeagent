# Stage 1: build the JAR (use official Maven image to avoid installing maven via apk)
FROM maven:3.9-eclipse-temurin-21-alpine AS build
WORKDIR /build
COPY pom.xml .
# Pre-download dependencies (layer-cache friendly — only re-runs when pom.xml changes)
RUN mvn dependency:go-offline -q
COPY src ./src
RUN mvn clean package -DskipTests -q

# Stage 2: minimal JRE runtime image
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
# Run as non-root user
RUN addgroup -S appgroup && adduser -S appuser -G appgroup
COPY --from=build /build/target/java-code-agent-*.jar app.jar
RUN chown appuser:appgroup app.jar
USER appuser
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
