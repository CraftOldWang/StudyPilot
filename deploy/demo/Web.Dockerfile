FROM node:22-alpine AS build
WORKDIR /build
COPY frontend/package.json frontend/package-lock.json ./
RUN npm ci
COPY frontend/ ./
ENV VITE_DEMO_MODE=true
RUN npm run build

FROM caddy:2-alpine
COPY --from=build /build/dist /srv
COPY deploy/demo/Caddyfile /etc/caddy/Caddyfile
