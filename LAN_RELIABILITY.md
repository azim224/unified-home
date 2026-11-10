# TCP discovery revision — 2026-11-10

This section supersedes the UDP discovery requirement below.

- PC and Android bootstrap explicit seed addresses over authenticated framed TCP,
  sustain known peers with TCP presence, and reply to non-reply TCP announcements.
  The optional `reply: true` announcement field prevents response loops. Older v2
  peers ignore that field. Membership secrets and wire framing are unchanged.
- Automatic bootstrap probes directly connected private IPv4 networks, using actual
  interface masks, with a bounded rotating 254-address sweep. Large subnets take
  multiple sweeps; explicit addresses remain the fast/deterministic option.
  No public-network scanning, cloud relay or Internet port forwarding is introduced.
- Presence/scan work is bounded separately from RPC delivery. Probes carry only
  signed announcements, never commands. Repeated discovery is safe; uncertain
  RPC execution is never retried or downgraded to UDP.
- UDP is an optional compatibility accelerator. A failed UDP bind no longer blocks
  TCP startup or Android requests/results. TCP listener recovery preserves the
  household session and replay/execution history.
- Both devices must still share a reachable network and allow inbound TCP on their
  household port (default 47654). Firewall/client isolation/VPN routing cannot be
  overridden by a transport library. Legacy UDP-only peers still need UDP.
- Android normalizes PC's `auto` role sentinel to its empty automatic choice and
  handles coordinator lookup failures before dispatch instead of throwing into
  the host's generic `home_route_unavailable` branch. This does not silently run
  locally after an uncertain remote request.

Validation: source review, Python AST parsing and whitespace checks. Kotlin syntax
was checked before the subsequent instruction forbidding toolchain execution;
final Kotlin changes have **not** been compiled. No Gradle, Android SDK, browser,
app server, device tests or APK builds were used after that instruction. Physical
PC–Android discovery, Windows firewall behavior and the original device exception
remain unverified. Install updated PC and Android plugin together to use TCP-only
bootstrap; the pasted invitation was not applied to local membership files.

---

# LAN transport and settings transfer — 2026-11-09

This document supersedes the UDP-only transport and no-settings-transfer statements
in the earlier HOME_PROTOCOL_V2/SHARED_HOME milestone notes.

## Presence and delivery

- Both platforms announce the existing authenticated v2 envelope over UDP every
  five seconds. Presence expires after 45 seconds. Discovery targets global and
  actual interface broadcasts plus up to 32 locally configured IPv4/DNS seeds.
- New/session-changed peers receive one unicast announcement so presence becomes
  bidirectional immediately. Seeds only provide routes: the household HMAC key
  and current recipient/sender sessions still authorize every request.
- Announcements add `tcp_port` (1024..65535), normally the same numeric port as
  UDP (47654). A listener receives one frame per TCP connection: four unsigned
  big-endian length bytes followed by 1..16384 bytes of the existing signed JSON
  envelope. Responses use a separate connection to the advertised peer address.
- RPC uses TCP when advertised; peers without `tcp_port` retain legacy UDP.
  Failed TCP delivery never downgrades or blindly repeats a side effect.
  TCP framing does not replace HMAC, session checks, replay checks or encryption.
- Connections and worker queues are bounded. Connect deadline is three seconds;
  complete inbound frame deadline is five seconds. Cancelled/expired pending
  requests are checked again before outgoing writes. Unknown execution remains
  unknown, not success.
- Listener faults recover automatically without discarding the current session,
  request execution history or replay protection. Membership/explicit stop still
  cancels work and rotates the session. Android link callbacks trigger discovery
  even without Internet validation. Android host declares multicast-lock permission.

## Local network controls

PC: **Дом → Адреса устройств**; Android: **Дом → Адреса для ручного поиска**.
Enter a device IPv4 address or hostname, not an invite, URL or port. A seed uses
this installation's configured household port. Devices must join the same home.
Allow both UDP and TCP on the configured port in the PC firewall. Wi-Fi client
isolation still blocks direct communication; discovery addresses do not override
router/firewall policy.

## Settings offers (encrypted private RPC)

Operation `settings_offer`, args exactly:

```json
{"transfer_id":"<32 lowercase hex>","settings":{"stt_api_key":"<user value>"}}
```

Accepted response is `{ok:true,data:{transfer_id,status:"pending_review"},error:""}`.
This confirms an offer queued for review, **not import**. There is no peer export,
peer approval or model-tool API for keys, permissions or import.

Both receivers default to refusing offers. A local human enables reception and
then approves each offer on the receiving device. Queue: at most eight offers,
180-second lifetime, memory only, bound to household/local/sender sessions.
Review lists field names only. Revocation/expiry/session change removes offers.
Deduplication prevents an already reviewed offer from silently applying again.
Import validates the entire patch before an atomic settings write. Absent fields
and unrelated permissions/profile/history remain untouched; empty keys are not
exported as deletion instructions. Secrets never enter discovery, normal status,
LLM tools or UI review values. AES-GCM uses the existing household private channel;
all household-key holders remain equally trusted cryptographically.

Shared Android/PC fields: `llm_api_key`, `stt_api_key`, `tts_api_key`, `llm_model`,
`stt_model`, `tts_model`, `tts_reference_id`, `stt_silence_seconds` (1.8..6),
`tts_speed` (0.5..2), `llm_temperature` (0..2).
Android exports raw entered preferences, never build-time/default secrets.
Android `llm_*` denotes Groq. PC sets the Groq endpoint explicitly when importing
these fields from Android, including that endpoint in local review. PC excludes
custom-provider LLM credentials/models when sending to Android. PC-only offers
may additionally contain `llm_base_url` and `tts_volume` (0..1).

PC private RPC dependencies now include cryptography (legacy Windows profile pins
3.4.8). Native plugin host contract adds human-only settings export/import methods;
the host and its exported native SDK must be updated together for plugin packaging.

## Voice coordination

Both platforms bound a round to 800 ms including lock contention, settling and
sampling. Elections are deterministic, share overlapping-round leases, validate
membership/session/capability view and subtract transit time from returned leases.
Conflicting manual targets, legacy peers, stale samples and unavailable decisions
do not authorize multiple microphones. Listener/responder selection requires a
fresh local detection sample, including manual choices. No scene approval is
implied by a voice election.

## Follow-up hardening

Queued RPCs now carry cancellation/deadline ownership, and inbound deadlines start
at admission. Same-session socket recovery uses the current route for results;
peer invalidation cancels obsolete work. PC peer lifetimes prevent expired work
becoming valid again after rediscovery. Routing checks household/session/consent
while work runs, and Android no longer holds its dedup mutex through host turns.
Scene planning projects repeated-device steps, serializes with execution, and
rechecks plan/session validity after waiting. Boolean power states are compared
strictly in the PC shared adapter/model matcher.

PC settings offers are actively purged without requiring an open panel. Importing
a different LLM endpoint clears old credentials/model unless replacements are
included, with an explicit review warning. This prevents forwarding a previous
provider's key to a new provider. Explicitly cleared LLM fields no longer fall
back to old launch configuration. Settings storage uses private file permissions,
reentrant updates and isolated cache snapshots.

Changes were reviewed statically. No compilation, application, browser or test
execution was performed for this revision.
