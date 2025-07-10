FROM gradle:8.8.0-jdk17 AS builder

WORKDIR /home/gradle/project
COPY . .

RUN gradle build

FROM debian:bullseye-slim

WORKDIR /app

COPY --from=builder /home/gradle/project/build/bin/native/releaseExecutable/p2pblockchainchat.kexe .

EXPOSE 51511/tcp

ENTRYPOINT ["./p2pblockchainchat.kexe"]