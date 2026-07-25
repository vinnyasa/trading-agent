# ── Build stage ───────────────────────────────────────────────────────────────
FROM gradle:8.5.0-jdk17 AS build
WORKDIR /app
COPY . .
RUN gradle jar --no-daemon

# ── Runtime stage ─────────────────────────────────────────────────────────────
FROM eclipse-temurin:17-jre-alpine AS runtime
WORKDIR /app
COPY --from=build /app/build/libs/market-rotation-tracker-1.0.0.jar app.jar
# reports/ is written at runtime — mount as a volume in docker-compose if persistence needed
ENTRYPOINT ["java", "-jar", "app.jar"]
