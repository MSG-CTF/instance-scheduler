# Runtime image readiness preference

The Scheduler queries Runtime `POST /v1/image-readiness/query` after Broker candidate
screening. It sends one architecture, every unique immutable image from the stored
container specification (including helpers), and unique eligible target IDs. The
Broker candidate and reservation contracts are unchanged.

Readiness only changes preference inside an existing risk tier. Ready candidates
retain the existing instance-specific spreading order, followed by the other
candidates in their original spreading order. Architecture, capacity, risk, cost,
candidate validity, Runtime authentication, image approval and workload validation
still apply. Candidate expiry is checked again before reservation.

## Configuration

```yaml
scheduler:
  image-readiness:
    enabled: false
    lookup-timeout: 300ms
    cache-ttl: 10s
    max-observation-age: 60s
    # Optional separate Scheduler readiness credential. Empty uses scheduler.runtime.token.
    token: ${SCHEDULER_IMAGE_READINESS_TOKEN:}
  instance-policy:
    release-metadata-enabled: false
```

The readiness transport uses `scheduler.runtime.base-url` but has a separate HTTP
client and deadline. The deadline includes response-body transfer; create and
operation timeout settings are not reused. Redirects are disabled. A dedicated
response subscriber cancels transfer before buffering more than 256 KiB. A dedicated
readiness token should be supplied when Runtime separates readiness authorization
from create authorization. HTTP 401/403 produce a status-only warning; response
bodies, tokens and transport exception text are never logged by this client.

Disabled readiness performs no HTTP call. Missing targets, unknown observations,
timeouts, authorization failures, missing routes, server errors and invalid
responses give no preference and do not fail creation. Oversized requests (more
than 50 distinct targets or 32 distinct images) skip the hint entirely.

## Evidence validation and caching

The response architecture and `image_set_id` must match the exact request. The
identity hashes the contract's compact canonical JSON with sorted full image
references, `schema_version=1`, and architecture. A changed helper image or
architecture changes identity. Unexpected or duplicate response target IDs make
the entire response unusable. Counts must be nonnegative, ready must equal
`ready_node_count > 0`, and ready nodes cannot exceed eligible nodes.

Positive evidence requires `observed_at <= now < valid_until` and an observation
age strictly below `max-observation-age`. The initial age ceiling is 60 seconds,
matching the proposed Runtime readiness lifetime. Clock skew tolerance is zero:
future observations never grant preference. Adjust this ceiling together with
Runtime's observation/expiry settings after measurement.

The bounded in-process cache holds at most 512 batch keys. Positive entries expire
at the earliest of local TTL, the original Runtime expiry, and the observation-age
ceiling. Every read revalidates evidence at the current time. Neither lookup nor
cache hits rewrite or extend Runtime timestamps. Missing/negative hints do not
receive a positive-cache lifetime. A policy change can leave a positive hint
until this short cache expires; actual create still checks current Runtime policy.

## Durable create metadata and rollout

Migration V22 adds nullable `release_metadata_forwarded`. Before the first Runtime
create, Scheduler stores whether `release_id` and `architecture` will be forwarded.
The decision is reused for resubmission and reconcile after restart, regardless of
later flag changes. Existing provisioned rows with a null decision keep omitting
the fields. Full containers, architecture and release ID remain in the original
durable instance specification. Reset copies that original specification into its
new instance; a new forwarding decision is recorded before its own first create.

1. Deploy Runtime support for readiness query and optional create/reconcile
   `release_id` and `architecture` first. Older Runtime rejects unknown create keys.
2. Deploy Scheduler with both flags false and apply V22. Confirm Broker target IDs
   match Runtime Registry IDs and configure the authorized readiness credential.
3. Enable metadata forwarding only after Runtime support is deployed. Enable
   readiness separately for a pilot after observations and image policies are ready.
4. Measure cold/warm create latency, readiness p95/p99, cache behavior and registry
   pull load before broad enablement. No production performance claim is made by
   local HTTP tests.

An ambiguous Broker reservation is recovered with its saved request ID, candidate
and original request body before any new candidate/readiness lookup. Readiness
cannot move an in-flight reservation to a different target. Runtime create and
reconcile continue using the saved target and full original workload.
