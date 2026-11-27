# syntax=docker/dockerfile:1.7

FROM node:24-alpine AS builder
WORKDIR /workspace

COPY package.json package-lock.json ./
RUN --mount=type=cache,target=/root/.npm npm ci

COPY tsconfig.json tsconfig.app.json tsconfig.node.json vite.config.ts index.html ./
COPY src ./src
RUN npm run build

FROM nginx:1.29-alpine AS production

LABEL org.opencontainers.image.title="SupportForge Console" \
      org.opencontainers.image.description="Enterprise customer support web console"

COPY nginx.conf /etc/nginx/nginx.conf
COPY --from=builder /workspace/dist /usr/share/nginx/html

RUN chown -R nginx:nginx /usr/share/nginx/html
USER nginx

EXPOSE 8080

HEALTHCHECK --interval=30s --timeout=4s --start-period=10s --retries=3 \
  CMD wget -q --spider http://127.0.0.1:8080/healthz || exit 1

ENTRYPOINT ["nginx", "-g", "daemon off;"]
