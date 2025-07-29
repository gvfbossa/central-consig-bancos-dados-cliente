# Etapa de build
FROM maven:3.9.6-eclipse-temurin-21 AS build

WORKDIR /app

# Copia o código do microsserviço
COPY . .

# Copia o JAR da lib core (gerado localmente)
COPY libs/central-consig-bancos-core-1.2.2.jar /root/libs/

# Instala a lib no repositório local do Maven no container
RUN mvn install:install-file \
  -Dfile=/root/libs/central-consig-bancos-core-1.2.2.jar \
  -DgroupId=com.centralconsig.core \
  -DartifactId=central-consig-bancos-core \
  -Dversion=1.2.2 \
  -Dpackaging=jar

# Compila o microsserviço
RUN mvn clean package -DskipTests

# Etapa de execução
FROM selenium/standalone-chrome:latest

WORKDIR /app

# Copia o JAR final
COPY --from=build /app/target/central-consig-bancos-dados-cliente-*.jar /app/app.jar

# Cria o diretório de logs e garante permissões
RUN mkdir -p /app/logs && chmod -R 777 /app/logs

EXPOSE 8080

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
