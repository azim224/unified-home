# Android Home v2 data extension

`android/src/main/kotlin/org/amalia/unifiedhome/v2/HomeV2Data.kt` is a standalone
privileged runtime extension. No Node/Entry wiring is included. PC references:
`/root/amalia-pc-v2/DATA.md` and `amalia_pc_v2/data.py`.

## Integration and lifecycle

Construct `HomeV2Data(context, host, nodeId, homeId)` for the current home (both
IDs: exactly 32 lowercase hexadecimal characters). While Node is **stopped**,
install `node.setOperationHandler(data.operations) { op, args, from ->
 data.handle(op, args, from) }`. The result is operation data, not a transport
envelope; `HomeV2Exception.code` is a sanitized error consumed by Node.

Node must authenticate encrypted v3 RPC, full-trust membership and replay policy
**before** calling this extension. `from` is trusted runtime context, not JSON
input; this extension only validates its identifier. It cannot authenticate a
UUID or independently inspect the Node's current membership. Fence handler
installation/use to this instance's `homeId`. Never call an old handler after
leave/rejoin, even if the node ID is unchanged. Stop Node, unregister the handler,
wait for its dispatched calls to finish, then `close()` the extension. Close is
idempotent; an in-flight call causes `data_busy`, not a forced unsafe close.
Create a fresh instance/partition for a new home. Do not migrate old home rows
implicitly. Constructor does synchronous opening/schema IO; construct off-main.
Handlers use Dispatchers.IO, a coroutine mutex and SQLite transactions. No
transaction spans a host suspension. No background sync or scheduler is started.

The DB lives under application-private **noBackupFilesDir** in
`home-v2-data-<node>-<home>/data.sqlite3`; metadata permanently verifies both IDs.
Separate homes and nodes have separate files, logs, cursors and profile keys.
SQLite DELETE journaling, FULL synchronous mode and memory temporary storage are
used. Data is not automatically deleted on leave; the runtime owns retention.
No schema upgrade beyond version 1, log pruning, tombstone GC or TTL is provided.
Only one live extension instance per partition is supported; in particular,
provider apply spans a host call and is not a multi-process transaction.

## Shared data: PC-compatible contract

Kinds are exactly `notes`, `tags`, `scenes`, `schedules`; scope is `home` or
`local`. IDs match `[A-Za-z0-9][A-Za-z0-9_.:-]{0,127}`. All argument and incoming
record fields are strict (unknown fields rejected). Values are JSON **objects**;
kind payloads are opaque and never executed.

| Operation | Required args | Optional args | Result |
|---|---|---|---|
| `data.get` | `scope,kind,id` | none | `{record}` |
| `data.put` | `scope,kind,id,value,expected_revision` | none | `{record,seq}` |
| `data.delete` | `scope,kind,id,expected_revision` | none | `{record,seq}` |
| `data.list` | `scope,kind` | `limit,cursor,include_tombstones` | `{revision,records,next_cursor}` |
| `data.changes` | `scope` | `after,limit,cursor` | `{revision,changes,next_cursor,after}` |
| `data.merge` | `home_id,records` | none | `{accepted,ignored,revision}` |

Record JSON is exactly:

```json
{"home_id":"<home hex32 or null for local>","scope":"home","kind":"notes","id":"shopping","revision":12,"origin":"<node hex32>","tombstone":false,"value":{"text":"Milk"}}
```

Put replaces the entire value; delete retains a durable empty-object tombstone.
Missing get/delete fails `record_not_found` (delete checks its expected version
first). Expected version is `0` for absent, otherwise the exact
`{revision:<positive integer>,origin:<hex32>}`; even a deleted key retains this
version. Mismatch fails `revision_conflict`, without implicit retry.

Revisions are device-wide persisted Lamport counters, not timestamps. Local
writes increment the observed clock; merge observes even losing remote counters.
The greatest `(revision,origin)` tuple wins, lexicographic ASCII origin tie-break.
An equal tuple with different content fails `lamport_collision` and rolls back
the entire batch. Every accepted mutation appends an immutable full record to an
AUTOINCREMENT sequence log in the same transaction. Sequences are local and
shared across scopes, not distributed revisions. Merge accepts 1..100 home
records only; envelope and all record homes must match. No local data, profiles
or host context can be imported by merge. Counter maximum is `2^63-2`.

List orders identifiers by SQLite BINARY ordering, excludes tombstones unless
`include_tombstones:true`, and returns the scope's latest accepted sequence as
`revision`. Limit defaults 50 and must be integer 1..100. Pages also stop at
48000 ASCII serialized item bytes. Scope mutation invalidates list cursors with
`stale_cursor`; another scope's mutation does not.

Change queries are interoperable: begin `{scope:"home",after:0,limit:50}`;
consume `changes:[{seq,record}]` and follow nonnull `next_cursor` with the same
scope (omit `after`). Cursors pin the initial high-water revision; later writes
are excluded. Once the cursor is null, save returned `after` as the completed
scope checkpoint for the next query. Never mix cursor/after, use another
partition's checkpoint, or save the high-water before finishing all pages.

Cursors are opaque Android-specific Base64 HMAC-SHA256 tokens, persistently
DB-keyed and filter/operation/revision bound; do not translate PC cursor contents.
Changes/list pagination is not full historical snapshot retention. Restoring an
older DB requires explicitly resetting peer checkpoints. Data does **not**
auto-sync: the parent runtime must poll/exchange authenticated change pages and
call merge. Nothing implements replicated schedule execution or exactly-once
side effects.

Arguments are bounded to 65536 canonical ASCII JSON bytes, record to 16384,
depth 12, 8192 visited values/keys and 1024 members per collection. Strings must
be valid Unicode, floating values finite, integral values within +/- `(2^63-2)`.
The serializer sorts keys and escapes non-ASCII, matching PC byte-budget intent.
Kotlin/JVM float spelling may differ from Python (e.g. exponent notation);
JSON numeric values are not cryptographic canonicalization across platforms.
Do not use these serialized strings as cross-platform hashes or signatures.
ASCII identifier ordering and tuple/version semantics are common.

## Profiles: encrypted immutable local versions

`profiles.import`, `profiles.export`, `profiles.list` follow the PC wire shape.
Import `{id,provider,label,values,expected_revision}` returns
`{node_id,id,version,provider,label}`. `provider` is an identifier, label nonempty
<=128 UTF-8 bytes. Values are 1..64 identifier keys to strings (empty permitted,
<=8192 UTF-8 bytes each, total <=16384 ASCII JSON bytes). Arbitrary PC provider
values may be **stored/exported**; importing does not apply/select them.
Expected revision is the numeric latest version for that ID or zero. Versions
are immutable, destination-owned; callers cannot choose source ownership.

List `{limit?,cursor?}` returns `{profiles,selection,revision,next_cursor}`;
metadata contains only `id,version,provider,label`, never keys/values/hashes.
Import or selection change invalidates list cursors. Export `{id,version}`
returns `{node_id,id,version,provider,label,values}` and **contains credentials**.
Never register profile operations as model tools, feed their output into prompts,
log args/results/exception text, or forward through unencrypted transport.
Membership grants full-trust runtime RPC authority; it does not make arbitrary
model-generated credential requests safe. Use an explicit separate allowlist.

### Why host apply is opt-in, not silently wired

The inspected HostServices declares `homeExportSettings` **LOCAL HUMAN UI ONLY**
and `homeImportSettings` **LOCAL HUMAN review approval ONLY**. Full-trust Node
membership cannot override that existing host API contract. Therefore the default
operations set deliberately excludes `profiles.apply`/`profiles.restore`;
direct invocation fails `profile_apply_unavailable`. Storage import/export/list
remain genuinely available. This is a deliberate integration limitation, not a
claim that selecting a row changes the running provider.

If the host explicitly introduces/approves a new HomeV2 membership-trust settings
contract, install `HomeV2Data.SettingsAdapter` **before** Node registration.
Its `exportSettings()` must return real raw user settings and `importSettings()`
must validate and atomically apply the whole canonical typed patch, throwing on
failure. Only after that host authorization may an adapter delegate export to
`host.homeExportSettings("{\"keys\":true,\"settings\":true}")` and import to
`host.homeImportSettings(patch.toString())`. This module does not call the old
methods under a new grant. Install no adapter on an unchanged legacy host.
Re-register Node operations if adapter availability changes while stopped.

With an authorized adapter, apply `{id,version,expected_revision}` and restore
`{expected_revision}` use numeric **selection** revision. Selection is
`{node_id,revision,selected:{id,version}|null,previous:{id,version}|null}`.
Apply remembers the previous exact ID/version; restoring swaps them (first apply
has no previous profile). Same selection is a no-op; no previous fails
`no_previous_profile`. Host success precedes selection commit; never fake success
on provider errors. No provider network authentication test is implied.

Android-native settings mapping is exact, no PC generic-key guessing:

* String keys: `llm_api_key`, `stt_api_key`, `tts_api_key`, `llm_model`,
  `stt_model`, `tts_model`, `tts_reference_id`. Nonblank, non-`***`, no control
  characters; max 2048 UTF-16 units for keys, 256 for other strings.
* String-encoded finite JSON numbers in PC `values` map to actual numeric host
  fields: `stt_silence_seconds` (1.8..6), `tts_speed` (0.5..2),
  `llm_temperature` (0..2). No whitespace/NaN/Infinity/numeric coercion.
* Patch: 1..10 keys, <=6000 UTF-8 bytes, strict host rules. Unknown values,
  generic PC `api_key`/`base_url`/`model`, custom endpoints, URLs and volume are
  rejected `unsupported_profile_values`, not discarded. Provider is metadata,
  not a new host backend selector. Current host `llm_*` is Groq; custom provider
  selection, app profiles and permissions are untouched.

Before changing the host, export and encrypt the **previous values of every
patched key** durably. Existing host export intentionally omits build/default
secrets, unset fields, and non-exportable settings. If any affected field is
absent, fail `profile_backup_incomplete` before import: patch-only import cannot
clear a newly introduced preference to restore its former absence. Do not claim
this is a complete effective-settings/BuildConfig backup. Unrelated settings
are not changed. Restoring a previous profile applies its patch, not a full
repository reset; backup is a separate safety record, not a synthetic profile.

A durable encrypted pending marker brackets the host call. Cancellation, host
exception (`profile_apply_unknown`) or crash may leave the provider outcome
ambiguous, and further applies fail `profile_apply_pending`. No automatic retry
occurs. A trusted **local human recovery** integration can call
`rollbackPendingLocally()` to atomically reapply the saved backup patch and keep
the old selection; failure leaves the pending marker intact. Alternatively,
inspect/reconcile actual host settings before calling
`resolvePendingLocally(applied)`; true commits intended selection, false keeps
old selection. The latter helper itself does not change or verify host settings.
Neither recovery helper may be RPC/model-accessible.
A separate database and host DataStore cannot provide one atomic transaction.

### At rest

Profile values and apply backup/pending records use Android Keystore AES-GCM
with a nonexportable per-partition key, random IV, 128-bit authentication tag,
and partition/row-associated data. Protection failures fail closed, no plaintext
fallback. No dependency is added. Requires Android Keystore AES-GCM (API 23+).
Hardware backing is not promised. Keystore invalidation/loss makes old encrypted
profiles unreadable; copying DB files is not portable profile export. The key
is retained on close/leave; deleting it requires an explicit retention policy.

**SQLite itself is not encrypted.** Notes, tags, scenes, schedules, immutable
change records, labels and other metadata remain plaintext in the app sandbox.
Use device encryption and protect explicit backups. noBackupFilesDir avoids
normal Android auto-backup, not root/device compromise. No credential
zeroization, rollback-resistant counters or hostile-filesystem sandbox claimed.

## Context: honest host subset, not PC history search

`context.search {query,limit?}` calls the actual `host.homeContext(query)`.
Query is <=256 UTF-8 bytes (empty allowed), limit defaults 50 (1..100). The host
currently selects up to three conversations using title/summary/recent-message
terms and may fall back to recent conversations; it also exports bounded device
tool definitions. This is neither complete conversation history nor PC substring
history search. There is no `history.append` or `history.search` advertisement.
`session_id` and `cursor` are explicitly rejected `unsupported_context_filter`.

Result is intentionally Android-specific:
`{context:{conversations?,device_tools?},next_cursor:null,partial:true,
truncated:<boolean>,capabilities:{source:"host.homeContext",full_history:false,
session_filter:false,pagination:false}}`. It retains actual host summary and
metadata objects; no synthetic PC `seq/at_ns/session_id` rows, revision or fake
pagination. Host raw output above 262144 UTF-8 bytes is refused; each exported
collection is limited to `limit` and 18000 ASCII item bytes. Unknown top-level
host fields are not forwarded; oversized/malformed individual objects fail
closed. Host redaction remains host-owned. No credential/settings APIs feed this
operation. Parent capabilities/UI must describe this as partial host context,
not advertise full-history browsing. Context responses remain private home RPC.

## Verification scope

Only source/API inspection and static final-file review were performed.
**No compilation, build, tests or app execution** were run. Android SQLite,
Keystore behavior, native cancellation/crash recovery, transport integration and
cross-device numeric edge cases remain runtime-unverified. Parent integration,
new authorized settings contract and local pending-recovery UI are not supplied
by these two files. Errors expose fixed codes, never raw exception text.
