# syntax=docker/dockerfile:1

# ---- build stage: compile + package the fat jar ----
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build
# Cache dependencies first (changes to src don't re-download the world)
COPY pom.xml .
RUN mvn -B -ntp dependency:go-offline
COPY src ./src
# Tests run in CI; the image build just packages
RUN mvn -B -ntp -DskipTests clean package \
    && cp target/*.jar /build/app.jar

# ---- runtime stage: JRE only, non-root ----
FROM eclipse-temurin:21-jre
RUN useradd -r -u 1001 veritas
WORKDIR /app
COPY --from=build /build/app.jar /app/app.jar
RUN chown -R veritas:veritas /app
# Bind to all interfaces inside the container (the container network is the boundary).
# Defaults: mock LLM + embedded SQLite. For a server DB run with SPRING_PROFILES_ACTIVE=postgres.
ENV SERVER_ADDRESS=0.0.0.0
EXPOSE 8080
USER veritas
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
CMD ["serve"]
