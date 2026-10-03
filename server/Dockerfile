# ---- build stage ----
FROM eclipse-temurin:25-jdk AS build
WORKDIR /app

# Build files and the Gradle wrapper
COPY gradlew settings.gradle.kts build.gradle.kts gradle.properties ./
COPY gradle ./gradle

# Drop the Android/Compose modules inside the image only (no Android SDK here)
RUN chmod +x gradlew \
 && sed -i '/include(":composeApp")/d;/include(":androidApp")/d' settings.gradle.kts

# Server sources, then build the fat jar
COPY server ./server
RUN ./gradlew :server:buildFatJar --no-daemon -Dorg.gradle.jvmargs="-Xmx2g"

# ---- runtime stage ----
# ---- runtime stage ----
FROM eclipse-temurin:25-jre
WORKDIR /app
RUN useradd --system --uid 1001 divvy
COPY --from=build /app/server/build/libs/*-all.jar app.jar
USER divvy
EXPOSE 8080
ENTRYPOINT ["java", "--enable-native-access=ALL-UNNAMED", "-XX:MaxRAMPercentage=75", "-jar", "app.jar"]