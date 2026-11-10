# Amalia Home LAN protocol v2 — implementation contract

**Transport/settings update (2026-11-09):** [LAN_RELIABILITY.md](LAN_RELIABILITY.md)
adds framed TCP RPC, recoverable discovery, 45-second presence and encrypted,
locally approved settings offers; it supersedes older transport limitations below.

All peers explicitly join one household using a 256-bit shared secret. No legacy/v1 fallback. Creating/joining/leaving a home is a **local human action**, not an LLM tool. Invite format `amalia-home:v2:<home UUID hex 32>:<key hex 64>`. Never publish/log the invite/key in discovery, tools, ordinary config/status or errors. Shared-key membership gives every member equal authority; removal requires a new home/key and rejoining remaining devices. HMAC authenticates but does **not encrypt** LAN traffic; keep secrets/audio/dialog history out of packets.

UDP default 47654; max datagram 16384 bytes. Announcement every 5 sec, TTL 15 sec. Household does not exist until explicitly created/joined. Node IDs are durable; session is a fresh UUID hex on service restart AND membership change.

Envelope JSON fields: `v`=2, `home`=32 lowercase hex, `id`=node id (1..128 noncontrol chars), `session`=32 lowercase hex, `nonce`=32 lowercase hex, `at`=integer Unix milliseconds, `body`=standard Base64 of exact UTF-8 body JSON, `mac`=64 lowercase hex.

MAC: HMAC-SHA256(key bytes from hex, UTF8 of `amalia-home-v2\n{home}\n{id}\n{session}\n{nonce}\n{at}\n{body}`). Constant-time compare. Body is verified before JSON interpretation. Reject wrong home, invalid types, oversized payload, nonfinite JSON numbers, timestamps outside +/-60 sec, already seen (id,session,nonce). Bound replay entries at 4096, expire after 120 sec, reject new packets when full rather than evict unexpired entries. The unauthenticated transport/source IP is not identity.

Bodies:
- announce: `{t:"announce",name,role,priority:0..100,port:1024..65535,platform:"android"|"pc",services:["catalog","plan_scene","apply_plan"]}`
- request: `{t:"request",to:<nodeId>,to_session:<recipient session>,request_id:<UUID hex 32>,op:"catalog"|"plan_scene"|"apply_plan",args:{...}}`
- result: `{t:"result",to:<nodeId>,to_session:<recipient session>,request_id,op,ok:boolean,data:{...},error:<generic string>}`
- vote: `{t:"vote",round:<UUID hex 32>,score:0..100}`. Diagnostic only; not microphone integration.

Each authenticated announce carries peer session in envelope. Request/result accepted only from a live known member, matching its announced session AND `to`/`to_session` equal current receiver. Response binds request_id, op, peer ID and peer session to pending request. Request IDs remembered per session to prevent duplicate execution even if nonce differs. Requests are single-shot; **no blind automatic retries**. Timeout returns `status:unknown`, never remote success. Bounded pending requests/inflight work (16). Cancel pending on stop/membership change. Do not accept responses from former members/sessions.

RPC semantics:
- catalog args `{}` -> data `{devices:[{id,name,room,kind,actions,adapter}],scenes:[{id,title,description,steps:<count>}]}`. Max 40 of each, `truncated` boolean, no token/address/entity/state/history.
- plan_scene args `{scene:<exact id or exact title>}` -> ordinary plan JSON, local receiver stores token bound to requesting node ID + session, TTL at most 180 sec.
- apply_plan args `{token:<plan token>}` -> only consume a remotely created plan owned by this requester/session, return actual per-step execution summary. Caller must show remote plan and obtain human agreement before sending. Group membership does not bypass plan/consent. Remote callback must run off receive loop, never under mesh monitor.

Public membership status: `{joined:boolean,home_id:string,name:string}`; public mesh config excludes key/invite. Dedicated local create/join endpoints return no secret. Explicit local export action reveals invite for manual transfer, UI masks it by default. Never place membership secret in LLM tools. Home reset/leave clears peers, pending, remote plan ownership and sessions.

The current milestone is one interoperable home, authenticated presence, capability/catalog lookup and remote plan/apply with truthful outcomes. It is NOT audio streaming, automatic voice arbitration, global state replication, NAT traversal, or an always-on Android background service.

## Coordination extension v1 (2026-11-08)

The original scene protocol above remains valid. Updated announcements add
`coordination_version:1`; absence means legacy node. Updated `catalog` accepts
exactly `{coordination:{action:"context"}}`, or
`{coordination:{action:"sample"|"arbitrate",round:<hex32>}}`.
These are public routing metadata, never full private context. `context` returns
node_id, coordination_version, preferences (mode automatic/manual, brain,
listener, executor, responder, roles), capability_catalog, devices/scenes,
voice_hook, proximity_available, scope=public_metadata_only.
`sample` returns round, preferences and score (null or finite 0..100).
`arbitrate` returns round, coordinator, routing (four role IDs or null),
participants, lease_ms (1..2000), diagnostic_only. The minimum-ID compatible
node collects fresh peer samples after a 150ms settle, with bounded deadline;
overlapping detector IDs share a two-second decision. The actual host callback
checks local listener ownership before starting STT; diagnostic tools alone
cannot start microphones. PCM energy is not physical distance.

Android host now supports WakeArbitrationContributor before wake response/STT.
PC participates from button-started microphone capture, not always-on wake-word.
Remote brain/responder pipelines and encrypted private-context exchange are NOT
implemented. See `/root/amalia-pc/HOME_COORDINATION.md` for current limitations.

## Private encrypted RPC extension v1 (2026-11-08, supersedes earlier limitations)

No PC/server is required: Android peers implement this channel directly. Presence
adds secure_version=1. New HMAC-authenticated bodies secure_request/secure_result
carry AES-256-GCM encrypted JSON (8192-byte plaintext limit, 16384 outer packet).
HKDF-SHA256: extract salt=UTF8(home ID), IKM=household key; expand info=
`amalia-home/secure-rpc/aes-256-gcm/v1`, one block byte 0x01, 32-byte AES key.
Canonical Base64 nonce=12 random bytes, ciphertext includes 16-byte tag.
AAD ordered newline fields: `amalia-home-secure-rpc`, v, t, home, from,
from_session, to, to_session, request_id, at, timeout_ms, nonce.
All headers/session/replay/recipient checks remain required. Encryption does not
isolate malicious members sharing a household key. No retries, no plaintext fallback.

Private fixed operations: assistant_context {query}; assistant_respond
{request_id,text,context}; assistant_speak {request_id,text}; Android additionally
assistant_listen {request_id}. Each receiver requires its own local default-off
permission. Grants are never model tools or RPC mutations. Response envelope stays
{ok,data,error}. Timeouts mean uncertain outcome after send; listening_requested
means reservation, not proof STT started. See DISTRIBUTED_HOME.md for exact host
integration, bounds and remaining limitations. Actual Kotlin/Python cryptographic
interoperability was checked; physical Android networking remains unverified.
