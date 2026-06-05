# 🎓 Backend & SRE Engineering Interview Cheatsheet

This cheatsheet highlights the architectural tradeoffs, design patterns, and engineering decisions implemented throughout this 7-day project. Use it as an interview-prep guide to explain **why** each component was built the way it was.

---

## 🏛️ 1. Architecture & Core Design

### Clean Architecture (Hexagonal / Ports & Adapters)
- **Tradeoff**: More boilerplate code (separate entity classes, DTOs, and mappers) in exchange for absolute framework independence.
- **Core Concept**: The `domain` layer has **zero dependencies** on external frameworks (no Spring, no Hibernate/JPA annotations). It is pure Java. 
- **Interview Q&A**: *"Why not annotate domain entities directly with JPA `@Entity`?"*
  - **Answer**: Annotating domain entities directly tightly couples your business rules to database representation. If you switch from PostgreSQL to MongoDB or Cassandra, your domain model shouldn't change. Keeping domain entities clean (plain Java) and mapping them to separate database persistence entities (`*Entity.java` in infrastructure layer) isolates business logic from storage details.

### Rich Domain Model vs. Anemic Domain Model
- **Tradeoff**: Rich domain models place business rules directly on the entity objects, whereas anemic models keep entities as plain data buckets (getters/setters) and put all logic in Service classes.
- **How we applied it**: `IncidentEntity` enforces its own state transitions using the `canTransitionTo(IncidentStatus)` method. It rejects invalid state changes directly.
- **Interview Q&A**: *"What is an Anemic Domain Model and why is it considered an anti-pattern?"*
  - **Answer**: An anemic domain model is one where objects contain data but no behavior, leaving services to implement all logic. This leads to procedural code masquerading as Object-Oriented. By moving logic like state-machine validation (`canTransitionTo`) directly into the entity class, we ensure that an object is always in a valid state, promoting high cohesion.

---

## 📨 2. Event-Driven Messaging (Kafka)

### Partition Sizing & Message Ordering
- **Tradeoff**: More partitions support higher consumer throughput but increase broker memory consumption and zookeeper/metadata overhead.
- **How we applied it**: Partitioned `alert-events` into 6 partitions because alert ingestion is high volume. Partitioned `incident-events` into 3 partitions.
- **Ordering Guarantee**: Kafka only guarantees message ordering **within a single partition**, not across a topic. We set the partition key to the `serviceName` (for alerts) and `incidentId` (for incidents). This ensures all alerts for a specific service (like `checkout-service`) go to the same partition and are processed sequentially.
- **Interview Q&A**: *"How do you guarantee exactly-once or at-least-once message processing in a consumer?"*
  - **Answer**: We enforce at-least-once delivery by disabling auto-commit and setting the acknowledgement mode to `MANUAL_IMMEDIATE`. The consumer commits the offset only **after** the database transaction commits successfully. If processing fails, no acknowledgement is sent, and Kafka replays the message from the last committed offset. We then prevent duplicate processing (idempotency) by saving a unique fingerprint in the DB.

### Non-Blocking Retries (`@RetryableTopic`)
- **Tradeoff**: Standard Spring `@Retryable` blocks the consumer thread during backoffs, halting processing of other messages. `@RetryableTopic` uses separate retry topics, allowing the main topic consumer to proceed.
- **How we applied it**: We configured 4 attempts with exponential backoff (1s -> 3s -> 9s) and a Dead Letter Queue (DLQ) suffix `-dlq`.
- **Interview Q&A**: *"How do you handle 'poison pill' messages in Kafka?"*
  - **Answer**: A poison pill is a message that repeatedly fails processing due to serialization issues or bad data. If retried on the main thread, it blocks the partition. We route these messages to a Dead Letter Queue (`alert-events-dlq`) after 4 attempts. This isolates the bad message so operations can inspect it, while allowing other healthy messages in the partition to continue processing.

---

## 🔒 3. Security, Caching & Protection (JWT & Redis)

### Rate Limiter Placement
- **Tradeoff**: Placed the rate limiter *before* the Spring Security filter chain.
- **Interview Q&A**: *"Why rate limit requests before verifying JWT signatures?"*
  - **Answer**: JWT verification involves CPU-intensive cryptographic signature checks (HMAC-SHA256). During an alert storm or DDoS, if the rate limiter is placed *after* security checks, an attacker could overwhelm the CPU just by sending random, unauthenticated requests. Placing the rate limiter at the gateway entrance (checking IP address) protects downstream CPU cycles from signature verification load.

### Redis Atomic Token Bucket via Lua
- **Tradeoff**: Checking and decrementing tokens in Java requires distributed locks to prevent race conditions under high concurrency.
- **Interview Q&A**: *"Why did you use a Lua script inside Redis for rate limiting?"*
  - **Answer**: A Lua script executes **atomically** on a single thread inside Redis. By running the check-and-decrement logic in a Lua script, we ensure the operation completes without race conditions or dirty reads, and without the overhead of heavy Java-side distributed locks (like Redisson).

---

## 🤖 4. AI Orchestration & RAG (LangChain4j, pgvector, Elasticsearch)

### Database Connection Pool Starvation (Short Transactions)
- **Tradeoff**: LLM calls take seconds or minutes. Keeping a database transaction open during a long-running LLM call exhausts the database connection pool.
- **How we applied it**: The `AgentOrchestrator` runs completely **outside** of database transactions. It makes short transactional calls to the database to load state, runs the AI agents (LLM calls) asynchronously, and then makes another short transactional call to save the results.
- **Interview Q&A**: *"Why is putting LLM calls inside @Transactional a critical system design mistake?"*
  - **Answer**: An LLM call can take upwards of 5-10 seconds. In Spring, `@Transactional` checks out a connection from the HikariCP connection pool and holds it until the method exits. If 20 alerts fire simultaneously and trigger 20 parallel LLM calls inside `@Transactional`, all 20 database connections will be held open waiting for the LLM. The connection pool will starve, other REST APIs will time out, and the database will lock up.

### pgvector vs. Standalone Vector Databases (Pinecone/Milvus)
- **Tradeoff**: Pinecone/Milvus scale better to billions of vectors but add infrastructure complexity. `pgvector` scales to millions of vectors and runs directly inside our existing PostgreSQL database.
- **How we applied it**: Ingested SOP runbooks into a `knowledge_embeddings` vector table in PostgreSQL.
- **Interview Q&A**: *"What are the advantages of pgvector for an enterprise RAG system?"*
  - **Answer**: Operational simplicity and transactional consistency (ACID). With `pgvector`, vector embeddings and relational metadata reside in the same database. This allows us to perform atomic operations (e.g. deleting a service and its embeddings in a single transaction) and run unified SQL queries joining relational columns with vector similarity checks, avoiding the synchronization lag found when using separate vector DBs.

### Elasticsearch Full-Text Search Space Limitation
- **Tradeoff**: Simple wildcard queries in Elasticsearch are easy to map in Spring Data repositories, but fail when search terms contain spaces.
- **How we applied it**: Swapped derived wildcard repository methods with standard `@Query` matching, using `multi_match` for incidents and a term-match boolean combination for logs.
- **Interview Q&A**: *"Why do wildcard queries fail when search inputs contain spaces, and how do you resolve it?"*
  - **Answer**: Wildcard queries evaluate character sequences on individual indexed terms. When a user input contains spaces (e.g. `"Memory leak"`), the search query is split. Standard wildcard query parsers fail to match across these terms correctly. We resolve this by using a `multi_match` query, which tokenizes the search input and matches against analyzed fields in the Elasticsearch index.
