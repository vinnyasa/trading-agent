FROM node:20-alpine

WORKDIR /app

COPY package*.json ./
RUN npm install

COPY tsconfig.json ./
COPY src ./src

# Type-check at build time
RUN node_modules/.bin/tsc --noEmit

# Reports output directory
RUN mkdir -p reports/daily

CMD ["node_modules/.bin/ts-node", "src/batch.ts"]
