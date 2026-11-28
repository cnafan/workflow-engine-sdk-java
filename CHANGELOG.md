# Changelog

## [Unreleased]

- The demo tests no longer depend on the ports checked into the agent cards and the credentials file. Both rewrote
  those files by replacing the fixed default port as text, so a developer who moved the demo ports made the rewrite
  silently do nothing: one test stopped rejecting a non-local endpoint, and the end-to-end demo left its login URLs
  aimed at ports the test does not own. Each URL is now rebuilt from its own scheme and path with only the authority
  replaced, so the tests pass whatever ports the working tree uses.
- The workbench reads ten ranked candidates instead of three, and a candidate that declares this host beats one that
  declares nothing. Retrieval ranks with a language model, so its top three is not stable: measured on a shared catalog,
  one run ranked a generic generated workflow above the SPN PSOP, the host accepted it because it declares no host, and
  the run failed on an agent the host does not run. Loading a candidate is cheap, so the host now reasons over a wider
  window and still refuses every candidate declared for another host.
- The execution observation sender no longer reports a dropped connection as an unreadable spool. A batch is read
  under the same lock the writer takes, so a file cannot be observed half-replaced, and a transport failure thrown by
  the HTTP send is rethrown unchecked and retried after one second, with a log line that names the connection instead
  of the spool. A real spool failure still retries, and the wording now says it may be the acknowledgement, not the read.
- SpringSpnDemo logs the workbench endpoint its AgentCard advertises instead of a fixed 26337, so changing the card
  port no longer leaves a log line pointing at a URL the demo never calls.
- Every task that starts is reported as ended exactly once, with success, failure or cancellation. A task
  cancelled while it was working, as `ANY_SUCCESS` does to the tasks that did not win, used to report no end at
  all, which left a consumer with a task that never finished. `task_response` now carries a `status` of `success`,
  `failed` or `cancelled` next to its `success` flag, `task_status_changed` reports the new `TaskStatus.CANCELLED`,
  and a cancelled task takes no place in the run's history because it produced no result. Execution reporting
  maps it to the outcome `cancelled` instead of passing it off as a failure.
- A loaded PSOP keeps the host it declares: `Workflow.getWorkflowHost()` and `Workflow.declaredHost()`. The declaration
  is `workflow_host`, or for older definitions the single agent named by their `SelfLoop` tasks, which the center reads
  as the host; a definition that names two hosts is refused, as it is by the center. A host that selects PSOPs by intent
  uses it to run only what it is declared to execute. The Spring Workbench checks its local AgentCard name: candidates
  are considered in rank order, one declared for another host is skipped with a log line, and when none is left the
  demo fails naming each candidate's host rather than running a workflow written for someone else.
- Building an event's content is writer work, never execution work. Walking outputs, redacting values, joining answers
  and truncating summaries now run on the writer thread, so a huge output costs the workflow nothing; the execution
  thread only queues identity and keeps references. A test records which thread walks the outputs and fails if the
  execution ever does it again.
- Reporting can never decide whether a run happens or what a host sees. The execution now reaches
  a reporter through a small `ObservationSink` seam and guards every call it makes: a failure to
  start reporting leaves the run unreported, a failure while capturing one event loses only that
  report (the event still reaches the host), and a failure reporting the outcome cannot break the
  completion chain. A test substitutes a failing sink to prove the run's result and event stream
  are identical without reporting.
- PSOP retrieval gets a longer response deadline than a load. The center ranks its whole catalog with a language model,
  so one search takes tens of seconds: measured against a 20-PSOP catalog, searches took 19-47 seconds and a third of
  them ran past the 30-second deadline a storage read allows, so the demo's retrieval failed intermittently.
  `LoadPsop.search` now defaults to a 120-second read deadline while `load` keeps 30 seconds; the explicit `Timeouts`
  overloads are unchanged.
- The Spring Workbench reuses its orchestration center connection for execution reporting and automatically captures
  redacted request and result summaries for node replay. Hosts using the lower-level `ExecutionReporter` can select
  their own capture policy with `ContentCapture`.
- Automatic reporting keeps the readable answer and never guesses at structured outputs: it uses
  `ContentCapture.summaries()`, because the center's contract requires the application to name every output key it is
  willing to share. A host that wants structured replay opts in with
  `new ContentCapture(12000, Set.of("conclusion"))` or `ContentCapture.all()`.
- An event that a task made too large is trimmed by decreasing value (bulky outputs, the rest of the outputs, then the
  summary) until it fits the center's limit, so a node never loses its identity, outcome or a short conclusion to a size
  rejection.
- Execution reporting explains itself: whether it is enabled (and why not), each confirmed execution, and every refused
  batch with the center's status and reason code. A conflict no retry can resolve (`SEQUENCE_OR_EVENT_ID_CONFLICT`,
  `EXECUTION_CLOSED`, `OWNER_MISMATCH`, ...)
  sets the run aside as rejected once instead of being retried forever, and records the center's reason in the rejection
  receipt. `CenterExecutionReporting` takes an optional spool directory, honours `OPENAN_EXECUTION_SPOOL` and
  `OPENAN_EXECUTION_INSTANCE` (system property first, then environment variable) for the default one, and exposes
  `isEnabled()`. The instance name gives each host process of the same account its own queue, so a second process starts
  instead of failing to own the first one's spool.
- `ContentCapture` is now a top-level class in `dev.openan.workflow.engine.runner` (it was nested in
  `ExecutionReporter`) and owns everything about reported content: the policy, which engine values are safe to disclose,
  and how an oversized event is trimmed. Write
  `ContentCapture.all()` instead of `ExecutionReporter.ContentCapture.all()`. It has the same shape as the Python SDK's
  `ContentCapture`, so the two SDKs read the same way.
- Keep the SPN workbench's notification subscription active across recovery results;
  business observers no longer trigger automatic result-based cancellation.
- Keep Notification-T subscriptions open independently of task/result terminal states; maintain
  stream activity tracking and explicit cancellation/resource ownership.
- Add independent registry HTTP authentication/TLS policy and orchestration account login for PSOP
  loading. These APIs are current-source capabilities, not part of the published 0.1.1 artifact.
- Refresh bilingual examples, release boundaries and constructor/transport ownership guidance.

## [0.1.2] — 2026-10-14

Released from tag `v0.1.2` (`c926446`). Compared with 0.1.1:

- Apply the configured blocking-call timeouts to the builder-built `DefaultRequestHandler`
  (a2a-java hardcodes 5s and never consults `A2AConfigProvider` on the builder path) and raise
  `A2AProperties` defaults to agent=900s / consumption=960s / reconciliation=60s.
- Echo requested supported extensions via the `A2A-Extensions` response header on success
  responses, so extension-aware clients can verify server participation.
- Keep workbench notification subscriptions state-independent from workflow task sessions.

## [0.1.1] — 2026-09-23

Released from tag `v0.1.1` (`952cde2`). Compared with 0.1.0:

- Harden workflow joins, cancellation, failure propagation and sensitive-data logging.
- Keep active notification streams alive beyond the ordinary request deadline and release
  streaming resources reliably. Later state-independent terminal-result handling is under Unreleased.
- Correct build-analysis exclusions and remove development-only vendor references from main.

## [0.1.0] — 2026-09-18

- Send SSE heartbeat comments on streaming endpoints (`message:stream`, `tasks/{id}:subscribe`) at a configurable
  interval (`a2at.server.heartbeat-interval-seconds`, default 15 seconds, `0` disables). Keeps intermediaries
  from idle-timing-out long-running SSE connections during agent execution; the heartbeat integrates with the
  emitter lifecycle and stops on stream completion, error, timeout, or client disconnect.
- Make collection fields on model types defensively copied and expose them through unmodifiable
  views (`Workflow`, `WorkflowStep`, `WorkflowSearchResult`, `ExecutionResult`, `SendMessageResult`,
  `TaskResult`, `BusinessFailure`, and the message records). Source- and binary-compatible; callers
  that mutate the collections returned by these getters now receive `UnsupportedOperationException`
  instead of silently corrupting engine-owned snapshots.
- Template the sample OMC agent credentials file: the tracked
  `samples/src/main/resources/spn_agent_credentials.json` is replaced by
  `spn_agent_credentials.example.json` (real file is now git-ignored; copy the example to run the
  demos). Missing credentials now fail with an actionable message instead of a bare NPE.

## [0.0.10] — 2026-09-16

- Upgrade the A2A-T SDK dependency to 1.1.1 (patch release, no breaking API changes).
- Make the fallback task poll interval configurable via `WorkflowEngineClientConfig.taskPollIntervalMillis`
  (default 20 seconds). Polling only triggers when a streaming response is interrupted before the task
  reaches a terminal state; while SSE is alive, polling does not start.
- Wire host-declared `TaskAuthorizationProvider` beans into the starter's autoconfigured request handler.
  Every A2A operation (message send/stream, task query, cancel, subscribe) is offered to the provider
  before the agent executor runs; rejections surface as standard A2A error envelopes.

## [0.0.9] — 2026-09-14

- Make Negotiation-T activation host-owned. The business activates Negotiation-T per send via the new
  `MessageContent.withExtension(...)`; the engine mirrors that selection into `message.extensions` and the
  `A2A-Extensions` request header and validates that the target AgentCard declares the extension before dispatch.
  An unsolicited negotiation (host did not activate Negotiation-T) fails fast instead of silently entering the
  auto-loop.
- Accept Negotiation-T Propose carried on a taskless bare message (A2A-T pre-task negotiation, where the remote
  proposes before creating any task): `onNegotiation` now also fires for a valid Propose without an
  `INPUT_REQUIRED` task status. The exchange is correlated by contextId and negotiation id, and the follow-up send
  carries no taskId. Invalid negotiation metadata on a bare message now fails explicitly instead of being silently
  treated as a normal successful response.
- Converge non-functional formatting and javadoc drift between `main` and `dev` so the branches differ only in the
  instruction-platform integration.

## [0.0.8] — 2026-09-13

- Scope task lifecycle callbacks to each `sendTask` invocation so concurrent workflow executions
  cannot overwrite or receive one another's protocol events.
- Cancel pending task polls and acknowledgement timers when an invocation, subscription, client or
  transport finishes; this prevents delayed callbacks from surviving resource shutdown.
- Track raw transport activity separately from decoded Notification-T business events. Standard SSE
  comment heartbeats refresh `heartbeat().lastEventAt()` without increasing `eventCount`; use the
  new `lastBusinessEventAt()` accessor for business-event liveness.
- Add configurable connection/read deadlines to `LoadPsop`, and reject ambiguous simultaneous
  `credentialsConfigPath` and inline `credentialsConfig` configuration.
- Clarify `DefaultExtensionSender` ownership: its public constructor owns the supplied transport;
  callers that share a transport must use `DefaultExtensionSender.nonOwning(...)`.

## [0.0.7] — 2026-09-13

- Set the source-build version to `0.0.7-SNAPSHOT`. Maven Central version `0.0.6` was published from the same source
  commit as `0.0.5`; it does not contain the APIs listed in this `0.0.7` section.
- Preserve the published `spring-boot-starter` artifact coordinate. The temporary, unpublished
  `spring-boot-a2a-starter` name is not a consumable Maven Central coordinate.

- Add `WorkflowEngineClient.sendTask(...)` for task execution outside a DAG. It reuses the normal
  task state machine, keeps one task/context through Negotiation-T, and supports a per-call
  `NegotiationStrategy`. Local interaction failures cancel a known non-final remote task before
  releasing the conversation. The internal workflow dispatch operation and the former
  `sendMessage(...)` alias are no longer part of the public `WorkflowEngineClient` contract.
- Increase the default Notification-T acknowledgement timeout from 5 seconds to 5 minutes and expose the sample Spring
  setting as `a2a.notification-ack-timeout-seconds` /
  `A2A_NOTIFICATION_ACK_TIMEOUT_SECONDS`.

## [0.0.6] — 2026-09-09

- Published from the same source commit as `0.0.5`; no APIs or behavior changed.

## [0.0.5] — 2026-09-09

### Breaking: per-edge conditional routing

- `onRoute` now evaluates one conditional edge at a time: `RouteRequest` describes a single edge (step, condition,
  upstream window) and the callback returns `RouteDecision.allow()` /
  `deny()` for that edge. Unconditional edges always run and bypass the callback; allowed conditional edges activate in
  parallel with them. This replaces the previous N-choose-1 candidate selection (`RouteDecision.nextStep` and
  `RouteRequest.candidates` are removed)
  and fixes mixed-edge steps, where all unconditional edges were not previously guaranteed to activate.
- Workflow validation now rejects duplicate outgoing targets on one step, null edges and blank targets.
- After all route decisions succeed, `route_decision` is emitted once per outgoing edge, including unconditional edges,
  instead of once per step. A callback failure emits the workflow error and no partial per-edge decisions.

## [0.0.4] — 2026-09-08

- Make the Spring Boot A2A server auto-configuration opt-in: server beans are registered only
  when `a2at.server.enabled=true` (previously on by default). Host applications that expose A2A
  endpoints must now set the property explicitly.
- Open `A2ASlashActionAliasController` for host-side registration: public, non-final class with
  a public constructor, so applications outside the starter package can instantiate, replace or
  proxy it (`@ConditionalOnMissingBean` replacement was previously unreachable from host code).

## [0.0.3] — 2026-09-07

- Release streaming resources when publisher subscription setup throws synchronously.
- Align published dependency examples with 0.0.2 and refresh neutral architecture terminology.
- Update GitHub Actions to Node 24-compatible action versions.
- Add optional slash-style alias endpoints for A2A actions, disabled unless
  `a2at.server.slash-action-aliases-enabled=true`.

## [0.0.2] — 2026-09-07

- Publish the main-branch baseline under `net.openan.workflow.sdk`, including the parent POM,
  workflow-engine and spring-boot-starter. This tag has the same source tree as 0.0.1.

## [0.0.1] — 2026-09-04

- Use published A2A-T 1.1.0 from Maven Central; remove SDK source checkout/install from CI; move content generation,
  validation, templates and SDK initialization to the host.
- onTask returns final MessageContent; onNegotiation returns Send/Stop. Remove engine profiles and content handlers.
- Preserve complete ReceivedMessage and metadata layers alongside deterministic convenience outputs; keep local nested
  multi-output values.
- Deduplicate negotiation rounds, separate resource budgets from protocol rounds, and suppress late sends after
  timeout/cancellation.
- Separate authorization/notification lifecycle from workflow outcomes; never synthesize a subscription ACK.
- Observe serialized HTTP/JSON-RPC, real gRPC metadata/protobuf and dev vendor SDK traffic with mandatory redaction and
  bounded SSE.
- Add missing-input negotiation and independent transport-path regression tests; live model and production endpoint
  validation remains separate.
- Refresh bilingual callback, architecture and integration contracts. No older SDK compatibility layer.

### Release hardening

- Propagate workflow cancellation and close execution-owned resources exactly once.
- Anonymize orchestration access tokens and bound registry response deadlines.
- Preserve multi-value results from arbitrary terminal workflow nodes.
- Map pre-task failures from the standard A2A error envelope while keeping post-creation failures in task state.
- Publish a compiled host integration example and require sample regression tests.
- Keep documentation host-neutral; sample class and AgentCard identifiers remain unchanged.

Published Maven artifacts use the version selected by the release tag. The default source-build
`revision=0.1.0-SNAPSHOT` is not a Maven Central release version.
