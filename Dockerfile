# ---- Build stage ----
FROM maven:3.9-eclipse-temurin-21-alpine AS build
WORKDIR /app

# Copy POM files first for dependency layer caching
COPY cycles-protocol-service/pom.xml cycles-protocol-service/pom.xml
COPY cycles-protocol-service/cycles-protocol-service-model/pom.xml cycles-protocol-service/cycles-protocol-service-model/pom.xml
COPY cycles-protocol-service/cycles-protocol-service-data/pom.xml cycles-protocol-service/cycles-protocol-service-data/pom.xml
COPY cycles-protocol-service/cycles-protocol-service-api/pom.xml cycles-protocol-service/cycles-protocol-service-api/pom.xml

# Download dependencies (cached unless POMs change)
RUN mvn -f cycles-protocol-service/pom.xml dependency:go-offline -B

# Copy source and build
COPY cycles-protocol-service/ cycles-protocol-service/
RUN mvn -f cycles-protocol-service/pom.xml clean package -DskipTests -B

# ---- Runtime stage ----
FROM eclipse-temurin:21-jre-alpine

ARG APP_VERSION=0.0.0

LABEL org.opencontainers.image.title="cycles-server" \
      org.opencontainers.image.description="Cycles Protocol budget authority server" \
      org.opencontainers.image.source="https://github.com/runcycles/cycles-server" \
      org.opencontainers.image.licenses="Apache-2.0" \
      org.opencontainers.image.version="${APP_VERSION}"

WORKDIR /app
COPY --from=build /app/cycles-protocol-service/cycles-protocol-service-api/target/cycles-protocol-service-api-*.jar app.jar

EXPOSE 7878
ENTRYPOINT ["java", "-jar", "app.jar"]
