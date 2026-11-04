FROM node:22-alpine
WORKDIR /app
COPY package.json server.mjs ./
RUN addgroup -S cinema && adduser -S cinema -G cinema
USER cinema
EXPOSE 8787
CMD ["node", "server.mjs"]
