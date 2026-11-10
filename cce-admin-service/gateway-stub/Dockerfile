# Image of the local gateway stand-in (AD-11). Build context: cce-admin-service/. Versions from D-05.
FROM maven:3.9.16-eclipse-temurin-21 AS build
WORKDIR /build
COPY gateway-stub/pom.xml gateway-stub/pom.xml
COPY gateway-stub/src gateway-stub/src
RUN mvn -B -f gateway-stub/pom.xml -DskipTests package

FROM eclipse-temurin:21.0.13_9-jre-alpine
RUN addgroup -S app && adduser -S -G app app
WORKDIR /app
COPY --from=build /build/gateway-stub/target/cce-gateway-stub-0.1.0-SNAPSHOT.jar /app/gateway-stub.jar
USER app
EXPOSE 8090
ENTRYPOINT ["java", "-jar", "/app/gateway-stub.jar"]
