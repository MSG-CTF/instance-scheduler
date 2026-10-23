# Gateway transport and cleanup proof implementation plan

> **For agentic workers:** Use superpowers:executing-plans to implement and verify each task.

**Goal:** Preserve the selected Gateway v1 endpoint transport through Scheduler and retain resource reservations until cleanup is confirmed.

**Architecture:** Keep asynchronous creation, the existing worker pool, and public operation states. Add optional transport fields to endpoint DTO/storage. Keep failed cleanup reservations and provide an explicit operator retry with a persisted deletion generation so uncertain submissions keep their identity and terminal failures can start a new cleanup operation.

**Tech Stack:** Kotlin 2.2.21, Spring Boot 4.0.7, Java 24, JPA/Flyway, JUnit5.

**Spec:** User selected Gateway v1, one public endpoint on NodePort with private sidecars; Runtime design PR MSG-CTF/secure-provisioner#74 and issue #73. Paired Runtime implementation is separate.

## Global constraints

- Existing endpoint port remains the container port; connection_port is Gateway 443.
- Preserve protocol HTTP/TCP. TCP service_url uses tls://hostname:443; metadata hostname, connection_port, tls, server_name travels unchanged.
- Missing metadata remains null; existing stored JSON and responses do not gain null fields.
- DELETE success and explicit cleanup_completed=true are cleanup proofs. Failure, timeout, and retry exhaustion alone are not.
- Preserve the Gateway instance ID. Only the Runtime operation request ID changes on explicit operator cleanup retry.
- No live service, token, or worker-setting changes. Existing image/security validation remains unchanged.

## Review focus

- Old endpoint JSON decodes and round-trips without transport fields.
- Null/false/true transport flags retain their meaning through DTO, persistence, and API naming.
- Failed deletion and polling deadlines retain the reservation even when the workload ID is known.
- Uncertain submit retries never switch request identity; explicit retry survives restart.
- Operator retry cannot resurrect an active or cleaned instance or clear an unknown reservation.

## Task 1: Gateway metadata propagation

Files: RuntimeDtos.kt, ServiceEndpoint.kt, InstanceOperationService.kt, ServiceEndpointCodecTest.kt, HttpRuntimeClientTest.kt, InstanceOperationServiceTest.kt.

- [x] Add failing HTTP decode, storage round-trip/legacy JSON, and operation-to-query metadata tests.
- [x] Run focused tests and confirm they fail for the missing fields.
- [x] Add optional hostname, connectionPort, tls, serverName and non-null inclusion on stored/API endpoints. Copy all fields when applying results.
- [x] Run focused tests and verify legacy fixtures remain unchanged.

## Task 2: Cleanup proof and explicit retry

Files: InstanceOperationService.kt, Instance.kt, InstanceStateTransitionService.kt, InstanceCleanupController.kt, V22__add_cleanup_generation.sql, service/controller/transition tests.

- [x] Add failing tests for failed DELETE with/without proof, submit exhaustion, poll timeout, and operator retry.
- [x] Remove reservation release from unconfirmed failure paths; successful deletion and explicit proof still release once.
- [x] Persist cleanupGeneration=0. Keep initial request ID and uncertain retries stable; generate a generation suffix only after explicit operator retry.
- [x] Add retryCleanup(UUID) for eligible FAILED cleanup, transition to CLEANUP_PENDING, reset retry/poll state, preserve reservation and workload coordinates, and schedule the existing worker.
- [x] Expose authenticated POST /api/instances/{instanceId}/retry-cleanup and document the operator-only workflow.
- [x] Verify state/event ordering and inability to retry active/cleaned/non-cleanup failures. Preserve the existing explicit force-cleanup contract.

## Task 3: Verification and handoff

- [x] Run the complete Gradle test suite; separate Testcontainers skips from executed tests if local Docker is unavailable.
- [x] Check migration/model consistency and diff whitespace; review the final patch independently.
- [x] Open a draft Scheduler PR and link Runtime issue/PR plus Backend TLS forwarding follow-up. Do not merge/deploy.
