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
    # Pause after timeout, 5xx, transport failure, invalid response or 401/403.
    failure-backoff: 30s
    # Pause after 404 (route missing) or 503 (Runtime has image preparation disabled).
    not-found-backoff: 5m
    # Required when enabled. Runtime answers the readiness route only to its dedicated
    # readiness token and returns 403 to the create token, so there is no fallback.
    token: ${SCHEDULER_IMAGE_READINESS_TOKEN:}
  instance-policy:
    release-metadata-enabled: false
```

`lookup-timeout` must stay within 1 second and both backoffs must be positive;
startup fails otherwise. With `enabled: true` and an empty `token`, startup fails
with the setting name in the message.

The readiness transport uses `scheduler.runtime.base-url` but has a separate HTTP
client and deadline. The deadline includes response-body transfer; create and
operation timeout settings are not reused. Redirects are disabled. A dedicated
response subscriber cancels transfer before buffering more than 256 KiB. A dedicated
readiness token is required because Runtime grants the readiness route only to that
credential. Response bodies, tokens and transport exception text are never logged;
failures log the status code or the exception class name only.

Disabled readiness performs no HTTP call. Missing targets, unknown observations,
timeouts, authorization failures, missing routes, server errors and invalid
responses give no preference and do not fail creation. Oversized requests (more
than 50 distinct targets or 32 distinct images) skip the hint entirely and are
recorded as `not_attempted`.

## Pausing lookups after failures

A failed lookup must not cost every following create its full deadline while
Runtime is down or not yet deployed. After a failure the Scheduler stops querying
for a while and proceeds with the existing order; cache hits are still served.

| Result | Recorded as | Pause |
|---|---|---|
| timeout | `timeout` | `failure-backoff` |
| 401, 403 | `unauthorized` | `failure-backoff`, one WARN per pause naming the token setting |
| 404, 503 | `not_found` | `not-found-backoff` |
| 5xx, transport failure, unreadable or invalid body | `error` | `failure-backoff`, one WARN per pause |
| other 4xx | `rejected` | none, one WARN each time the status changes (the request violates the contract) |
| during a pause | `skipped` | no HTTP call |
| request outside the lookup limits (image count, target count, non-immutable image) | `not_attempted` | no HTTP call |

The pause starts from the failure that triggered it; concurrent failures keep the
longest pause and do not log again. A 400 does not pause because waiting would not fix the
request.

## What the provisioning transition records

Every SCHEDULING to PROVISIONING transition detail ends with the readiness
decision so the effect of pre-pulled images can be measured against the
PROVISIONING dwell time of PR #74 events.

- `readiness=ready|not_ready|unknown` when Runtime answered: the state of the
  target that was actually reserved. `not_ready` means Runtime observed the target
  without the full image set; `unknown` means no usable observation (missing from
  the response, no `observed_at`, expired, or too old).
- `readiness=off|skipped|not_attempted|timeout|not_found|unauthorized|rejected|error`
  when no answer was used, with the meaning from the table above.
- `readiness=replay` when a pending reservation was recovered; readiness is never
  consulted on that path.
- `readinessCache=hit|miss` when a cached answer was served or an HTTP lookup ran; `skipped` carries no cache field.
- `readinessTargetMoved=true` when the Broker reservation moved the instance to a
  different target than the ranked candidate; the recorded state is that of the
  reserved target.

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

Migration V25 adds nullable `release_metadata_forwarded`. It follows the merged
Windows assignment V23 and Gateway cleanup V24 so existing deployments upgrade without out-of-order
migrations. Before the first Runtime
create, Scheduler stores whether `release_id` and `architecture` will be forwarded.
The decision is reused for resubmission and reconcile after restart, regardless of
later flag changes. Existing provisioned rows with a null decision keep omitting
the fields. Full containers, architecture and release ID remain in the original
durable instance specification. Reset copies that original specification into its
new instance; a new forwarding decision is recorded before its own first create.

1. Deploy Runtime support for readiness query and optional create/reconcile
   `release_id` and `architecture` first. Older Runtime rejects unknown create keys.
2. Deploy Scheduler with both flags false and apply V25. Confirm Broker target IDs
   match Runtime Registry IDs and configure the authorized readiness credential.
3. Enable metadata forwarding only after Runtime support is deployed. Enable
   readiness separately for a pilot after observations and image policies are ready.
4. Measure cold/warm create latency, readiness p95/p99, cache behavior and registry
   pull load before broad enablement. No production performance claim is made by
   local HTTP tests.

Before every Broker reservation attempt, including a new generation after an
EXPIRED response, Scheduler commits the exact request ID and candidate placement
as `pending_reservation` in a short transaction. The HTTP call runs outside that
transaction. A process exit after Broker acceptance therefore retains the original
intent even when no exception handler ran. A restart recovers that request and its
original durable body before any new candidate/readiness lookup.

Intent claims require the row to remain SCHEDULING with no competing intent and the
same scheduling retry time observed by the worker. Clearing an authoritative
rejection/expired intent and applying later outcomes also compare the exact intent
and retry time. A late worker leaves a newer intent or lease untouched. A HELD
intent remains until the PROVISIONING transaction commits its reservation and
target. Readiness cannot move an in-flight reservation to a different target.
Runtime create and reconcile continue using the saved target and full original
workload.
