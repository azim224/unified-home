# Amalia Home LAN protocol v2 — implementation contract

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
