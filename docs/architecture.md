# Architecture

## System context

ECS Safe Drain runs inside each Spring Boot application instance. It does not deploy containers or move load-balancer traffic; ECS, CodeDeploy, and the load balancer remain responsible for those platform operations.

```mermaid
flowchart LR
    U["Upstream client"] --> ALB["Application Load Balancer"]
    ALB --> APP["Spring Boot application"]
    OPS["Deployment operator"] --> EP["Safe Drain Actuator endpoint"]
    EP --> FW["ECS Safe Drain framework"]
    FW --> APP
    FW --> STATUS["Drain status / safeToTerminate"]
    STATUS --> OPS
```

The framework answers one local question: **does this application process still have protected HTTP work that must finish before it is safe to terminate?**

## Module architecture

```mermaid
flowchart TD
    EXAMPLE["examples/work-service\nRunnable Spring Boot application"]
    STARTER["safe-drain-spring-boot-starter\nSpring MVC and Actuator integration"]
    CORE["safe-drain-core\nFramework-independent state machine"]
    TESTKIT["safe-drain-testkit\nConsumer testing utility"]

    EXAMPLE --> STARTER
    STARTER --> CORE
    TESTKIT --> CORE
```

- `safe-drain-core` contains admission control, in-flight counting, lifecycle state, timeout handling, status snapshots, lifecycle events, and the participant SPI. It has no Spring dependency.
- `safe-drain-spring-boot-starter` discovers and configures the core, intercepts protected Servlet requests, and exposes management operations through Actuator.
- `safe-drain-testkit` gives framework consumers a deterministic participant for testing future workload adapters.
- `examples/work-service` is a generic demonstration application that imports the starter like an ordinary dependency.

## Runtime components

```mermaid
flowchart TD
    REQ["Protected HTTP request"] --> FILTER["SafeDrainHttpFilter"]
    FILTER -->|"tryBegin(http)"| REGISTRY["InFlightWorkRegistry"]
    REGISTRY -->|"WorkToken"| FILTER
    FILTER --> CONTROLLER["WorkController"]
    CONTROLLER -->|"response or failure"| FILTER
    FILTER -->|"close token"| REGISTRY

    COMMAND["POST /actuator/safeDrain"] --> ENDPOINT["SafeDrainEndpoint"]
    ENDPOINT --> COORDINATOR["DrainCoordinator"]
    COORDINATOR -->|"close/open admission"| REGISTRY
    COORDINATOR --> PARTICIPANTS["DrainParticipant implementations"]
    REGISTRY -->|"last token completed"| COORDINATOR
    COORDINATOR --> STATUS["DrainStatus"]
```

### Component responsibilities

- `InFlightWorkRegistry` owns the atomic boundary between admission and counting. A request is either admitted and counted or rejected; it cannot slip through while admission is closing.
- `WorkToken` represents ownership of one accepted operation and decrements its category exactly once when closed.
- `DrainCoordinator` owns state transitions, timestamps, the drain deadline, participant coordination, lifecycle events, and the `safeToTerminate` decision.
- `SafeDrainHttpFilter` applies configured path policy and owns each HTTP token until synchronous or asynchronous Servlet completion.
- `SafeDrainEndpoint` translates `DRAIN` and `RESUME` management commands into coordinator calls.
- `DrainParticipant` is the extension boundary for future workloads such as message consumers or scheduled jobs.

## Accepted request sequence

```mermaid
sequenceDiagram
    participant Client
    participant Filter as SafeDrainHttpFilter
    participant Registry as InFlightWorkRegistry
    participant Controller as WorkController

    Client->>Filter: POST /work
    Filter->>Registry: tryBegin("http")
    Registry-->>Filter: WorkToken (count = 1)
    Filter->>Controller: continue filter chain
    Controller-->>Filter: work response
    Filter->>Registry: WorkToken.close() (count = 0)
    Filter-->>Client: 200 OK
```

The registry tracks the Servlet request lifetime. The sample work-item map and its `PROCESSING` value are not inspected by the coordinator.

## Drain while a request is running

```mermaid
sequenceDiagram
    participant Work as Existing work request
    participant Registry as InFlightWorkRegistry
    participant Operator
    participant Endpoint as SafeDrainEndpoint
    participant Coordinator as DrainCoordinator
    participant NewWork as New work request

    Work->>Registry: token already active (count = 1)
    Operator->>Endpoint: POST {action: DRAIN}
    Endpoint->>Coordinator: startDrain()
    Coordinator->>Registry: closeAdmission()
    Coordinator-->>Operator: DRAINING, safeToTerminate=false
    NewWork->>Registry: tryBegin("http")
    Registry-->>NewWork: empty / rejected
    Note over NewWork: SafeDrainHttpFilter returns 503
    Work->>Registry: WorkToken.close() (count = 0)
    Registry->>Coordinator: evaluateCompletion()
    Note over Coordinator: state becomes DRAINED
```

## Lifecycle

```mermaid
stateDiagram-v2
    [*] --> ACTIVE
    ACTIVE --> DRAINING: DRAIN
    DRAINING --> DRAINED: all tracked work and participants drained
    DRAINING --> TIMED_OUT: deadline reached
    DRAINING --> ACTIVE: RESUME
    DRAINED --> ACTIVE: RESUME
    TIMED_OUT --> ACTIVE: RESUME
    ACTIVE --> ACTIVE: repeated RESUME
    DRAINING --> DRAINING: repeated DRAIN
    DRAINED --> DRAINED: repeated DRAIN
```

Both management commands are idempotent in their already-satisfied states. Repeated `DRAIN` during `DRAINING` or after `DRAINED` returns the existing snapshot. Repeated `RESUME` while `ACTIVE` returns the unchanged `ACTIVE` snapshot.

Resuming a drained task invalidates its earlier `safeToTerminate: true` result. Deployment tooling must therefore treat the newest status as authoritative.

## Thread safety

Registry acquisition and admission closure share one monitor. This prevents a request from observing open admission and incrementing after a drain has closed it. Each token uses an atomic closed flag, so duplicate Servlet completion callbacks cannot decrement twice.

The coordinator serializes state transitions, zero-work callbacks, timeout callbacks, participant snapshots, and resume operations with its lifecycle monitor. It schedules one timeout task and reacts to registry notifications instead of polling. Returned maps and lists are immutable copies.

## Participants and failures

Participants stop their own admission, report their safety condition, and resume when requested. Zero participants is valid. A participant failure while draining is recorded in `lastError` and prevents `DRAINED`. A participant status exception is represented as a non-drained `ParticipantDrainStatus`. A timeout changes the state to `TIMED_OUT` but never claims that termination is safe.

## Boundary with ECS

The current framework decides whether this process is safe to terminate. It does not request ECS task protection, change desired task count, receive deployment hooks, control ALB target registration, or prevent `SIGKILL`. An external deployment controller must stop routing new traffic, initiate drain early enough, observe the latest `safeToTerminate` value, and coordinate task termination.
