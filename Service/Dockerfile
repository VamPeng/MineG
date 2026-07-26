# syntax=docker/dockerfile:1.7
FROM golang:1.26.5-alpine AS build
WORKDIR /src
COPY go.mod go.sum ./
RUN go mod download
COPY . .
RUN CGO_ENABLED=0 go build -trimpath -ldflags="-s -w" -o /out/mineg-api ./cmd/api
RUN CGO_ENABLED=0 go build -trimpath -ldflags="-s -w" -o /out/mineg-migrate ./cmd/migrate

FROM gcr.io/distroless/static-debian12:nonroot
WORKDIR /app
COPY --from=build /out/mineg-api /app/mineg-api
COPY --from=build /out/mineg-migrate /app/mineg-migrate
COPY --from=build /src/migrations /app/migrations
ENV MINEG_MIGRATIONS_DIR=/app/migrations
EXPOSE 8080
USER nonroot:nonroot
ENTRYPOINT ["/app/mineg-api"]
