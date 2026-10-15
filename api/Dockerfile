# 多阶段构建：产物 ~15MB，零运行时依赖
FROM golang:1.23-alpine AS build
ENV GOPROXY=https://goproxy.cn,direct
WORKDIR /src
COPY . .
RUN go mod tidy && CGO_ENABLED=0 go build -ldflags="-s -w" -o /teslamate-api .

FROM alpine:3.20
RUN apk add --no-cache tzdata ca-certificates
COPY --from=build /teslamate-api /usr/local/bin/teslamate-api
EXPOSE 8090
ENTRYPOINT ["/usr/local/bin/teslamate-api"]
