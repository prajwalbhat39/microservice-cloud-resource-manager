# syntax=docker/dockerfile:1
# -----------------------------------------------------------------------------
# Multi-stage build for the Quota service (Spring Boot fat jar, Java 21).
#   Stage 1 (build): compile and package inside a Maven+JDK image.
#   Stage 2 (run):   copy ONLY the jar into a small JRE image.
# Build context is this service directory; the service builds standalone
# (inherits spring-boot-starter-parent directly), so no other module is needed.
# -----------------------------------------------------------------------------
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build
# Copy the build descriptor first, then sources. The --mount cache shares the
# downloaded Maven dependencies across rebuilds and across all seven services.
COPY pom.xml .
COPY src ./src
RUN --mount=type=cache,target=/root/.m2 mvn -B -ntp -DskipTests clean package

FROM eclipse-temurin:21-jre
RUN apt-get update && apt-get install -y --no-install-recommends curl && rm -rf /var/lib/apt/lists/*
WORKDIR /app
# Wildcard copy tolerates either naming scheme (<artifactId>-<version>.jar here,
# <finalName>.jar for the U4 services).
COPY --from=build /build/target/*.jar app.jar
# Low heap: seven JVMs + Postgres share a memory-tight host.
ENV JAVA_TOOL_OPTIONS="-Xmx256m"
EXPOSE 8081
ENTRYPOINT ["java","-jar","/app/app.jar"]
