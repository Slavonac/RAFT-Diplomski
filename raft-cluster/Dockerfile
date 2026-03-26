FROM maven:3.9-eclipse-temurin-23 AS build
WORKDIR /app
COPY pom.xml .
RUN mvn dependency:go-offline -q
COPY src ./src
RUN mvn package -DskipTests -q

FROM eclipse-temurin:23-jdk
WORKDIR /app
COPY --from=build /app/target/RaftDiplomski-1.0-SNAPSHOT.jar app.jar
COPY io ./io
EXPOSE 9000 9001 9002 9003 9004
ENTRYPOINT ["java", "-jar", "app.jar"]
