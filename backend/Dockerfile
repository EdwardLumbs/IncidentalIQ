# Trip Ops Incidental Monitor — backend. Multi-stage: compile TS, ship only the compiled JS +
# production deps. No `tsx`/`typescript` in the final image — those are dev-time only.

FROM node:20-alpine AS builder
WORKDIR /app
COPY package.json package-lock.json ./
RUN npm ci
COPY tsconfig.json ./
COPY src ./src
RUN npm run build

FROM node:20-alpine
WORKDIR /app
ENV NODE_ENV=production
COPY package.json package-lock.json ./
RUN npm ci --omit=dev
COPY --from=builder /app/dist ./dist

# Config (DATABASE_URL, GROQ_API_KEY, API_TOKEN, GROQ_MODEL, BATCH_SIZE, MAX_MSGS_PER_RUN, PORT) comes
# from the compose service's env_file, same pattern as the other apps on the home server — nothing is
# baked into the image.
EXPOSE 8790
CMD ["node", "dist/index.js"]
