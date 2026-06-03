# ADR-002: Technology Stack Choices

**Date**: 2024-01-01  
**Status**: Accepted  

---

## Java 21 over Java 17

**Why Java 21?**
- **Virtual Threads (Project Loom)**: Massive concurrency improvement. A single JVM can handle 1M+ concurrent connections with virtual threads, impossible with platform threads. This is a key interview topic for 2024.
- **Pattern Matching**: Cleaner code, fewer casts
- **Record Classes**: Immutable DTOs without boilerplate
- **Sealed Classes**: Better domain modeling

**Interview Question**: *"Why did you choose Java 21?"*  
**Answer**: "Java 21 introduced Virtual Threads via Project Loom, which eliminates the thread-per-request bottleneck. Traditional Spring MVC uses platform threads (expensive OS threads). With virtual threads, we get non-blocking I/O semantics without the complexity of reactive programming. For an incident platform handling 10K alerts/minute, this is critical."

---

## PostgreSQL over MySQL

**Why PostgreSQL?**
1. **JSONB support**: Store unstructured alert payloads alongside structured data
2. **pgvector extension**: Vector storage for RAG embeddings without a separate vector DB
3. **Advanced indexing**: GiST indexes for range queries on timestamps
4. **Window functions**: Complex analytics queries for incident patterns
5. **Full-text search**: Built-in, reducing Elasticsearch dependency for simple cases

**Interview Question**: *"Why not MongoDB for this use case?"*  
**Answer**: "Incidents have relationships — alerts belong to incidents, investigations belong to incidents, audit logs reference incidents. These relational constraints are better enforced by PostgreSQL with foreign keys. MongoDB's flexible schema is valuable when you don't know your data model, but we do. Additionally, ACID transactions are critical for incident state machines."

---

## Kafka over RabbitMQ

**Why Kafka?**
1. **Durability**: Messages persist to disk with configurable retention (7 days by default)
2. **Replay**: Can re-process all alerts from last week — impossible with RabbitMQ
3. **Consumer Groups**: Multiple services can independently consume same stream
4. **Ordering**: Partition-level ordering ensures alerts for same incident are processed in order
5. **Industry Standard**: AWS uses Kafka internally, Confluent has $4.5B valuation

**When RabbitMQ wins**: Task queues, when you want to delete messages after processing, complex routing rules

---

## Redis over Memcached

**Why Redis?**
1. **Data Structures**: Lists, Sets, Sorted Sets — not just key-value
2. **Persistence**: RDB snapshots + AOF logs (Memcached is purely in-memory)
3. **Pub/Sub**: Can use Redis for lightweight event broadcasting
4. **Cluster Mode**: Built-in clustering for horizontal scaling
5. **Lua scripting**: Atomic complex operations

**Use in this project**:
- Sorted Sets for incident priority queues
- Strings for simple incident status caching
- Hashes for agent result caching

---

## Spring AI + LangChain4j over Bare API Calls

**Why LangChain4j?**
1. **Agent abstractions**: ReAct pattern, Tool calling, Memory management
2. **RAG pipeline**: Built-in document loaders, splitters, embedding stores
3. **Multiple LLM providers**: Swap OpenAI → Anthropic → local Ollama with config change
4. **Java-native**: Designed for Java, not a Python port

**Spring AI adds**:
- Spring Boot autoconfiguration
- Micrometer metrics for AI calls
- Testcontainers support
