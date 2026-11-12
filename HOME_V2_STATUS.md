# Home v2 source ledger — 2026-11-11

Unreleased, no local compiler/build/test/application execution. Old published
1.1.1 remains unchanged. Fresh matching host SDK is REQUIRED for these sources.

## Integrated

- Separate v3 full-trust membership; no silent upgrade of v1 permissions.
- Framed AES-GCM TCP, UDP candidates, NSD _amalia-home._tcp. register/discover,
  serialized resolution with lifecycle fencing and validated numeric routes.
- Connectivity callbacks, debounced network recovery, retrying listener and
  resource cleanup. Discovery is not authentication or guaranteed reachability.
- Host catalogue/durable capability tasks, authenticated caller namespaces,
  task/status/context/profile-metadata tools. No secret-export model tools.
- SQLite shared data/tombstones/changefeed, bidirectional polling and merge.
- Android Keystore profiles, encrypted transfer management UI, full host capture,
  built-in Groq/custom/AssemblyAI/Fish apply and previous-state backup/restore.
  Native role selection/OS consent aren't imported as provider settings.
- Full history paging facade homeHistory on the new host; revision-bound cursor,
  query/session filter, explicit oversize failure, redacted message text.
  Old host fallback explicitly returns partial summaries.
- Durable assistant respond/speak/listen RPC receipts. Listen acceptance is NOT
  proof of active recording. Long agent work does not hold the lifecycle gate.
- Fixed-voter coordination service/pump, durable leases and restart fence,
  majority-backed immutable occurrence scene digests before scheduled dispatch.
- Schedule DAG executor, persistent run/step IDs, shared task namespace across
  leaders, target serialization/bounded parallelism, unknown/blocked outcomes.
- Management screen with nine-language copy, remote profile picker, local capture
  and fixed quorum configuration. New home screen retained; old screen rollback.

## Not claimed complete

Physical Android/TV/Windows7 networking, NSD OEM behavior, UI rendering/focus,
provider API operation and crypto/data interop are NOT verified. Fixed quorum
requires identical explicit configuration on each voter. No dynamic quorum edits,
individual revocation/guest roles or malicious-member isolation under PSK.

No completed automatic wake/listener election or cross-platform voice preference
UI; existing local Android wake path remains. PC uses local capture/selected
speech destination. No guarantee of one spoken answer during network partition.

Fixed-voter scheduling is source-integrated, not a proven exactly-once system:
unknown local runs stay stopped, external effects cannot be rolled back, edited
occurrences require exact quorum payload binding. Android floating-point scene
values fail closed for cross-platform digest compatibility. No safe bypass when
majority is absent. Full history has no implicit background replication.

Known operational limits: NSD legacy resolve cannot be force-cancelled; if OS
never calls back, UDP/manual remain. Continuous Android network permission,
foreground-service eligibility and router isolation require target verification.
Fresh Native SDK and CI compilation are mandatory; don't edit old SDK/stubs or
release metadata to pretend it is compatible. No push/live publication performed.
