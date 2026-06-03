# ADR-001: Modular Monolith First, Microservices Later

**Date**: 2024-01-01  
**Status**: Accepted  
**Deciders**: Engineering Team  

---

## Context

We need to decide whether to start with a microservices architecture or a monolith for the Incident Management Platform.

## Decision

We will start with a **Modular Monolith** and extract services only when we have clear scalability requirements.

## Rationale

### Against starting with microservices immediately:
1. **Distributed systems problems from day 1**: Network latency, partial failures, distributed transactions
2. **Premature optimization**: We don't know which services need independent scaling yet
3. **Operational overhead**: Each service needs its own CI/CD, monitoring, deployment
4. **Development velocity**: Slower iteration in early stages

### The Modular Monolith approach:
- Each domain (alerts, incidents, investigation) is a **module** with clear boundaries
- Modules communicate via **interfaces**, not direct class references
- This gives us clean boundaries WITHOUT distributed systems complexity
- When a module needs independent scaling, we extract it into a service

## How Real Companies Did This
- **Netflix**: Started monolith in 2007, migrated to microservices over 7 years
- **Shopify**: Still a modular monolith in the Rails core
- **Stack Overflow**: Monolith serving 1M+ requests/minute
- **Amazon**: Extracted services from their Obidos monolith over years

## Consequences
- ✅ Faster development velocity
- ✅ Simpler debugging and testing
- ✅ No distributed transaction complexity
- ✅ Easy refactoring of module boundaries
- ⚠️ Single deployment unit (mitigated by modular design)
- ⚠️ Shared database (mitigated by schema-per-module convention)

## Interview Talking Point
> "We followed the strangler fig pattern — starting with a modular monolith where each domain had clear module boundaries, then extracting services based on actual scaling needs rather than theoretical ones."
