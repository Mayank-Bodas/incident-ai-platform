# ==========================================
# STAGE 1: Build stage using Maven
# ==========================================
FROM maven:3.9.6-eclipse-temurin-17 AS builder

WORKDIR /build

# Copy only the pom.xml first to resolve dependencies and leverage Docker layer caching
COPY pom.xml .

# Download dependencies offline (highly cacheable layer)
RUN mvn dependency:go-offline -B

# Copy the application source code
COPY src ./src

# Compile and package the application, skipping tests (tests run in CI/CD pipeline)
RUN mvn package -DskipTests -B

# ==========================================
# STAGE 2: Secure and lightweight runtime stage
# ==========================================
FROM eclipse-temurin:17-jre

# Install curl in debian/ubuntu for healthcheck support
RUN apt-get update && apt-get install -y curl && rm -rf /var/lib/apt/lists/*

# Create a non-privileged system group and user to run the application
# Hardening constraint: Never run processes as root in production containers
RUN groupadd -r sregroup && useradd -r -g sregroup sreuser

WORKDIR /app

# Copy the built JAR from the builder stage
COPY --from=builder /build/target/incident-platform-0.0.1-SNAPSHOT.jar app.jar

# Grant ownership to the non-root user
RUN chown -R sreuser:sregroup /app

# Switch to the non-root user
USER sreuser

# Expose the application port (8080)
EXPOSE 8080

# Configure JVM properties for container awareness and memory management
# -XX:+UseG1GC: G1 garbage collector for low latency
# -XX:+ExitOnOutOfMemoryError: crash the container on OOM so Kubernetes can restart it
ENTRYPOINT ["java", "-XX:+UseG1GC", "-XX:+ExitOnOutOfMemoryError", "-jar", "app.jar"]
