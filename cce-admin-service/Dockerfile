# Multi-stage image of cce-admin-service. Versions from D-05.
FROM maven:3.9.16-eclipse-temurin-21 AS build
WORKDIR /build
COPY pom.xml .
COPY src ./src
RUN mvn -B -DskipTests package

FROM eclipse-temurin:21.0.13_9-jre-alpine
RUN addgroup -S app && adduser -S -G app app
WORKDIR /app
COPY --from=build /build/target/cce-admin-service-0.1.0-SNAPSHOT.jar /app/app.jar
USER app
EXPOSE 8085
HEALTHCHECK CMD wget -qO- http://localhost:8085/admin/v1/health || exit 1
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
