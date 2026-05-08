FROM golang:1.21-alpine AS builder
WORKDIR /app
COPY . .
RUN go mod tidy && go build -o server .

FROM alpine:latest
WORKDIR /root/
COPY --from=builder /app/server .
EXPOSE 8443
CMD ["./server"]
