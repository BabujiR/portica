# Build the static console, then serve it with nginx. Backend URLs are set at container start
# (PORTICA_* variables below), so one image works in every environment.
FROM node:22-alpine AS build
WORKDIR /app
COPY package.json package-lock.json* ./
RUN if [ -f package-lock.json ]; then npm ci; else npm install; fi
COPY . .
RUN npm run build

FROM nginx:1.27-alpine
COPY docker/nginx.conf /etc/nginx/conf.d/default.conf
COPY docker/40-portica-config.sh /docker-entrypoint.d/40-portica-config.sh
RUN chmod +x /docker-entrypoint.d/40-portica-config.sh
COPY --from=build /app/dist /usr/share/nginx/html
ENV PORTICA_API_BASE_URL=http://localhost:8080 \
    PORTICA_GATEWAY_URL=http://localhost:8081 \
    PORTICA_PORTAL_API_URL=http://localhost:8085 \
    PORTICA_OIDC_AUTHORITY=http://localhost:8180/realms/portica \
    PORTICA_OIDC_CLIENT_ID=portica-console
EXPOSE 80
