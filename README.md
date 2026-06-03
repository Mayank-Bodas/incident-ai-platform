# 🚨 AI-Powered Incident Management Platform

> An enterprise-grade platform that automatically investigates production incidents using a multi-agent AI system, performing root cause analysis and generating remediation recommendations.

[![Java](https://img.shields.io/badge/Java-21-orange.svg)](https://openjdk.org/projects/jdk/21/)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.x-green.svg)](https://spring.io/projects/spring-boot)
[![Kafka](https://img.shields.io/badge/Apache%20Kafka-3.x-black.svg)](https://kafka.apache.org/)
[![PostgreSQL](https://img.shields.io/badge/PostgreSQL-15-blue.svg)](https://www.postgresql.org/)
[![Redis](https://img.shields.io/badge/Redis-7-red.svg)](https://redis.io/)
[![Elasticsearch](https://img.shields.io/badge/Elasticsearch-8-yellow.svg)](https://www.elastic.co/)
[![Docker](https://img.shields.io/badge/Docker-Containerized-blue.svg)](https://www.docker.com/)
[![Kubernetes](https://img.shields.io/badge/Kubernetes-Ready-326CE5.svg)](https://kubernetes.io/)

## 🎯 Problem Statement

Modern distributed systems generate thousands of alerts daily. On-call engineers spend hours manually investigating incidents, reading logs, checking metrics, and searching runbooks. This platform automates the entire investigation lifecycle using AI agents.

## 🏗️ Architecture Overview

```
Monitoring Systems (Prometheus, Datadog, Custom)
        │
        ▼
   Alert Ingestion API
        │
        ▼
   Kafka (alert-events topic)
        │
        ▼
   Incident Coordinator
        │
        ▼
   AI Agent Orchestrator
   ├── Planner Agent       → Creates investigation strategy
   ├── Log Analysis Agent  → Detects log anomalies
   ├── Metrics Agent       → Analyzes performance metrics
   ├── Knowledge Agent     → RAG-based runbook retrieval
   ├── RCA Agent           → Root cause synthesis
   └── Recommendation Agent → Remediation steps
        │
        ▼
   Incident Updated (PostgreSQL + Elasticsearch)
```

## 🛠️ Technology Stack

| Layer | Technology | Purpose |
|-------|-----------|---------|
| Backend | Java 21, Spring Boot 3 | Core application framework |
| Database | PostgreSQL 15 | Primary data store |
| Cache | Redis 7 | Distributed caching |
| Messaging | Apache Kafka 3 | Event streaming |
| Search | Elasticsearch 8 | Log and incident search |
| AI | Spring AI + LangChain4j | Agent orchestration |
| Vector DB | pgvector (PostgreSQL ext) | RAG embeddings |
| Security | Spring Security + JWT | Authentication & Authorization |
| Observability | Micrometer + Prometheus + Grafana | Metrics & monitoring |
| Container | Docker + docker-compose | Local development |
| Orchestration | Kubernetes | Production deployment |

## 🚀 Quick Start

### Prerequisites
- Java 21+
- Docker Desktop
- Maven 3.9+

### Run Locally
```bash
# Start infrastructure
docker-compose up -d

# Run application
mvn spring-boot:run
```

### API Documentation
After starting, visit: http://localhost:8080/swagger-ui.html

## 📊 Key Features

- **Real-time Alert Ingestion**: Process 10,000+ alerts/minute
- **Automated Incident Creation**: Zero manual intervention
- **Multi-Agent Investigation**: 6 specialized AI agents working in parallel
- **RAG-powered Knowledge Retrieval**: Query runbooks and past incidents
- **Full Audit Trail**: Every state change recorded immutably
- **Role-Based Access Control**: ADMIN, ENGINEER, VIEWER roles
- **Production Observability**: Metrics, logs, and traces out of the box

## 📁 Project Structure

```
incident-platform/
├── src/
│   └── main/
│       ├── java/com/incidentplatform/
│       │   ├── domain/           # Core business entities
│       │   ├── application/      # Use cases and orchestration
│       │   ├── infrastructure/   # External integrations
│       │   └── interfaces/       # REST controllers
│       └── resources/
│           ├── application.yml   # Configuration
│           └── db/migration/     # Flyway migrations
├── docs/
│   ├── architecture/             # Architecture decision records
│   ├── decisions/                # ADRs (Architecture Decision Records)
│   └── runbooks/                 # Operational runbooks
├── scripts/
│   ├── docker/                   # Docker configurations
│   ├── k8s/                      # Kubernetes manifests
│   └── db/                       # Database scripts
└── docker-compose.yml
```

## 🎓 Engineering Concepts Demonstrated

- **Clean Architecture** (Hexagonal/Ports & Adapters)
- **Event-Driven Architecture** with Kafka
- **CQRS** (Command Query Responsibility Segregation)
- **Saga Pattern** for distributed transactions
- **Circuit Breaker Pattern** for resilience
- **Repository Pattern** for data abstraction
- **Strategy Pattern** for pluggable agents
- **RAG (Retrieval-Augmented Generation)** for AI knowledge
- **JWT + RBAC** security model
- **Twelve-Factor App** principles

## 📈 Performance Targets

| Metric | Target |
|--------|--------|
| Alert API Latency (p99) | < 200ms |
| Alert Throughput | 10,000/minute |
| AI Investigation Time | < 5 minutes |
| System Availability | 99.9% |

## 👨‍💻 Author

Built as a production-grade learning project demonstrating enterprise backend engineering practices.
