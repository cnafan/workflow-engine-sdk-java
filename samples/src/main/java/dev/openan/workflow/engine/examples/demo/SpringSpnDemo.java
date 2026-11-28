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

package dev.openan.workflow.engine.examples.demo;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.openan.workflow.engine.client.A2ATransport;
import dev.openan.workflow.engine.client.AgentCardJacksonModule;
import dev.openan.workflow.engine.client.DefaultWorkflowEngineClient;
import dev.openan.workflow.engine.client.WorkflowEngineClientConfig;
import dev.openan.workflow.engine.examples.SpringWorkbenchApplication;
import dev.openan.workflow.engine.examples.agents.SpnDomainAgentCity1Executor;
import dev.openan.workflow.engine.examples.agents.SpnDomainAgentCity2Executor;
import dev.openan.workflow.engine.examples.server.OmcAgentLauncher;
import dev.openan.workflow.engine.examples.util.EnvResolver;
import dev.openan.workflow.engine.examples.workbench.WorkbenchAgentCatalog;
import dev.openan.workflow.engine.examples.workbench.SpringWorkbenchExecutor;
import dev.openan.workflow.engine.examples.workbench.SpringWorkbenchExtensionLifecycle;
import dev.openan.workflow.engine.model.SendMessageResult;
import dev.openan.workflow.engine.spring.A2AController;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.a2aproject.sdk.spec.AgentCard;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * Demo entry point for the SPN cross-city diagnosis (direct HTTP+JSON transport).
 *
 * <p>The Workbench Agent runs as a Spring Boot service. The demo orchestrates:
 *
 * <ol>
 *   <li>Start Spring Boot Workbench Agent (A2A server on port 26337)
 *   <li>Start OMC agents (embedded servers on ports 26335, 26336)
 *   <li>Send a Task-T message to the Workbench Agent via the SDK client
 *   <li>Print the response and shut everything down
 * </ol>
 *
 * <p>Demonstrates heterogeneous architecture: Workbench (Spring Boot) and OMC (JDK HttpServer)
 * communicate via the unified A2A-T protocol.
 */
public class SpringSpnDemo {
  private static final Logger log = LoggerFactory.getLogger(SpringSpnDemo.class);
  private static final ObjectMapper mapper =
      new ObjectMapper().registerModule(new AgentCardJacksonModule());

  private static final String WB_AGENT_NAME = "Transport Workbench Agent";
  private static final long STARTUP_WAIT = 3;

  private final OmcAgentLauncher omc = new OmcAgentLauncher();

  public static void main(String[] args) throws Exception {
    SpringWorkbenchApplication.loadDotEnv();
    new SpringSpnDemo().run(args);
  }

  static void requireCompleted(SendMessageResult result) {
    if (result == null
        || result.getTaskState() == null
        || !result.getTaskState().contains("TASK_STATE_COMPLETED")) {
      throw new IllegalStateException(
          "Workbench task failed: state="
              + (result != null ? result.getTaskState() : "null")
              + ", response="
              + (result != null ? result.getText() : "null"));
    }
  }

  private static long elapsedMillis(long startedNanos) {
    return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNanos);
  }

  static java.util.Map<String, Object> demoProperties(boolean demoNegotiation) {
    var defaults = new java.util.HashMap<String, Object>();
    defaults.put("a2a.demo-negotiation-enabled", demoNegotiation);
    defaults.put("a2a.spn-demo", true);
    return defaults;
  }

  private static String executionId(SendMessageResult result) {
    var metadata = result.getMetadata();
    return metadata != null
            && metadata.get(SpringWorkbenchExecutor.EXECUTION_ID_METADATA) instanceof String id
        ? id
        : null;
  }

  /** The endpoint the workbench card advertises, so the log names what is actually called. */
  private static String workbenchEndpoint() {
    return new WorkbenchAgentCatalog()
        .load()
        .stream()
        .filter(card -> WB_AGENT_NAME.equals(card.name()))
        .map(card -> card.supportedInterfaces().get(0).url())
        .findFirst()
        .orElse("(no workbench card)");
  }

  public void run(String... applicationArgs) throws Exception {
    long demoStarted = System.nanoTime();
    ConfigurableApplicationContext ctx = null;
    boolean success = false;
    boolean embeddedOmc = EmbeddedOmcSupport.enabled(applicationArgs, true);
    List<AgentCard> embeddedCards = EmbeddedOmcSupport.prepare(embeddedOmc);
    boolean demoNegotiation = SpnCasePrompts.demoNegotiationEnabled(embeddedOmc);
    log.info(
        "[Demo] NEGOTIATION_DEMO enabled={}, city={} (local default: City1 negotiates, City2 diagnoses directly; "
            + "disable with -Da2at.samples.negotiation=false)",
        demoNegotiation,
        System.getProperty("a2at.samples.negotiation.city", "city1"));
    log.info(
        "[Demo] START mode=direct, embeddedOmc={}, workbench={}", embeddedOmc, workbenchEndpoint());
    try {
      long stageStarted;
      if (embeddedOmc) {
        stageStarted = System.nanoTime();
        log.info("[Demo] STAGE_START stage=start-omc-agents");
        omc.startFromCard(embeddedCards.get(0), new SpnDomainAgentCity1Executor());
        omc.startFromCard(embeddedCards.get(1), new SpnDomainAgentCity2Executor());
        log.info("[Demo] Waiting {}s for agent ports to bind", STARTUP_WAIT);
        TimeUnit.SECONDS.sleep(STARTUP_WAIT);
        log.info(
            "[Demo] STAGE_DONE stage=start-omc-agents, count={}, elapsedMs={}",
            omc.servers().size(),
            elapsedMillis(stageStarted));
      } else {
        log.info("[Demo] STAGE_SKIP stage=start-omc-agents, reason=external-omc, mode=direct");
      }

      stageStarted = System.nanoTime();
      log.info("[Demo] STAGE_START stage=start-spring-workbench");
      SpringApplication application = new SpringApplication(SpringWorkbenchApplication.class);
      application.addInitializers(
          context -> {
            var environment = context.getEnvironment();
            environment
                .getPropertySources()
                .addFirst(
                    new org.springframework.core.env.MapPropertySource(
                        "spnDemo", demoProperties(demoNegotiation)));
          });
      ctx = application.run(applicationArgs);
      log.info(
          "[Demo] STAGE_DONE stage=start-spring-workbench, elapsedMs={}",
          elapsedMillis(stageStarted));

      stageStarted = System.nanoTime();
      log.info(
          "[Demo] STAGE_START stage=send-workbench-task, target={}, inputChars={}",
          WB_AGENT_NAME,
          SpnCasePrompts.TASK_TEXT.length());
      log.debug("[Demo] Workbench task text={}", SpnCasePrompts.TASK_TEXT);
      SendMessageResult result = sendTaskToWorkbench();
      var workbench = ctx.getBean(SpringWorkbenchExecutor.class);
      try {
        requireCompleted(result);
      } catch (IllegalStateException taskFailure) {
        try {
          String id = executionId(result);
          if (id != null) workbench.flushExecutionReporting(id, java.time.Duration.ofSeconds(15));
        } catch (InterruptedException reportingFailure) {
          Thread.currentThread().interrupt();
          taskFailure.addSuppressed(reportingFailure);
        } catch (RuntimeException reportingFailure) {
          taskFailure.addSuppressed(reportingFailure);
        }
        throw taskFailure;
      }
      String response = result.getText();
      log.info(
          "[Demo] STAGE_DONE stage=send-workbench-task, responseChars={}, elapsedMs={}",
          response != null ? response.length() : 0,
          elapsedMillis(stageStarted));
      if (response != null) {
        log.info("[Demo] Workbench response={}", response);
      } else {
        log.warn("[Demo] Workbench returned no response text");
      }
      workbench.flushExecutionReporting(executionId(result), java.time.Duration.ofSeconds(15));
      success = true;
      // Task result is already final. This bounded observation only keeps the local
      // demonstration alive long enough to show its independent recovery notification.
      if (embeddedOmc) {
        boolean observed =
            ctx.getBean(SpringWorkbenchExtensionLifecycle.class)
                .awaitFirstRecovery(java.time.Duration.ofSeconds(10));
        log.info("[Demo] RECOVERY_OBSERVATION received={}, workflowSuccess=true", observed);
      }
    } catch (Exception e) {
      log.error(
          "[Demo] FAILED elapsedMs={}, errorType={}, message={}",
          elapsedMillis(demoStarted),
          e.getClass().getSimpleName(),
          e.getMessage(),
          e);
      throw e;
    } finally {
      log.info(
          "[Demo] SHUTDOWN_START omcCount={}, springStarted={}", omc.servers().size(), ctx != null);
      if (ctx != null) {
        try {
          boolean drained =
              ctx.getBean(A2AController.class).awaitStreamsDrained(java.time.Duration.ofSeconds(2));
          if (!drained) {
            log.warn(
                "[Demo] SSE_DRAIN_TIMEOUT activeStreams={}, timeoutSeconds=2",
                ctx.getBean(A2AController.class).activeStreamCount());
          } else {
            log.info("[Demo] SSE_DRAIN_DONE activeStreams=0");
          }
        } catch (Exception e) {
          log.warn("[Demo] Failed to drain SSE responses: {}", e.getMessage(), e);
        }
        try {
          // Drain outbound Notification-T calls before Spring starts stopping Tomcat.
          // The bean's @PreDestroy call is idempotent.
          ctx.getBean(SpringWorkbenchExtensionLifecycle.class).close();
        } catch (Exception e) {
          log.warn("[Demo] Failed to close workbench extension lifecycle: {}", e.getMessage(), e);
        }
        try {
          // Outbound subscriptions are closed. Stop locally owned OMC streams before
          // dependent gateway/server infrastructure is disposed.
          omc.close();
          ctx.close();
        } catch (Exception e) {
          log.warn("[Demo] Failed to close Spring context: {}", e.getMessage(), e);
        }
      }
      omc.close();
      log.info(
          "[Demo] SHUTDOWN_DONE success={}, elapsedMs={}", success, elapsedMillis(demoStarted));
    }
  }

  /**
   * Northbound Task-T message to the Workbench Agent, mirroring spec case 7.1.
   *
   * <p>Structured-data track: the demo hands over the raw complaint data + schema and the A2A-T SDK
   * renders the Task-T prompt through its schema-aware fromData pipeline (no hand-written prompt
   * and no scenario-recognition call; slot extraction may use the SDK LLM).
   */
  private SendMessageResult sendTaskToWorkbench() throws Exception {
    long started = System.nanoTime();
    AgentCard wbCard =
        OmcAgentLauncher.cardFromResource("agentcard/transport_workbench_agent.json");
    String envPath = EnvResolver.resolveEnvPath();
    var clientConfig = WorkflowEngineClientConfig.builder().sslVerify(false);
    if (wbCard.securitySchemes() != null
        && !wbCard.securitySchemes().isEmpty()
        && wbCard.securityRequirements() != null
        && !wbCard.securityRequirements().isEmpty()) {
      clientConfig.credentialsConfigPath(
          OmcAgentLauncher.resourcePath("spn_agent_credentials.json"));
    }

    A2ATransport transport = new A2ATransport(List.of(wbCard), null, clientConfig.build());
    DefaultWorkflowEngineClient client = new DefaultWorkflowEngineClient(transport);
    try {
      log.info(
          "[Demo] NORTHBOUND_SEND target={}, contextId={}, endpoint={}, inputChars={}, track=fromData",
          WB_AGENT_NAME,
          transport.getContextId(),
          wbCard.supportedInterfaces().isEmpty() ? "?" : wbCard.supportedInterfaces().get(0).url(),
          SpnCasePrompts.TASK_TEXT.length());
      SendMessageResult result =
          client
              .sendTask(
                  WB_AGENT_NAME,
                  dev.openan.workflow.engine.client.A2atMessages.from(
                      dev.openan.workflow.engine.examples.util.A2ATInitialization.create(
                              () ->
                                  new net.openan.a2at.sdk.client.A2ATClient(
                                      java.nio.file.Path.of(envPath)))
                          .generateTaskPromptFromDataWithSchema(
                              SpnCasePrompts.privateLineComplaintData(),
                              SpnCasePrompts.privateLineComplaintSchema(),
                              net.openan.a2at.sdk.core.model.StandardTemplates
                                  .PRIVATE_LINE_COMPLAINT
                                  .uri()),
                      List.of(new org.a2aproject.sdk.spec.TextPart(SpnCasePrompts.TASK_TEXT))))
              .join();
      log.info(
          "[Demo] NORTHBOUND_DONE target={}, contextId={}, state={}, responseChars={}, elapsedMs={}",
          WB_AGENT_NAME,
          transport.getContextId(),
          result.getTaskState(),
          result.getText() != null ? result.getText().length() : 0,
          elapsedMillis(started));
      return result;
    } finally {
      client.close();
      transport.close();
    }
  }
}
