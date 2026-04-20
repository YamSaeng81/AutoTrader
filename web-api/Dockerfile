FROM gradle:8.7-jdk17-alpine AS builder

WORKDIR /app
COPY . .
RUN gradle :web-api:bootJar -x test --no-daemon

FROM eclipse-temurin:17-jre-alpine

WORKDIR /app
COPY --from=builder /app/web-api/build/libs/*.jar app.jar

EXPOSE 8080

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]
