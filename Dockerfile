FROM maven:3.9.5-eclipse-temurin-17 AS build
WORKDIR /app

COPY backend/pom.xml .
RUN mvn -B dependency:go-offline

COPY backend/src ./src
RUN mvn -B package -DskipTests

FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=build /app/target/banking-system-backend-1.0.0.jar ./app.jar
COPY frontend ./frontend
EXPOSE 8080
ENV FRONTEND_DIR=/app/frontend
CMD ["java", "-jar", "app.jar"]
