FROM node:22.18-alpine AS build
WORKDIR /app
COPY Frontend/package.json Frontend/package-lock.json ./
RUN npm ci
COPY Frontend/ .
COPY Service/api/openapi.yaml /Service/api/openapi.yaml
RUN npm run api:generate && npm run build

FROM nginxinc/nginx-unprivileged:1.29-alpine
COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /app/dist /usr/share/nginx/html
EXPOSE 8080
