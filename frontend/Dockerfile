# syntax=docker/dockerfile:1.7
FROM node:22-alpine AS build
WORKDIR /app
COPY package.json package-lock.json ./
# --ignore-scripts: `nuxt prepare` (postinstall) needs the source, which is copied in the next step.
# Optional extra CA (only for builds behind a TLS-intercepting corporate proxy):
#   docker build --secret id=extra_ca,src=corp-ca.pem ...   It is mounted for this step only, never stored in the image.
RUN --mount=type=cache,target=/root/.npm --mount=type=secret,id=extra_ca,required=false \
    if [ -f /run/secrets/extra_ca ]; then export NODE_EXTRA_CA_CERTS=/run/secrets/extra_ca; fi; \
    npm ci --ignore-scripts
COPY . .
RUN npm run build

# Nitro bundles everything the server needs into .output: no node_modules in the runtime image.
FROM node:22-alpine
WORKDIR /app
ENV NODE_ENV=production HOST=0.0.0.0 PORT=3000
COPY --from=build --chown=node:node /app/.output ./.output
USER node
EXPOSE 3000
CMD ["node", ".output/server/index.mjs"]
