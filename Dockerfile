# Etapa de build com Maven + Java 21
FROM maven:3.9.6-eclipse-temurin-21 AS build

WORKDIR /app
COPY . .
RUN mvn clean package -DskipTests

# Etapa de execução com Chrome + Selenium
FROM selenium/standalone-chrome:latest

WORKDIR /app

# Copia o JAR gerado
COPY --from=build /app/target/central-consig-bancos-dados-cliente-*.jar /app/app.jar

# Exposição da porta
EXPOSE 8080

# Comando de execução
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
