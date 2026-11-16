# Build the consumer as a self-contained JAR.
FROM maven:3.9.9-eclipse-temurin-21 AS build
WORKDIR /app
COPY pom.xml .
COPY src ./src
RUN mvn -q -DskipTests clean package

# FFmpeg is needed at runtime for video compression.
FROM eclipse-temurin:21-jre
RUN apt-get update \
    && apt-get install -y --no-install-recommends ffmpeg \
    && rm -rf /var/lib/apt/lists/*
WORKDIR /app
COPY --from=build /app/target/consumer-1.0.0.jar /app/app.jar
COPY config.properties /app/config.properties
EXPOSE 50051 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar", "/app/config.properties"]
