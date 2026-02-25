# 基础镜像，建议使用与您编译时相同的 JDK 版本对应的 JRE
FROM openjdk:11-jre-slim

# 设定时区
ENV TZ=Asia/Shanghai
RUN ln -snf /usr/share/zoneinfo/$TZ /etc/localtime && echo $TZ > /etc/timezone

COPY target/*.jar app.jar
# sky-server-1.0-SNAPSHOT.jar

# 入口点 (保持不变)
ENTRYPOINT ["java", "-jar", "/app.jar"]
