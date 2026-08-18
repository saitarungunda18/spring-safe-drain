# Spring Safe Drain

> Spring Safe Drain is an extensible, platform-independent Spring Boot framework for tracking in-flight work and coordinating graceful application shutdown.

Spring Safe Drain closes application-level admission before termination and lets work already accepted by the process finish. Milestone 1 supports protected servlet HTTP paths, exposes lifecycle state through Actuator, and provides extension contracts for future Kafka, scheduler, outbox, and business-safety participants.

## Why this exists

Deployment platforms and load balancers can stop routing traffic, but they do not know whether an application is still committing a transaction. Platform termination grace periods only delay forced exit. Neither mechanism coordinates Kafka offsets, scheduled work, outbox delivery, or application-specific invariants. This framework provides the missing in-process safety decision. It complements infrastructure controls in ECS, Kubernetes, virtual machines, and other environments; it does not replace them.

Milestone 1 deliberately implements only application-level HTTP draining. It does not call deployment-platform APIs, delay process termination by itself, or provide exactly-once processing.

## Modules

- `safe-drain-core`: Spring-independent admission registry, lifecycle, coordinator, events, and participant SPI.
- `safe-drain-spring-boot-starter`: conditional auto-configuration, servlet filter, configuration properties, and `safeDrain` Actuator endpoint.
- `safe-drain-testkit`: deterministic participant utility for consumer tests.
- `examples/work-service`: runnable generic sample that consumes the starter as an ordinary dependency.

## Architecture and semantics

The registry serializes admission closure and token acquisition under one lock, so a request is either accepted and counted or rejected. Tokens decrement once even when closed repeatedly. The coordinator uses zero-work notifications plus one scheduled deadline, never a polling loop. Concurrent drain calls observe the same operation. Completion and timeout serialize under the lifecycle lock, so exactly one terminal state wins. `DRAINED` is entered only when the registry is empty, every participant reports drained, and no participant start failure was recorded.

Lifecycle: `ACTIVE -> DRAINING -> DRAINED`, or `DRAINING -> TIMED_OUT`. `DRAINING`, `DRAINED`, and `TIMED_OUT` can be resumed back to `ACTIVE`. Resuming a drained task invalidates its previous `safeToTerminate: true` decision, so deployment automation must always read the latest status before terminating it. Status values and collections are immutable snapshots.

## Quick start

Requirements: Java 21. The wrapper downloads Maven 3.9.11.

```bash
./mvnw clean verify
./mvnw install -DskipTests
./mvnw -f examples/work-service/pom.xml spring-boot:run
```

On Windows, stop the running example with `Ctrl+C` before running `clean`; otherwise Java keeps the work-service JAR locked and Maven cannot delete `target`. Running the example as a standalone module resolves the framework from the local Maven repository, so run `install` after framework code changes to avoid loading a stale snapshot.

Consumer dependency for this local project version:

```xml
<dependency>
  <groupId>io.github.springsafedrain</groupId>
  <artifactId>safe-drain-spring-boot-starter</artifactId>
  <version>1.0.0-SNAPSHOT</version>
</dependency>
```

The framework is disabled by default. Enable and scope it explicitly:

```yaml
safe-drain:
  enabled: true
  timeout: 30s
  http:
    enabled: true
    protected-paths:
      - /work/**
      - /work
    excluded-paths:
      - /actuator/**
    retry-after-seconds: 30
management.endpoints.web.exposure.include: health,safeDrain
```

Protected and excluded paths use Spring Ant matching; exclusions win. Static assets and unrelated controllers are not protected unless a configured pattern includes them. `timeout` and `retry-after-seconds` must be positive and protected paths must be non-empty. User-defined registry, coordinator, endpoint, or filter-registration beans override defaults.

## API and demonstration

`GET /actuator/safeDrain` reads status. One `POST` operation controls the lifecycle with an enum action: `{"action":"DRAIN"}` begins an idempotent drain, while `{"action":"RESUME"}` resumes admission from `DRAINING`, `DRAINED`, or `TIMED_OUT`. Both commands are idempotent: repeating `DRAIN` while already draining/drained returns the current status, and repeating `RESUME` while already `ACTIVE` returns `200 OK` with the unchanged `ACTIVE` status. Rejected work receives status 503, `Retry-After`, and stable JSON:

```json
{"code":"SERVICE_DRAINING","message":"This service is temporarily not accepting new work.","state":"DRAINING"}
```

Run these in separate terminals after starting the example:

```bash
curl -i -X POST http://localhost:8080/work -H "Content-Type: application/json" -d '{"name":"report-generation","processingDelayMs":10000}'
curl -i -X POST http://localhost:8080/actuator/safeDrain -H "Content-Type: application/json" -d '{"action":"DRAIN"}'
curl -i -X POST http://localhost:8080/work -H "Content-Type: application/json" -d '{"name":"data-export","processingDelayMs":0}'
curl -s http://localhost:8080/actuator/safeDrain
curl -s http://localhost:8080/actuator/health
```

Resume the same task with the same POST endpoint:

```bash
curl -i -X POST http://localhost:8080/actuator/safeDrain -H "Content-Type: application/json" -d '{"action":"RESUME"}'
```

The first work item completes; the later request returns 503; status becomes `DRAINED` with `safeToTerminate: true` after the first response finishes.

## Code flow

For a protected work request, `SafeDrainHttpFilter.doFilterInternal()` calls `InFlightWorkRegistry.tryBegin("http")`. When admission is open, the registry increments the HTTP count and returns a `WorkToken`; the filter then calls `WorkController.create()`. After the controller completes, the filter closes the token in its `finally` block, and `InFlightWorkRegistry.complete()` decrements the count.

When `SafeDrainEndpoint.changeState(DRAIN)` calls `DrainCoordinator.startDrain()`, the coordinator changes the state to `DRAINING` and calls `InFlightWorkRegistry.closeAdmission()`. Already accepted requests keep their tokens and are allowed to finish. A later protected request cannot acquire a token, so `SafeDrainHttpFilter.reject()` returns `503 SERVICE_UNAVAILABLE` without invoking `WorkController`. A 500 response is not the expected draining response.

When the final token closes, the registry count reaches zero and invokes its zero listeners. `DrainCoordinator.evaluateCompletion()` verifies that the state is `DRAINING`, the registry is empty, participants are drained, and no participant start failure exists. It then changes the state to `DRAINED` and reports `safeToTerminate: true`. The current milestone tracks the servlet request lifecycle; the sample work-item map and its `PROCESSING` value are not inspected by the drain coordinator.

## Security

The drain endpoint changes application availability. Never expose it on a public listener. Use a private management port/interface, network policy, TLS, and your platform's authenticated management security. The example exposes it locally only for demonstration and intentionally does not implement authentication, which is out of scope.

## Limitations and roadmap

This release targets Spring MVC servlet applications. Servlet async requests remain counted until completion, timeout, or error. A process crash can still interrupt work, so business operations must be idempotent. Asynchronous participant adapters must call `DrainCoordinator.requestEvaluation()` when their status changes; this notification avoids polling.

The sample stores generic work items in memory and simulates latency. It is demonstration code, not a durable job-processing system. Milestone 1 neither controls the deployment platform nor guarantees exactly-once processing.

Planned milestones may add Kafka, scheduled-job, outbox, and platform-specific orchestration integrations. They are intentionally absent here.

See [architecture](docs/architecture.md) and [failure scenarios](docs/failure-scenarios.md).
