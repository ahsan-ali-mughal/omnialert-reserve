# OmniAlert & Reserve

Event-driven distributed reservation and notification engine for high-concurrency
flash sales / event ticketing, implementing the Tech PRD v1.0.0.

- **Sub-20ms stock validation** at the cache edge using a Redis Lua script
  (atomic decrement + duplicate-purchase guard, zero overselling).
- **Decoupled persistence** via Apache Kafka, so the synchronous API path
  never waits on a MySQL write.
- **Resilient notification fan-out** via AWS SNS → SQS (LocalStack locally),
  with a Dead Letter Queue for failed email deliveries.
- **Audit + telemetry** written to MongoDB for every persistence and
  notification event.

## Architecture

```
Client --> POST /api/v1/orders --> Redis Lua (atomic reserve) --> 202 Accepted
                                          |
                                          v
                              Kafka: order-reserved-events
                                          |
                                          v
                       OrderPersistenceConsumer --> MySQL (orders)
                                          |               |
                                          v               v
                                   MongoDB audit log   AWS SNS topic
                                                            |
                                                            v
                                                       AWS SQS queue
                                                            |
                                                            v
                                                    EmailListenerService
                                                     (JavaMailSender)
                                                            |
                                                 failures -> AWS DLQ
```

## Tech Stack

| Tier | Technology |
|---|---|
| Language / Framework | Java 21, Spring Boot 3.3 |
| Cache & Edge Guard | Redis 7.x (Lua scripting) |
| Relational DB | MySQL 8.0 (HikariCP) |
| Document Store | MongoDB 6.x |
| Message Streaming | Apache Kafka |
| Cloud Notifications | AWS SNS/SQS (LocalStack locally) |
| Auth | OAuth2 Resource Server / JWT (RS256) |

## Quick Start (Docker)

```bash
docker compose up --build
```

This brings up: Redis, MySQL (auto-seeded via `src/main/resources/db/init.sql`),
MongoDB, Zookeeper + Kafka, LocalStack (auto-provisions the SNS topic + SQS
queue + DLQ via `localstack/init-aws.sh`), MailHog (SMTP catcher + web UI on
http://localhost:8025), and the Spring Boot app on **http://localhost:8080**.

Wait for all health checks to pass (`docker compose ps`), then seed a
Redis stock key for the sample item that was inserted into MySQL by `init.sql`:

```bash
docker exec -it omni-redis redis-cli SET inventory:item:1 100
```

(Item id `1` is the auto-increment id of the seeded "Limited Edition Sneaker" row.)

### Get a test JWT

The API is protected by OAuth2/JWT. For local testing, a throwaway RSA
keypair is provided in `dev-keys/` (public half is baked into the jar at
`src/main/resources/certs/public_key.pem`).

```bash
pip install pyjwt cryptography --break-system-packages
python3 scripts/generate-test-jwt.py
```

### Call the API

```bash
TOKEN=$(python3 scripts/generate-test-jwt.py)

curl -i -X POST http://localhost:8080/api/v1/orders \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"userId": 8832, "itemId": 1, "quantity": 2}'
```

Expected: `202 Accepted` with a `reservationId`. Within a few seconds:
- A row appears in MySQL `orders` (status `CONFIRMED`).
- An `order_audit_logs` document appears in MongoDB.
- An email appears in MailHog at http://localhost:8025.
- A `notification_telemetry` document appears in MongoDB.

Re-running the same request (same `userId` + `itemId`) returns `409 CONFLICT`
(`DUPLICATE_PURCHASE`). Requesting more than the remaining Redis stock
returns `409 CONFLICT` (`INSUFFICIENT_STOCK`).

## Local Development (without Docker for the app)

Start only the infra:

```bash
docker compose up redis mysql mongo kafka zookeeper localstack mailhog
```

Then run the app from your IDE / `mvn spring-boot:run` with the
environment variables in `.env.example` exported (they default to
`localhost` already, so this usually works out of the box).

## Project Layout

```
src/main/java/com/omnialert/reserve/
├── config/        # Redis, Kafka, AWS (SNS/SQS), Security(JWT)
├── controller/     # REST API + global exception handling
├── dto/            # Request/response/event payloads
├── entity/         # JPA entities (users, items, orders)
├── document/       # MongoDB documents (audit log, telemetry)
├── repository/     # Spring Data JPA + MongoDB repositories
├── service/        # ReservationService, Kafka consumer, notification workers
└── exception/      # Domain exceptions -> HTTP error mapping

src/main/resources/
├── application.yml
├── scripts/reserve_stock.lua   # Atomic Redis reservation script
├── db/init.sql                 # MySQL schema + seed data
└── certs/public_key.pem        # JWT verification key (dev)

localstack/init-aws.sh   # Provisions SNS topic + SQS queue + DLQ on container start
dev-keys/private_key.pem # Dev-only signing key (NOT bundled in the jar)
```

## Non-Functional Targets (per PRD §6)

- Edge API P95 latency < 20ms (Redis Lua execution).
- ≥ 4,000 req/sec per app instance.
- 0.00% overselling rate (guaranteed by Lua's single-threaded atomicity).
- ≥ 90% coverage target for unit/integration tests (JaCoCo not wired into
  this scaffold's `pom.xml` yet — add the `jacoco-maven-plugin` if you need
  to enforce this in CI).

## Notes / Production Hardening Ideas

- Swap the manual SQS poll loop in `EmailListenerService` for
  `spring-cloud-aws-starter-sqs` in production.
- Point `app.aws.endpoint` at nothing (remove the override) and use IAM
  roles for real AWS SNS/SQS.
- Issue real JWTs from an actual OAuth2/OIDC provider instead of
  `scripts/generate-test-jwt.py`.
- Add `jacoco-maven-plugin` + CI gate to enforce the 90% coverage NFR.
