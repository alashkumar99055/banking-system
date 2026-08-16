# Banking Management System

Minimal staff-facing banking app: login, create customer accounts, credit, withdraw, and transaction history.

This is a prototype. It does not claim to meet real-world banking regulatory, KYC, AML, PCI, or financial-institution security requirements.

## Existing architecture (kept)

- Java 17 `com.sun.net.httpserver.HttpServer` backend (Maven)
- Static HTML/CSS/JS frontend
- PostgreSQL
- Salted SHA-256 password hashes
- Bearer session tokens

## Local setup

### 1. PostgreSQL

Create a database:

```sql
CREATE DATABASE banking_system;
```

### 2. Environment

Copy `.env.example` values into your shell. Minimum for local use:

```
POSTGRES_URL=jdbc:postgresql://localhost:5432/banking_system
POSTGRES_USER=postgres
POSTGRES_PASSWORD=postgres
```

Optional seed staff account (created on first boot if the username does not exist):

```
BANK_STAFF_USERNAME=teller
BANK_STAFF_PASSWORD=change-me-now
```

Schema is created automatically on startup (`CREATE TABLE IF NOT EXISTS`). You can also apply `backend/db/schema.sql` manually.

### 3. Run the backend

From `backend/`:

```
mvn -B package -DskipTests
java -jar target/banking-system-backend-1.0.0.jar
```

The server listens on `PORT` if set, otherwise `8080`. It also serves the `frontend/` files, so you can open:

- http://localhost:8080/login.html
- http://localhost:8080/index.html

You can also open `frontend/login.html` directly; `config.js` points local browsers at `http://localhost:8080`.

### 4. Staff login

Use the seeded user, or **Create Staff Login** on the sign-in page.

## API

All banking routes require `Authorization: Bearer <token>` except `/api/login`, `/api/register`, `/api/health`, and `/api/logout`.

| Method | Path | Purpose |
| --- | --- | --- |
| POST | `/api/login` | Staff login |
| POST | `/api/register` | Create staff login |
| POST | `/api/logout` | Invalidate session |
| GET | `/api/me` | Current staff user |
| GET | `/api/health` | Health check |
| POST | `/api/accounts` | Create customer account |
| GET | `/api/accounts?accountNumber=` | Look up account |
| POST | `/api/accounts/{accountNumber}/credit` | Credit |
| POST | `/api/accounts/{accountNumber}/withdraw` | Withdraw |
| GET | `/api/transactions` | History (optional `?accountNumber=`) |

Send `Idempotency-Key` on credit/withdraw to safely retry the same request.

Amounts are strings with two decimal places, e.g. `"100.00"`. Stored as `NUMERIC(19,2)`.

## Environment variables

| Variable | Purpose |
| --- | --- |
| `PORT` | Listen port (Render sets this) |
| `DATABASE_URL` or `POSTGRES_URL` | PostgreSQL URL |
| `POSTGRES_USER` / `POSTGRES_PASSWORD` | DB credentials if not in the URL |
| `PGHOST` `PGPORT` `PGDATABASE` `PGUSER` `PGPASSWORD` | Alternate DB settings |
| `BANK_STAFF_USERNAME` / `BANK_STAFF_PASSWORD` | Optional staff seed |
| `FRONTEND_DIR` | Static UI directory (Docker sets `/app/frontend`) |
| `FRONTEND_URL` | CORS origin if the UI is hosted separately |

Do not hardcode production credentials or database URLs in source.

## Render deployment

Use **one Web Service** (API + UI) plus a PostgreSQL database.

1. Create a PostgreSQL instance on Render. Copy the internal or external `DATABASE_URL`.
2. Create a **Web Service** from this repo.
   - Runtime: Docker
   - Dockerfile path: `./Dockerfile` (repository root)
   - Health check: `/api/health`
3. Set environment variables:
   - `DATABASE_URL` = Render Postgres connection string (`postgres://...`)
   - `BANK_STAFF_USERNAME` / `BANK_STAFF_PASSWORD` (recommended)
   - `PORT` is provided by Render
4. Deploy. Open the service URL and sign in.

The root Dockerfile copies `frontend/` into the image and the Java server serves it. Production `config.js` uses the same origin (no hardcoded localhost). Render provides HTTPS.

If you host the UI as a separate Static Site, set that site’s `config.js` `apiBaseUrl` to the Web Service URL and set `FRONTEND_URL` on the API to the static site origin.

## Security notes (prototype)

- Passwords are salted SHA-256 hashes, never stored in plaintext
- Banking APIs require a valid session
- Amounts and account numbers are validated on the server
- Balance updates use `SELECT FOR UPDATE` inside a database transaction
- Unique account numbers; unique idempotency keys
- Login rate limiting (10 attempts / minute / IP)
- Secrets belong in environment variables

In-memory sessions reset when the process restarts. That is acceptable for this prototype; a production bank would persist sessions or use signed tokens with server-side revocation.
