# 🚨 AI-Powered Incident Management Platform

> An enterprise-grade, event-driven platform that automates microservice incident investigations using a collaborative multi-agent AI system, performing semantic runbook RAG (Retrieval-Augmented Generation), log anomaly detection, and automated root cause analysis.

---

## 🗺️ System Architecture

```mermaid
flowchain
    direction TB
    subgraph Clients ["Ingestion & Users"]
        A["Prometheus / Monitoring"]
        B["SRE Operator (REST / UI)"]
    end

    subgraph API ["Gateway & Ingestion Layer"]
        C["API Gateway / AlertController"]
        D["RateLimitingFilter (Redis Token Bucket)"]
        E["JwtAuthenticationFilter (RBAC Validation)"]
    end

    subgraph Messaging ["Event Backbone (Kafka)"]
        F[("alert-events topic (6 partitions)")]
        G[("incident-events topic (3 partitions)")]
    end

    subgraph Core ["Incident Core Services"]
        H["IncidentCoordinator (Idempotent Consumer)"]
        I["InvestigationTriggerConsumer"]
        J["AgentOrchestrator (AI Pipeline)"]
    end

    subgraph Agents ["Collaborative AI Agent Team"]
        K["Planner Agent"]
        L["Log & Metrics Agent"]
        M["Knowledge Agent (pgvector RAG)"]
        N["RCA & Recommendation Agent"]
    end

    subgraph Storage ["Infrastructure Datastores"]
        O[("PostgreSQL (JPA, audit_logs, pgvector)")]
        P[("Elasticsearch (Incidents & Logs index)")]
        Q[("Redis (L2 Cache & Rate Limiting)")]
    end

    A --> D
    B --> D
    D --> E
    E --> C
    C --> F
    F --> H
    H --> O
    H --> G
    G --> I
    I --> J
    J --> K
    K --> L
    L --> M
    M --> N
    N --> J
    J --> O
    J --> P
    J --> Q
```

---

## 🛠️ Technology Stack & Ports

| Layer | Technology | Purpose | Local Port |
| :--- | :--- | :--- | :--- |
| **Backend** | Java 17, Spring Boot 3.3.x | Core application runtime | `8080` |
| **Database** | PostgreSQL 16 (pgvector) | Relational data, auditing, and vector store | `5432` |
| **Cache** | Redis 7.4 (Lettuce) | L2 cache and atomic rate limiting | `6379` |
| **Messaging** | Apache Kafka 7.7.0 (Confluent) | High-throughput event backbone | `9092` |
| **Search** | Elasticsearch 8.15.0 | Inverted index for log & incident full-text queries | `9200` |
| **AI Framework** | LangChain4j | LLM connectivity and prompt templates | N/A |
| **Observability** | Prometheus 2.54.0 | Time-series metrics scraping | `9090` |
| **UI Tools** | Kibana 8.15.0 | Log browsing and Elasticsearch dashboard | `5601` |

---

## 🔑 Default Roles & Credentials

To test Role-Based Access Control (RBAC), utilize the pre-seeded users. Log in at `POST /api/v1/auth/login` to obtain a bearer JWT.

| Email | Password | Role | Permissions |
| :--- | :--- | :--- | :--- |
| `admin@test.com` | `password` | `ROLE_ADMIN` | Full CRUD, user management, runbook ingestion |
| `engineer@test.com` | `password` | `ROLE_ENGINEER` | Manage incidents, ingest runbooks, run search APIs |
| `viewer@test.com` | `password` | `ROLE_VIEWER` | Read-only access to incidents and search endpoints |

---

## 🚀 Quick Start Guide

### Prerequisites
- Docker Desktop installed and running
- Java 17+ (JDK) installed locally (if running app outside container)
- Maven 3.9+ installed

### 1. Run the Entire Stack (Hardened Container Deployment)
Spin up the application and all required infrastructure containers in one command:
```bash
# Build the application JAR and start container stack
docker-compose up --build -d
```
Docker Compose will build our multi-stage Docker image and start:
1. `postgres` (with `pgvector` extension and schema initialized via Flyway)
2. `kafka` & `zookeeper` (with auto-created topics)
3. `redis` (secured with password auth and LRU eviction)
4. `elasticsearch` & `kibana` (single-node development mode)
5. `app` (our Spring Boot container running as non-root `sreuser`)
6. `prometheus` (configured to scrape `/actuator/prometheus` every 5 seconds)

### 2. Operational Dashboards

- **Swagger UI (API Specs)**: [http://localhost:8080/swagger-ui.html](http://localhost:8080/swagger-ui.html)
- **Prometheus Metrics**: [http://localhost:9090](http://localhost:9090)
- **Kibana Log Viewer**: [http://localhost:5601](http://localhost:5601)
- **Spring Actuator Health**: [http://localhost:8080/actuator/health](http://localhost:8080/actuator/health)

---

## 🧪 Step-by-Step API Demonstration

Follow these steps using `curl` or Swagger UI to trace the full lifecycle of alert ingestion, rate-limiting protection, event propagation, pgvector similarity RAG, and Elasticsearch queries.

### Step 1: Authentication (Get JWT Token)
Send a login request to obtain a bearer token:
```bash
curl -X POST http://localhost:8080/api/v1/auth/login \
  -H "Content-Type: application/json" \
  -d '{"email":"engineer@test.com","password":"password"}'
```
*Save the returned token string.*

### Step 2: Ingest a Runbook SOP (pgvector Store)
Ingest a troubleshooting runbook for the payment service:
```bash
curl -X POST http://localhost:8080/api/v1/documents/ingest \
  -H "Authorization: Bearer <YOUR_TOKEN>" \
  -H "Content-Type: application/json" \
  -d '{
    "title": "Payment database pool timeout recovery SOP",
    "content": "SOP: When Hikari pool exhausted in payment microservice, verify the database connections, reset pool capacity to 50, and restart the database service.",
    "serviceName": "payment-service",
    "tags": ["database", "postgres", "hikari"]
  }'
```
This is chunked, embedded, and stored as a dense vector in PostgreSQL using `pgvector`.

### Step 3: Trigger an Incident (Kafka Alert Ingestion)
Publish a critical alert that automatically coordinates an incident:
```bash
curl -X POST http://localhost:8080/api/v1/alerts \
  -H "Authorization: Bearer <YOUR_TOKEN>" \
  -H "Content-Type: application/json" \
  -d '{
    "title": "Hikari pool connections exhausted in payment-service",
    "description": "ConnectionTimeoutException: Connection is not available from Hikari pool.",
    "severity": "SEV1",
    "source": "prometheus",
    "serviceName": "payment-service",
    "environment": "production",
    "rawPayload": "{\"error\":\"ConnectionTimeoutException\"}"
  }'
```
*What happens in the background:*
1. API receives alert and generates SHA-256 fingerprint (deduplicates within 10-minute window).
2. Publishes `AlertCreatedEvent` to Kafka.
3. `IncidentCoordinator` consumes it, creates a new `IncidentEntity` in PostgreSQL (`status=OPEN`), and publishes `IncidentCreatedEvent`.
4. `InvestigationTriggerConsumer` receives the event and triggers `AgentOrchestrator` asynchronously.
5. Orchestrator transitions status to `INVESTIGATING` and kicks off the 4 SRE Agents.
6. The **KnowledgeBaseAgent** executes a semantic similarity vector query against PostgreSQL to pull the SOP ingested in Step 2.
7. The RCA Agent compiles findings and automatically resolves the incident (`status=RESOLVED`).

### Step 4: Full-Text Elasticsearch Queries
Query the Elasticsearch inverted indexes for incidents or log events matching SRE keywords:
```bash
# Search resolved incidents in Elasticsearch
curl -X GET "http://localhost:8080/api/v1/search/incidents?query=Hikari%20pool" \
  -H "Authorization: Bearer <YOUR_TOKEN>"

# Search indexed logs in Elasticsearch
curl -X GET "http://localhost:8080/api/v1/search/logs?serviceName=payment-service&query=ConnectionTimeoutException" \
  -H "Authorization: Bearer <YOUR_TOKEN>"
```

---

## 🏛️ Engineering & Architectural Patterns Applied

- **Clean Architecture (Hexagonal)**: Strict layer isolation (`domain` -> `application` -> `infrastructure` -> `interfaces`). Domain contains pure business rules with zero framework imports.
- **Event-Driven Pipeline**: Kafka handles alert routing and investigation triggering. Partition keys guarantee ordering per service name.
- **pgvector Semantic RAG**: Runbooks are stored as dense vectors, allowing our AI agents to pull SRE context semantically instead of basic string matches.
- **Atomic Token Bucket Rate Limiting**: Executes an atomic Lua script inside Redis at the filter layer to protect database and JWT decryption CPU cycles from alert storms.
- **Short Transaction Boundary Pattern**: Runs expensive LLM/Agent network operations outside database transactions, preventing connection pool starvation.
