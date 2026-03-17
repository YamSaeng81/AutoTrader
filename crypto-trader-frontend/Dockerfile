FROM node:20-alpine AS builder

WORKDIR /app
COPY package.json package-lock.json* ./
RUN npm ci
COPY . .
ARG INTERNAL_BACKEND_URL=http://backend:8080
ENV INTERNAL_BACKEND_URL=$INTERNAL_BACKEND_URL
ARG NEXT_PUBLIC_API_TOKEN
ENV NEXT_PUBLIC_API_TOKEN=$NEXT_PUBLIC_API_TOKEN
RUN rm -f playwright.config.ts && npm run build

FROM node:20-alpine

WORKDIR /app
COPY --from=builder /app/.next/standalone ./
COPY --from=builder /app/.next/static ./.next/static
COPY --from=builder /app/public ./public

EXPOSE 3000

ENV PORT=3000
ENV HOSTNAME="0.0.0.0"

CMD ["node", "server.js"]
