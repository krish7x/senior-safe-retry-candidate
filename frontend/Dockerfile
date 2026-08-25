# syntax=docker/dockerfile:1.7
FROM node:22-alpine AS build

WORKDIR /app
COPY package.json package-lock.json ./
RUN --mount=type=cache,target=/root/.npm npm ci

COPY index.html vite.config.js ./
COPY src ./src
RUN npm run build

FROM nginxinc/nginx-unprivileged:1.27.4-alpine AS runtime

COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build --chown=101:101 /app/dist /usr/share/nginx/html

USER 101:101
EXPOSE 8080

HEALTHCHECK --interval=10s --timeout=3s --start-period=5s --retries=3 \
  CMD wget -q --spider http://127.0.0.1:8080/healthz || exit 1

CMD ["nginx", "-g", "daemon off;"]
