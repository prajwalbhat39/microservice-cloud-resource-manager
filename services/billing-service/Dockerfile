# syntax=docker/dockerfile:1
# Multi-stage build for the Billing service (Spring Boot, Java 21).
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build
COPY pom.xml .
COPY src ./src
RUN --mount=type=cache,target=/root/.m2 mvn -B -ntp -DskipTests clean package

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /build/target/*.jar app.jar
ENV JAVA_TOOL_OPTIONS="-Xmx256m"
EXPOSE 8083
ENTRYPOINT ["java","-jar","/app/app.jar"]
