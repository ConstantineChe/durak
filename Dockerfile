FROM java:8-alpine
MAINTAINER Your Name <you@example.com>

ADD target/uberjar/durak.jar /durak/app.jar

EXPOSE 3000

CMD ["java", "-jar", "/durak/app.jar"]
