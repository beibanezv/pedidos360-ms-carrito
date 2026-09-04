# ms-carrito — imagen para EC2/ECR
# Build: compila y corre los tests dentro de la imagen.

FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /app
COPY pom.xml .
RUN mvn -q dependency:go-offline
COPY src ./src
RUN mvn -q package

FROM eclipse-temurin:17-jre
RUN useradd -r -u 1001 -m app
WORKDIR /app
COPY --from=build /app/target/*.jar app.jar
USER app
EXPOSE 8082
ENTRYPOINT ["java", "-jar", "/app/app.jar"]