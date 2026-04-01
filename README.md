# Token Generator

Reusable OTP + JWT token service for app-specific contributor or access flows.

This folder is self-contained and can be used as its own Git repo root.

## Structure

```text
token-generator/
├── service/              Spring Boot API on :8081
├── ui/                   React + Vite UI on :5174
├── docker-compose.yml    Local stack for this repo
└── .env.example
```

## What It Does

- validates email addresses
- sends OTP codes by email
- verifies OTPs
- issues signed JWTs for registered client apps

The current config includes an `interview-bank` client so the existing Interview Bank flow keeps working after the split.

## Prerequisites

Docker path:

- Docker Desktop
- Docker Compose v2

Local dev path:

- Java 17+
- Maven 3.8+
- Node.js 20+
- npm

## First-Time Setup

Create the repo-level env file:

```bash
cd /home/chinu/token-generator
cp .env.example .env
```

The repo-root `.env` is auto-loaded by the Spring Boot service for local runs.

## Run This Repo With Docker

From the repo root:

```bash
cd /home/chinu/token-generator
docker compose up --build
```

Stop it later with:

```bash
cd /home/chinu/token-generator
docker compose down
```

Services started by this repo:

- UI: `http://localhost:5174`
- API: `http://localhost:8081`
- Redis: `localhost:6379`

## Run This Repo Locally Without Docker

Terminal 1:

```bash
cd /home/chinu/token-generator
docker compose up redis -d
```

Terminal 2:

```bash
cd /home/chinu/token-generator/service
mvn spring-boot:run
```

Terminal 3:

```bash
cd /home/chinu/token-generator/ui
npm install
npm run dev
```

Local URLs:

- UI: `http://localhost:5174`
- API: `http://localhost:8081`

Stop Redis later with:

```bash
cd /home/chinu/token-generator
docker compose stop redis
```

## Run Both Repos Together

If you want the full Interview Bank submission flow to work, run this repo and the `interview-bank` repo together.

Terminal 1:

```bash
cd /home/chinu/token-generator
docker compose up redis -d
```

Terminal 2:

```bash
cd /home/chinu/token-generator/service
mvn spring-boot:run
```

Terminal 3:

```bash
cd /home/chinu/token-generator/ui
npm install
npm run dev
```

Terminal 4:

```bash
cd /home/chinu/interview-bank/service
mvn spring-boot:run
```

Terminal 5:

```bash
cd /home/chinu/interview-bank/ui
npm install
npm run dev
```

Then use:

1. `http://localhost:5174/?app=interview-bank`
2. verify the email OTP flow
3. paste the issued token into `http://localhost:5173/submit`

## Environment

Repo root `.env`:

```bash
JWT_SECRET=change-me-to-a-strong-random-secret-at-least-32-chars
MAIL_HOST=smtp.gmail.com
MAIL_PORT=587
MAIL_USERNAME=your-email@gmail.com
MAIL_PASSWORD=your-gmail-app-password
REDIS_HOST=redis
REDIS_PORT=6379
REDIS_PASSWORD=
```

Optional UI env file for custom API or hosted environments:

```bash
cd /home/chinu/token-generator/ui
cp .env.example .env.local
```

`ui/.env.local` values:

```bash
VITE_API_BASE_URL=http://localhost:8081/api/v1/token
```

## Integration Contract

- consumer apps must share the same `JWT_SECRET`
- tokens are issued with issuer `interview-bank-token-generator`
- audience is the client app id, for example `interview-bank`
- one-time token usage is enforced by the consuming app, not by this service

## Register A New Client App

Add a new entry under `app.clients` in `service/src/main/resources/application.yml`.

Example fields:

- `id`
- `name`
- `description`
- `require-work-email`
- `token-ttl-hours`
- `claims`

## API Summary

- `GET /api/v1/token/client/{clientId}`
- `POST /api/v1/token/validate-email`
- `POST /api/v1/token/request-otp`
- `POST /api/v1/token/verify-otp`
