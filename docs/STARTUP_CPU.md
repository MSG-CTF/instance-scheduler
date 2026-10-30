# Startup CPU rollout

Startup CPU defaults off. After deploying Broker's explicit downshift commit
and Runtime's reviewed CPU-resize support and image catalog, set
`SCHEDULER_INSTANCE_POLICY_STARTUP_CPU_BOOST_ENABLED=true`.

Migration V28 adds nullable `startup_cpu_millicores`; old instances retain the
normal path. New creates and resets select only exact reviewed digests and
their original base CPU. The selected peak is persisted before reservation and
does not change on retries or worker restart. Grade Tampering uses 300m/1000m,
Open House 500m/2000m and Cashout 250m/1000m. Author images and configuration are
unchanged. An unreviewed digest or changed base CPU stays on the normal path.

Candidate lookup and reservation use peak CPU and Kubernetes targets only.
Runtime receives peak CPU plus `workload.steady_cpu_millicores`. Its successful
operation must confirm the matching steady CPU; otherwise Scheduler retains
the reservation and enters the existing cleanup path without publishing access.

Only after confirmation does Scheduler commit steady `resource_profile` with
the original `startup_cpu_millicores` marker. Broker atomically reduces the
charged CPU while retaining original CREATE intent. Missing/failed commit
responses retry with the same request ID and body. Final release still follows
confirmed Runtime deletion. HELD TTL is unchanged; monitor concurrent-create
queue time and do not treat an expired hold as a successful commit.

For rollback, disable the switch for new requests, finish/delete opted-in
instances, then restore old binaries. V28 is additive and may remain installed.
