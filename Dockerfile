FROM gradle:8.8.0-jdk21 AS build

WORKDIR /home/gradle/project

COPY build.gradle.kts gradlew ./
COPY gradle/ gradle/

COPY src/ src/

RUN chmod +x ./gradlew

RUN ./gradlew shadowJar

FROM eclipse-temurin:21-jre-jammy

WORKDIR /app

COPY --from=build /home/gradle/project/build/libs/*.jar bot.jar

CMD ["java", "-jar", "bot.jar"]
