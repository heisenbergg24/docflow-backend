# Stage 1: Build the Spring Boot jar
FROM maven:3.9.6-eclipse-temurin-21 AS builder

WORKDIR /app
COPY pom.xml .
COPY src ./src
RUN mvn clean package -DskipTests

# Stage 2: Runtime image
FROM eclipse-temurin:21-jdk-jammy

WORKDIR /app

RUN apt-get update && apt-get install -y \
    ghostscript \
    libreoffice \
    python3 \
    python3-pip \
    python3-venv \
    && rm -rf /var/lib/apt/lists/*

RUN python3 -m venv /opt/venv && \
    /opt/venv/bin/pip install pdf2docx

ENV PATH="/opt/venv/bin:$PATH"

COPY --from=builder /app/target/*.jar app.jar
COPY pdf_to_docx.py .

EXPOSE 8080

ENTRYPOINT ["java", "-jar", "app.jar"]