/*
 * Copyright (c) 2026 Huawei Technologies Co., Ltd.
 * All Rights Reserved.
 *
 * SPDX-License-Identifier: Apache-2.0
 *
 *    Licensed under the Apache License, Version 2.0 (the "License"); you may
 *    not use this file except in compliance with the License. You may obtain
 *    a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 *    WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 *    License for the specific language governing permissions and limitations
 *    under the License.
 */

package dev.openan.workflow.engine.examples.workbench;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.openan.workflow.engine.client.A2AJavaClientRuntime;
import dev.openan.workflow.engine.client.A2ATransport;
import dev.openan.workflow.engine.client.AuthProvider;
import dev.openan.workflow.engine.client.DefaultWorkflowEngineClient;
import dev.openan.workflow.engine.client.WireLog;
import dev.openan.workflow.engine.client.WorkflowEngineClient;
import dev.openan.workflow.engine.client.WorkflowEngineClientConfig;
import dev.openan.workflow.engine.control.EventCallback;
import dev.openan.workflow.engine.control.EventType;
import dev.openan.workflow.engine.examples.negotiation.NegotiationStrategy;
import dev.openan.workflow.engine.model.ExecutionResult;
import dev.openan.workflow.engine.model.JumpCondition;
import dev.openan.workflow.engine.model.StepType;
import dev.openan.workflow.engine.model.Task;
import dev.openan.workflow.engine.model.Workflow;
import dev.openan.workflow.engine.model.WorkflowSearchResult;
import dev.openan.workflow.engine.model.WorkflowStep;
import dev.openan.workflow.engine.registry.CenterExecutionReporting;
import dev.openan.workflow.engine.registry.HttpAuthProvider;
import dev.openan.workflow.engine.registry.LoadPsop;
import dev.openan.workflow.engine.registry.TlsPolicy;
import dev.openan.workflow.engine.runner.ExecutePsop;
import dev.openan.workflow.engine.runner.ExecutionReporter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.a2aproject.sdk.spec.AgentCard;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Workflow orchestration for the SPN cross-city diagnosis.
 *
 * <p>Single responsibility: coordinate the full orchestration pipeline -- load agent cards,
 * search/load PSOP, create the task-scoped engine client, and run the workflow. Each sub-step
 * delegates to a dedicated collaborator:
 *
 * <ul>
 *   <li>{@link WorkbenchControlPoint} -- workflow decision callbacks (task dispatch, routing)
 *   <li>{@link NegotiationStrategy} -- typed negotiation decisions (injected into the control
 *       point)
 * </ul>
 *
 * <p>This class does NOT handle agent server I/O (that stays in the Spring-managed {@code
 * SpringWorkbenchExecutor}) or the details of any single A2A-T extension protocol.
 */
public class WorkbenchOrchestrator {

  private static final Logger log = LoggerFactory.getLogger(WorkbenchOrchestrator.class);
  public static final String SPN_PSOP_ID = "psop_spn_cross_city_diagnosis";

  /** Ranked candidates inspected before giving up: ranking moves, and the host still filters. */
  private static final int SEARCH_CANDIDATES = 10;

  private final String orchUrl;
  private final String credentialsPath;
  private final boolean sslVerify;
  private final String a2atEnvPath;
  private final A2AJavaClientRuntime clientRuntime;
  private final AuthProvider authProvider;
  private final CenterAccess centerAccess;
  private final CenterExecutionReporting reporting;

  public WorkbenchOrchestrator(
      String orchUrl, String credentialsPath, boolean sslVerify, String a2atEnvPath) {
    this(orchUrl, credentialsPath, sslVerify, a2atEnvPath, null, null);
  }

  public WorkbenchOrchestrator(
      String orchUrl,
      String credentialsPath,
      boolean sslVerify,
      String a2atEnvPath,
      A2AJavaClientRuntime clientRuntime) {
    this(orchUrl, credentialsPath, sslVerify, a2atEnvPath, clientRuntime, null);
  }

  public WorkbenchOrchestrator(
      String orchUrl,
      String credentialsPath,
      boolean sslVerify,
      String a2atEnvPath,
      A2AJavaClientRuntime clientRuntime,
      AuthProvider authProvider) {
    this(
        orchUrl,
        credentialsPath,
        sslVerify,
        a2atEnvPath,
        clientRuntime,
        authProvider,
        new CenterAccess(HttpAuthProvider.none(), TlsPolicy.strict(), false));
  }

  /**
   * Configures center access independently of agent transport credentials and TLS. A supplied
   * runtime is owned by this single execution, including preparation failures.
   */
  public WorkbenchOrchestrator(
      String orchUrl,
      String credentialsPath,
      boolean sslVerify,
      String a2atEnvPath,
      A2AJavaClientRuntime clientRuntime,
      AuthProvider authProvider,
      CenterAccess centerAccess) {
    this(
        orchUrl,
        credentialsPath,
        sslVerify,
        a2atEnvPath,
        clientRuntime,
        authProvider,
        centerAccess,
        null);
  }

  public WorkbenchOrchestrator(
      String orchUrl,
      String credentialsPath,
      boolean sslVerify,
      String a2atEnvPath,
      A2AJavaClientRuntime clientRuntime,
      AuthProvider authProvider,
      CenterAccess centerAccess,
      CenterExecutionReporting reporting) {
    this.orchUrl = orchUrl;
    this.credentialsPath = credentialsPath;
    this.sslVerify = sslVerify;
    this.a2atEnvPath = a2atEnvPath;
    this.clientRuntime = clientRuntime;
    this.authProvider = authProvider;
    this.centerAccess = Objects.requireNonNull(centerAccess, "centerAccess");
    this.reporting = reporting;
  }

  static String buildResultText(ExecutionResult result) {
    if (result.isSuccess()) {
      if (result.getHistory() != null) {
        for (int i = result.getHistory().size() - 1; i >= 0; i--) {
          Map<String, Object> last = result.getHistory().get(i);
          if (last.get("outputs") instanceof List<?> outputs && !outputs.isEmpty()) {
            Object step = last.get("step");
            return result.getHistory().stream()
                .filter(entry -> Objects.equals(step, entry.get("step")))
                .filter(entry -> entry.get("outputs") instanceof List<?>)
                .flatMap(entry -> ((List<?>) entry.get("outputs")).stream())
                .map(WorkbenchOrchestrator::renderOutput)
                .collect(Collectors.joining("\n\n"));
          }
        }
      }
    }
    StringBuilder sb = new StringBuilder();
    sb.append("Workflow execution ")
        .append(result.isSuccess() ? "succeeded" : "failed")
        .append(".\n");
    if (result.getHistory() != null) {
      for (Map<String, Object> h : result.getHistory()) {
        sb.append("- Step: ")
            .append(h.get("step"))
            .append(", Agent: ")
            .append(h.get("agent"))
            .append(", Status: ")
            .append(h.get("status"))
            .append("\n");
        appendFailureDetails(sb, h);
      }
    }
    if (result.getError() != null) {
      sb.append("Error: ").append(result.getError());
    }
    return sb.toString();
  }

  // The host renders safe engine failure facts, never treats them as diagnosis output or LLM input.
  private static void appendFailureDetails(StringBuilder text, Map<String, Object> history) {
    for (String field : List.of("errorCode", "error", "errorDetails")) {
      Object value = history.get(field);
      if (value == null || value instanceof Map<?, ?> map && map.isEmpty()) continue;
      String rendered = renderOutput(value);
      if (!rendered.isBlank()) {
        text.append("  ").append(field).append(": ").append(WireLog.redact(rendered)).append("\n");
      }
    }
  }

  private static String outputText(Map<String, Object> output) {
    if (output == null || output.isEmpty()) {
      return "";
    }
    return output.values().stream()
        .filter(value -> value != null && !String.valueOf(value).isBlank())
        .flatMap(value -> value instanceof List<?> values ? values.stream() : Stream.of(value))
        .map(WorkbenchOrchestrator::renderOutput)
        .collect(Collectors.joining("\n\n"));
  }

  private static String renderOutput(Object value) {
    if (value instanceof String text) return text;
    try {
      return new ObjectMapper().writeValueAsString(value);
    } catch (JsonProcessingException error) {
      throw new IllegalArgumentException("Cannot serialize business output", error);
    }
  }

  static String buildResultText(ExecutionResult result, Workflow workflow) {
    if (result.isSuccess() && result.getStepOutputs() != null) {
      String terminalOutputs =
          workflow.getSteps().stream()
              .filter(step -> result.getStepOutputs().containsKey(step.getName()))
              .filter(
                  step ->
                      step.getNext() == null
                          || step.getNext().stream()
                              .noneMatch(
                                  next -> result.getStepOutputs().containsKey(next.getStep())))
              .map(step -> outputText(result.getStepOutputs().get(step.getName())))
              .filter(output -> !output.isBlank())
              .collect(Collectors.joining("\n\n"));
      if (!terminalOutputs.isBlank()) return terminalOutputs;
    }
    return buildResultText(result);
  }

  static Workflow fallbackWorkflow() {
    WorkflowStep city1 =
        WorkflowStep.builder()
            .name("diagnosis_city1")
            .layer(0)
            .subtasks(
                List.of(
                    Task.builder()
                        .agent("SPN Domain Agent City1")
                        .description("SPN专线故障诊断")
                        .build()))
            .next(List.of(JumpCondition.builder().step("merge_analysis").condition("").build()))
            .build();
    WorkflowStep city2 =
        WorkflowStep.builder()
            .name("diagnosis_city2")
            .layer(0)
            .subtasks(
                List.of(
                    Task.builder()
                        .agent("SPN Domain Agent City2")
                        .description("SPN专线故障诊断")
                        .build()))
            .next(List.of(JumpCondition.builder().step("merge_analysis").condition("").build()))
            .build();
    WorkflowStep merge =
        WorkflowStep.builder()
            .name("merge_analysis")
            .layer(1)
            .stepType(StepType.SELF_LOOP)
            .contextFrom(List.of("diagnosis_city1", "diagnosis_city2"))
            .subtasks(
                List.of(Task.builder().agent("Workbench").description("汇总两地市OMC诊断结论").build()))
            .next(List.of(JumpCondition.builder().step("endNode").condition("").build()))
            .build();
    return Workflow.builder()
        .id(SPN_PSOP_ID)
        .name("SPN跨地市专线投诉诊断")
        .description("两个地市并行诊断后由工作台本地汇总")
        .steps(List.of(city1, city2, merge))
        .build();
  }

  private static long elapsedMillis(long startedNanos) {
    return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNanos);
  }

  /** Run the full orchestration pipeline and return the result text. */
  public String run(String messageText) throws Exception {
    return run(messageText, Boolean.getBoolean("a2at.samples.negotiation"));
  }

  /** Runs with an explicit sample-host negotiation setting, without changing global properties. */
  public String run(String messageText, boolean demoNegotiationEnabled) throws Exception {
    return run(messageText, demoNegotiationEnabled, new ExecutionCancellation());
  }

  String run(String messageText, boolean demoNegotiationEnabled, ExecutionCancellation cancellation)
      throws Exception {
    return run(messageText, demoNegotiationEnabled, cancellation, ignored -> {});
  }

  String run(
      String messageText,
      boolean demoNegotiationEnabled,
      ExecutionCancellation cancellation,
      Consumer<String> executionStarted)
      throws Exception {
    A2ATransport transport = null;
    try {
      cancellation.check();
      long runStarted = System.nanoTime();
      String runtimeName =
          clientRuntime != null
              ? clientRuntime.getClass().getSimpleName()
              : "DefaultA2AJavaClientRuntime";
      log.info(
          "[Orchestrator] START orchUrl={}, runtime={}, sslVerify={}, inputChars={}",
          orchUrl,
          runtimeName,
          sslVerify,
          messageText != null ? messageText.length() : 0);

      long stageStarted = System.nanoTime();
      log.info("[Orchestrator] STAGE_START stage=load-agent-cards");
      List<AgentCard> agentCards = loadAgentCards();
      cancellation.check();
      log.info(
          "[Orchestrator] STAGE_DONE stage=load-agent-cards, count={}, agents={}, elapsedMs={}",
          agentCards.size(),
          agentCards.stream().map(AgentCard::name).toList(),
          elapsedMillis(stageStarted));

      stageStarted = System.nanoTime();
      log.info("[Orchestrator] STAGE_START stage=search-load-psop");
      ResolvedWorkflow resolvedWorkflow = resolveWorkflow(messageText);
      cancellation.check();
      String psopId = resolvedWorkflow.psopId();
      Workflow workflow = resolvedWorkflow.workflow();
      ExecutionReporter reporter =
          reporting == null ? null : reporting.forPsop(resolvedWorkflow.loaded());
      if (reporting != null && reporter == null) {
        log.warn("[Orchestrator] Execution reporting unavailable for psopId={}", psopId);
      }
      log.info(
          "[Orchestrator] STAGE_DONE stage=search-load-psop, psopId={}, workflow={}, "
              + "steps={}, elapsedMs={}",
          psopId,
          workflow.getName(),
          workflow.getSteps().size(),
          elapsedMillis(stageStarted));

      stageStarted = System.nanoTime();
      log.info("[Orchestrator] STAGE_START stage=create-engine-client, runtime={}", runtimeName);
      transport =
          new A2ATransport(
              agentCards,
              clientRuntime,
              WorkflowEngineClientConfig.builder()
                  .sslVerify(sslVerify)
                  .credentialsConfigPath(credentialsPath)
                  .authProvider(authProvider)
                  .build());
      log.info(
          "[Orchestrator] STAGE_DONE stage=create-engine-client, contextId={}, elapsedMs={}",
          transport.getContextId(),
          elapsedMillis(stageStarted));
      try {
        WorkflowEngineClient engineClient = new DefaultWorkflowEngineClient(transport);

        stageStarted = System.nanoTime();
        log.info(
            "[Orchestrator] STAGE_START stage=execute-workflow, contextId={}, "
                + "psopId={}, workflow={}",
            transport.getContextId(),
            psopId,
            workflow.getName());
        WorkbenchControlPoint controlPoint =
            new WorkbenchControlPoint(
                a2atEnvPath, new NegotiationStrategy(a2atEnvPath), demoNegotiationEnabled);
        cancellation.check();
        CompletableFuture<ExecutionResult> execution =
            ExecutePsop.builder()
                .psop(workflow)
                .reporter(reporter)
                .controlPoint(controlPoint)
                .engineClient(engineClient)
                .runtimeIntent(messageText)
                .lang("zh")
                .eventCallback(createLogCallback(transport.getContextId(), executionStarted))
                .onFinish(
                    (r, events) -> {
                      log.info(
                          "[Orchestrator] WORKFLOW_FINISH success={}, history={}, events={}",
                          r.isSuccess(),
                          r.getHistory() != null ? r.getHistory().size() : 0,
                          events.size());
                      return CompletableFuture.completedFuture(null);
                    })
                .execute();
        cancellation.bind(execution);
        ExecutionResult result = execution.join();
        cancellation.check();
        log.info(
            "[Orchestrator] STAGE_DONE stage=execute-workflow, contextId={}, success={}, "
                + "history={}, elapsedMs={}",
            transport.getContextId(),
            result.isSuccess(),
            result.getHistory() != null ? result.getHistory().size() : 0,
            elapsedMillis(stageStarted));
        String resultText = buildResultText(result, workflow);
        log.info(
            "[Orchestrator] DONE contextId={}, success={}, resultChars={}, elapsedMs={}",
            transport.getContextId(),
            result.isSuccess(),
            resultText.length(),
            elapsedMillis(runStarted));
        if (!result.isSuccess()) {
          throw new IllegalStateException(resultText);
        }
        return resultText;
      } catch (Exception e) {
        log.error(
            "[Orchestrator] FAILED contextId={}, elapsedMs={}, errorType={}, message={}",
            transport.getContextId(),
            elapsedMillis(runStarted),
            e.getClass().getSimpleName(),
            e.getMessage(),
            e);
        throw e;
      }
    } finally {
      if (transport != null) {
        log.info("[Orchestrator] TRANSPORT_CLOSE contextId={}", transport.getContextId());
        transport.close();
      } else if (clientRuntime != null) {
        // Preparation/cancellation/constructor failure: transport never took ownership.
        try {
          clientRuntime.close();
        } catch (RuntimeException closeError) {
          log.warn(
              "[Orchestrator] RUNTIME_CLOSE_FAILED errorType={}",
              closeError.getClass().getSimpleName());
        }
      }
    }
  }

  private List<AgentCard> loadAgentCards() {
    return new WorkbenchAgentCatalog().load();
  }

  ResolvedWorkflow resolveWorkflow(String messageText) throws Exception {
    if (centerAccess.localWorkflowEnabled()) {
      log.warn(
          "[Orchestrator] LOCAL_DEMO_WORKFLOW psopId={} (center access explicitly disabled)",
          SPN_PSOP_ID);
      return new ResolvedWorkflow(SPN_PSOP_ID, new LoadPsop.LoadedPsop(fallbackWorkflow(), null));
    }
    // The PSOP comes from the task's own intent. Pinning one in configuration would bypass the
    // retrieval this host exists to exercise, and would hide a search that stopped working.
    List<WorkflowSearchResult> results =
        LoadPsop.search(
            orchUrl, messageText, SEARCH_CANDIDATES, centerAccess.auth(), centerAccess.tls());
    if (results.isEmpty())
      throw new IllegalStateException("Orchestration center returned no matching workflow");
    // Relevance only says a PSOP may fit the intent. Ranking is a model's opinion and moves
    // between calls, so the best match can be one this host is not declared to execute, or a
    // generic workflow that declares no host and needs agents this host does not run. Candidates
    // are loaded in rank order and a declaration for this host wins over the absence of one.
    // A center failure while loading a candidate is not a reason to look at the next one.
    List<LoadPsop.LoadedPsop> candidates = new ArrayList<>();
    List<WorkflowSearchResult> candidateResults = new ArrayList<>();
    List<String> skipped = new ArrayList<>();
    for (WorkflowSearchResult result : results) {
      String psopId = result.getWorkflowId();
      LoadPsop.LoadedPsop loaded =
          LoadPsop.loadForExecution(orchUrl, psopId, centerAccess.auth(), centerAccess.tls());
      String declaredHost = loaded.workflow().declaredHost().orElse(null);
      candidates.add(loaded);
      candidateResults.add(result);
      if (declaredHost != null && !centerAccess.servedHosts().contains(declaredHost)) {
        skipped.add(psopId + " is declared for host '" + declaredHost + "'");
        log.warn(
            "[Orchestrator] PSOP_SKIPPED psopId={} score={} declaredHost={} servedHosts={}",
            psopId,
            result.getScore(),
            declaredHost,
            centerAccess.servedHosts());
      }
    }
    for (int index = 0; index < candidates.size(); index++) {
      String declaredHost = candidates.get(index).workflow().declaredHost().orElse(null);
      if (declaredHost == null || !centerAccess.servedHosts().contains(declaredHost)) continue;
      return found(candidateResults.get(index), candidates.get(index), declaredHost);
    }
    for (int index = 0; index < candidates.size(); index++) {
      if (candidates.get(index).workflow().declaredHost().isPresent()) continue;
      return found(candidateResults.get(index), candidates.get(index), null);
    }
    throw new IllegalStateException(
        "None of the matching workflows is executed by this host: " + String.join("; ", skipped));
  }

  /** Reports the candidate that was chosen, and which of the two passes chose it. */
  private ResolvedWorkflow found(
      WorkflowSearchResult result, LoadPsop.LoadedPsop loaded, String declaredHost) {
    log.info(
        "[Orchestrator] Found PSOP: {} (score={}, declaredHost={})",
        result.getWorkflowId(),
        result.getScore(),
        declaredHost);
    return new ResolvedWorkflow(result.getWorkflowId(), loaded);
  }

  private EventCallback createLogCallback(String contextId, Consumer<String> executionStarted) {
    return new EventCallback() {
      @Override
      public void onEvent(String type, Map<String, Object> data) {
        switch (type) {
          case EventType.START -> {
            String executionId = (String) data.get("executionId");
            executionStarted.accept(executionId);
            log.info("  [START] {} execution_id={}", data.get("workflow"), executionId);
          }
          case EventType.STEP_START -> log.info("  [STEP_START] {}", data.get("step"));
          case EventType.TASK_REQUEST -> log.info("  [TASK_REQUEST] agent={}", data.get("agent"));
          case EventType.TASK_RESPONSE ->
              log.info(
                  "  [TASK_RESPONSE] contextId={}, executionId={}, step={}, taskId={}, agent={},"
                      + " success={}, errorCode={}",
                  contextId,
                  data.get("executionId"),
                  data.get("step"),
                  data.get("taskId"),
                  data.get("agent"),
                  data.get("success"),
                  data.get("errorCode"));
          case EventType.AGENT_STATUS_UPDATE ->
              log.info(
                  "  [STATUS_UPDATE] agent={}, state={}, final={}",
                  data.get("agent"),
                  data.get("state"),
                  data.get("is_final"));
          case EventType.AGENT_ARTIFACT_UPDATE ->
              log.info(
                  "  [ARTIFACT_UPDATE] agent={}, artifact={}",
                  data.get("agent"),
                  data.get("artifact_name"));
          case EventType.AGENT_MESSAGE_EVENT ->
              log.info(
                  "  [MESSAGE] agent={}, {} chars",
                  data.get("agent"),
                  data.get("text") != null ? ((String) data.get("text")).length() : 0);
          case EventType.STEP_COMPLETE -> log.info("  [STEP_COMPLETE] {}", data.get("step"));
          case EventType.ROUTE_DECISION ->
              log.info(
                  "  [ROUTE] {} -> {}, conditional={}, allowed={}, reason={}",
                  data.get("step"),
                  data.get("next"),
                  data.get("conditional"),
                  data.get("allowed"),
                  data.get("reason"));
          case EventType.COMPLETE -> log.info("  [COMPLETE]");
          case EventType.ERROR ->
              log.error(
                  "  [ERROR] contextId={}, step={}, reason={}",
                  contextId,
                  data.getOrDefault("step", "(workflow)"),
                  WireLog.redact(String.valueOf(data.getOrDefault("error", "Execution failed"))));
          case EventType.CLOSE -> log.info("  [CLOSE]");
          default -> {}
        }
      }
    };
  }

  record ResolvedWorkflow(String psopId, LoadPsop.LoadedPsop loaded) {
    Workflow workflow() {
      return loaded.workflow();
    }
  }

  /**
   * How this host may use the center: its credentials and TLS, the local workflow mode, and the
   * hosts whose PSOPs this process executes. Local workflow mode is an explicit offline demo,
   * never a response to center failures.
   */
  public record CenterAccess(
      HttpAuthProvider auth, TlsPolicy tls, boolean localWorkflowEnabled, Set<String> servedHosts) {
    public CenterAccess {
      Objects.requireNonNull(auth, "auth");
      Objects.requireNonNull(tls, "tls");
      servedHosts = servedHosts == null ? Set.of() : Set.copyOf(servedHosts);
    }

    /** Center access for a host that executes only PSOPs which declare no host. */
    public CenterAccess(HttpAuthProvider auth, TlsPolicy tls, boolean localWorkflowEnabled) {
      this(auth, tls, localWorkflowEnabled, Set.of());
    }
  }
}
