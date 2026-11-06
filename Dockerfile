# Build stage. The pom is copied first so the dependency layer is cached between builds.
FROM maven:3.9-eclipse-temurin-11 AS build
WORKDIR /build
COPY pom.xml .
RUN mvn -B -q dependency:go-offline
COPY src ./src
RUN mvn -B -q package -DskipTests

# Runtime stage
FROM eclipse-temurin:11-jre
WORKDIR /app
COPY --from=build /build/target/cfp.jar app.jar
# Scaleway's database needs TLS; the Compose Postgres doesn't, so application.properties defaults to off.
ENV PGSSLMODE=require
# GET /health reports this as its version: docker build --build-arg GIT_SHA=$(git rev-parse HEAD) .
ARG GIT_SHA=unknown
ENV GIT_SHA=$GIT_SHA
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
