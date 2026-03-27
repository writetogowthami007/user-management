FROM gradle:8.10.2-jdk17 AS builder
WORKDIR /app
COPY . .
RUN gradle clean bootJar -x test --no-daemon

FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=builder /app/build/libs/*.jar /app/app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
