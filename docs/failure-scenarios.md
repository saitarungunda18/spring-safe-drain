# Failure scenarios

## Termination during HTTP processing

An accepted protected request owns a token until its servlet lifecycle completes. Drain rejects later protected requests. If the deployment platform forcibly terminates the process before `DRAINED`, the framework cannot save the request; external grace periods must exceed the drain budget.

## Drain timeout

The coordinator enters `TIMED_OUT`, keeps admission closed, reports `safeToTerminate: false`, and retains diagnostic counts. An operator may investigate or abort to resume admission.

## Participant failure

A participant exception is recorded and cannot silently produce `DRAINED`. Status exceptions appear as non-drained participant details. Abort calls participants' `resume` methods; adapter authors should make resume idempotent and non-throwing.

## Duplicate drain requests

Concurrent or repeated requests share the same timestamps, deadline, and state. Participants are not asked to stop twice for one operation.

## Process crash

Memory, tokens, and coordinator state disappear. No in-process framework can complete a transaction after process death or prove remote side effects. Durable recovery belongs to the business system.

## Idempotency remains necessary

Clients, proxies, message brokers, databases, and process restarts can create ambiguous outcomes even after graceful draining. Use idempotency keys, unique constraints, transactional boundaries, and reconciliation appropriate to the workload. Safe draining reduces interruption risk; it does not create exactly-once semantics.
