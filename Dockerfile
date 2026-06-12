# ---- Builder ----
FROM gradle:8.5-jdk21 AS builder
WORKDIR /app
COPY build.gradle.kts settings.gradle.kts ./
COPY src ./src
RUN gradle bootJar --no-daemon

# ---- Runtime ----
FROM eclipse-temurin:21-jre
WORKDIR /app

# JavaCV нужны системные либы для FFmpeg
RUN apt-get update && apt-get install -y --no-install-recommends \
    libgomp1 \
    && rm -rf /var/lib/apt/lists/*

COPY --from=builder /app/build/libs/*.jar app.jar

# Папка с моделями (скачаются при первом запуске)
RUN mkdir -p /app/models /app/videos /app/ftp/incoming /app/ftp/processed

EXPOSE 8080

ENV JAVA_OPTS="-Xms256m -Xmx2g"
ENV PC_MODELS_DIR=/app/models

ENTRYPOINT ["sh","-c","java $JAVA_OPTS -jar /app/app.jar"]
