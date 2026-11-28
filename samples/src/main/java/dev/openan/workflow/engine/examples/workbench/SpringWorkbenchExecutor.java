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

import dev.openan.workflow.engine.client.A2ATExtension;
import dev.openan.workflow.engine.examples.agents.BaseAgentExecutor;
import dev.openan.workflow.engine.examples.config.OrchestrationAccess;
import dev.openan.workflow.engine.examples.config.WorkbenchClientProperties;
import dev.openan.workflow.engine.examples.util.EnvResolver;
import dev.openan.workflow.engine.registry.CenterExecutionReporting;
import dev.openan.workflow.engine.registry.HttpAuthProvider;
import dev.openan.workflow.engine.registry.TlsPolicy;
import dev.openan.workflow.engine.runner.ExecutionReporter;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.a2aproject.sdk.server.agentexecution.RequestContext;
import org.a2aproject.sdk.server.tasks.AgentEmitter;
import org.a2aproject.sdk.spec.A2AError;
import org.a2aproject.sdk.spec.AgentCard;
import org.a2aproject.sdk.spec.Part;
import org.a2aproject.sdk.spec.TextPart;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

/**
 * Spring-managed Workbench AgentExecutor.
 *
 * <p>Receives a Task-T from the upper layer, delegates to {@link WorkbenchOrchestrator} for the
 * full pipeline, and returns the result. The A2A server container is provided by the Spring Boot
 * starter's auto-configuration.
 */
@Component
public class SpringWorkbenchExecutor extends BaseAgentExecutor implements DisposableBean {
  private static final Logger log = LoggerFactory.getLogger(SpringWorkbenchExecutor.class);
  public static final String EXECUTION_ID_METADATA = "dev.openan.workflow.execution_id";

  private final WorkbenchClientProperties properties;
  private final HttpAuthProvider centerAuth;
  private final TlsPolicy centerTls;
  private final AgentCard localAgentCard;
  private final CenterExecutionReporting reporting;
  private volatile WorkbenchTaskInputParser taskInputParser;
  private final ConcurrentMap<String, ExecutionCancellation> executions = new ConcurrentHashMap<>();

  public SpringWorkbenchExecutor(
      WorkbenchClientProperties properties,
      AgentCard localAgentCard,
      @Qualifier("orchestrationHttpAuthProvider")
          ObjectProvider<HttpAuthProvider> centerAuthProvider,
      @Qualifier("orchestrationTlsPolicy") ObjectProvider<TlsPolicy> centerTlsProvider) {
    this.properties = properties;
    this.localAgentCard = localAgentCard;
    this.centerTls = centerTlsProvider.getIfAvailable(() ->
        !properties.isOrchVerifyServer() ? TlsPolicy.insecure()
        : properties.getOrchCaCertsPath() == null || properties.getOrchCaCertsPath().isBlank()
            ? TlsPolicy.strict() : TlsPolicy.trustedCa(properties.getOrchCaCertsPath()));
    this.centerAuth =
        centerAuthProvider.getIfAvailable(
            () -> OrchestrationAccess.authentication(properties, centerTls));
    this.reporting =
        properties.isLocalWorkflowEnabled()
            ? null
            : new CenterExecutionReporting(properties.getOrchUrl(), centerAuth, centerTls);
  }

  private static Map<String, Object> executionMetadata(String executionId) {
    return executionId == null ? Map.of() : Map.of(EXECUTION_ID_METADATA, executionId);
  }

  public void flushExecutionReporting(String executionId, Duration timeout)
      throws InterruptedException {
    if (reporting == null) return;
    ExecutionReporter reporter = reporting.activeReporter();
    if (reporter == null) {
      throw new IllegalStateException(
          "The center did not enable reporting for this PSOP and account.");
    }
    if (executionId == null) {
      throw new IllegalStateException("No workflow execution ID was returned by Workbench.");
    }
    ExecutionReporter.DeliveryStatus delivery = reporter.awaitDelivery(executionId, timeout);
    if (delivery != ExecutionReporter.DeliveryStatus.ACKNOWLEDGED) {
      throw new IllegalStateException(
          "Execution reporting outcome " + delivery + " for " + executionId);
    }
    log.info("[SpringWorkbench] EXECUTION_REPORTING_ACKNOWLEDGED execution_id={}", executionId);
  }

  private static long elapsedMillis(long startedNanos) {
    return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNanos);
  }

  private String resolveEnvPath() {
    if (properties.getA2atEnvPath() != null && !properties.getA2atEnvPath().isBlank()) {
      return properties.getA2atEnvPath();
    }
    return EnvResolver.resolveEnvPath();
  }

  private String resolveCredentialsPath() {
    if (properties.getCredentialsPath() != null && !properties.getCredentialsPath().isBlank()) {
      return properties.getCredentialsPath();
    }
    return getClass().getClassLoader().getResource("spn_agent_credentials.json") != null
        ? "classpath:spn_agent_credentials.json"
        : null;
  }

  @Override
  public void destroy() {
    if (reporting != null) reporting.close();
  }

  @Override
  public void execute(RequestContext ctx, AgentEmitter emitter) throws A2AError {
    String taskId = ctx.getTaskId();
    String contextId = ctx.getContextId();
    String userText = extractText(ctx.getMessage());
    long started = System.nanoTime();
    ExecutionCancellation cancellation = new ExecutionCancellation();
    executions.put(taskId, cancellation);
    emitter.submit(buildStatusMessage(contextId, taskId, "Task received"));
    emitter.startWork(buildStatusMessage(contextId, taskId, "Processing"));

    var executionId = new AtomicReference<String>();
    try {
      Map<String, Object> callMetadata =
          ctx.getMessage() != null && ctx.getMessage().metadata() != null
              ? ctx.getMessage().metadata()
              : Map.of();
      WorkbenchTaskInputParser.ParsedTask taskInput =
          taskInputParser().parse(userText, callMetadata);
      String input = taskInput.runtimeIntent();
      log.info(
          "[SpringWorkbench] TASK_START taskId={}, contextId={}, inputChars={}, "
              + "taskTemplate={}, taskParams={}, orchUrl={}, transportMode={}, "
              + "sslVerify={}",
          taskId,
          contextId,
          input.length(),
          taskInput.templateUri().uri(),
          taskInput.parameters().keySet(),
          properties.getOrchUrl(),
          "direct",
          properties.isSslVerify());
      String result =
          new WorkbenchOrchestrator(
                  properties.getOrchUrl(),
                  resolveCredentialsPath(),
                  properties.isSslVerify(),
                  resolveEnvPath(),
                  null,
                  null,
                  new WorkbenchOrchestrator.CenterAccess(
                      centerAuth,
                      centerTls,
                      properties.isLocalWorkflowEnabled(),
                      Set.of(localAgentCard.name())),
                  reporting)
              .run(input, properties.isDemoNegotiationEnabled(), cancellation, executionId::set);
      cancellation.check();
      Map<String, Object> metadata = new LinkedHashMap<>();
      metadata.put(A2ATExtension.TASK_T.uri(), result);
      List<Part<?>> parts = List.of(new TextPart(result));
      emitter.addArtifact(parts, "result", "cross-city-diagnosis-summary", metadata, false, true);
      emitter.complete(
          buildStatusMessage(contextId, taskId, "Completed", executionMetadata(executionId.get())));
      log.info(
          "[SpringWorkbench] TASK_DONE taskId={}, contextId={}, resultChars={}, elapsedMs={}",
          taskId,
          contextId,
          result.length(),
          elapsedMillis(started));
    } catch (Exception e) {
      if (cancellation.isCancelled()) return;
      log.error(
          "[SpringWorkbench] TASK_FAILED taskId={}, contextId={}, elapsedMs={}, "
              + "errorType={}, message={}",
          taskId,
          contextId,
          elapsedMillis(started),
          e.getClass().getSimpleName(),
          e.getMessage(),
          e);
      emitter.fail(
          buildStatusMessage(
              contextId,
              taskId,
              "Failed: " + e.getMessage(),
              executionMetadata(executionId.get())));
    } finally {
      executions.remove(taskId, cancellation);
    }
  }

  private WorkbenchTaskInputParser taskInputParser() {
    WorkbenchTaskInputParser current = taskInputParser;
    if (current != null) {
      return current;
    }
    synchronized (this) {
      if (taskInputParser == null) {
        taskInputParser = WorkbenchTaskInputParser.fromEnv(resolveEnvPath());
      }
      return taskInputParser;
    }
  }

  @Override
  public void cancel(RequestContext ctx, AgentEmitter emitter) throws A2AError {
    ExecutionCancellation execution = executions.get(ctx.getTaskId());
    if (execution != null) execution.cancel();
    log.warn(
        "[SpringWorkbench] TASK_CANCEL taskId={}, contextId={}",
        ctx.getTaskId(),
        ctx.getContextId());
    emitter.cancel();
  }
}
