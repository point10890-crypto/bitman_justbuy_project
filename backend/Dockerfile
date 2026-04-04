FROM eclipse-temurin:21-jdk-alpine AS build
WORKDIR /app
COPY gradle/ gradle/
COPY gradlew build.gradle.kts settings.gradle.kts ./
RUN chmod +x gradlew && ./gradlew dependencies --no-daemon || true
COPY src/ src/
RUN ./gradlew bootJar --no-daemon -x test

FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY --from=build /app/build/libs/*.jar app.jar
RUN mkdir -p /app/data
EXPOSE 8080
ENV H2_CONSOLE_ENABLED=false
ENV LANG=C.UTF-8
ENV LC_ALL=C.UTF-8
ENTRYPOINT ["java", "-Dfile.encoding=UTF-8", "-Dsun.jvm.stdout.encoding=UTF-8", "-Dsun.jvm.stderr.encoding=UTF-8", "-jar", "app.jar"]
