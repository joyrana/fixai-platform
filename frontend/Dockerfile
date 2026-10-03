# Behind a TLS-intercepting corporate proxy: docker build --secret id=corp_ca,src=/path/to/ca.pem ...
FROM node:22-alpine AS build
WORKDIR /src
COPY package.json package-lock.json ./
RUN --mount=type=secret,id=corp_ca,required=false \
    if [ -f /run/secrets/corp_ca ]; then export NODE_EXTRA_CA_CERTS=/run/secrets/corp_ca; fi \
    && npm ci --no-audit --no-fund
COPY . .
RUN npm run build

FROM nginxinc/nginx-unprivileged:1.27-alpine
COPY nginx.conf.template /etc/nginx/templates/default.conf.template
COPY --from=build /src/dist /usr/share/nginx/html
ENV BROKER_URL=http://broker-service:8080 CERTIFICATION_URL=http://certification-service:8080 \
    WORKFLOW_URL=http://workflow-service:8080 SIMULATOR_URL=http://fix-simulator:8080 \
    ORCHESTRATOR_URL=http://agent-orchestrator:8100 NGINX_ENVSUBST_FILTER="_URL$"
EXPOSE 8080
HEALTHCHECK --interval=10s --timeout=3s CMD wget -qO- http://127.0.0.1:8080/healthz || exit 1
