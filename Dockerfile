# syntax=docker/dockerfile:1

# ---- build ----------------------------------------------------------------
FROM maven:3.9-eclipse-temurin-26 AS build
WORKDIR /build

# Resolve dependencies in their own layer so source edits don't re-download them.
COPY pom.xml ./
RUN --mount=type=cache,target=/root/.m2 mvn -B dependency:go-offline

COPY src ./src
RUN --mount=type=cache,target=/root/.m2 mvn -B clean package -DskipTests

# ---- runtime --------------------------------------------------------------
FROM eclipse-temurin:26-jre-alpine
WORKDIR /app

RUN adduser -S -u 10001 bot
COPY --from=build /build/target/telegram-test-1.jar /app/app.jar
USER bot

# Long-polling bot: no ports exposed, no healthcheck endpoint.
# MaxRAMPercentage keeps the heap inside whatever limit compose sets.
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75.0"
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
