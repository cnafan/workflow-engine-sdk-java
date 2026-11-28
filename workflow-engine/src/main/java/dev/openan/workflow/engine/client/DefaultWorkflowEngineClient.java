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

package dev.openan.workflow.engine.client;

import dev.openan.workflow.engine.control.ControlPoint;
import dev.openan.workflow.engine.control.EventCallback;
import dev.openan.workflow.engine.control.EventType;
import dev.openan.workflow.engine.control.NegotiationStrategy;
import dev.openan.workflow.engine.model.BusinessFailure;
import dev.openan.workflow.engine.model.MessageContent;
import dev.openan.workflow.engine.model.NegotiationReply;
import dev.openan.workflow.engine.model.NegotiationRequest;
import dev.openan.workflow.engine.model.ReceivedMessage;
import dev.openan.workflow.engine.model.SendMessageResult;
import dev.openan.workflow.engine.model.TaskRequest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import net.openan.a2at.sdk.core.model.NegotiationContext;
import net.openan.a2at.sdk.core.model.NegotiationPerformative;
import org.a2aproject.sdk.client.ClientEvent;
import org.a2aproject.sdk.client.MessageEvent;
import org.a2aproject.sdk.client.TaskUpdateEvent;
import org.a2aproject.sdk.jsonrpc.common.wrappers.ListTasksResult;
import org.a2aproject.sdk.spec.AgentCard;
import org.a2aproject.sdk.spec.ListTasksParams;
import org.a2aproject.sdk.spec.TaskStatusUpdateEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Coordinates A2A task interaction; all content generation and semantic validation belong to the
 * host.
 */
public class DefaultWorkflowEngineClient implements WorkflowEngineClient, AutoCloseable {
  private static final Logger log = LoggerFactory.getLogger(DefaultWorkflowEngineClient.class);
  private final A2ATransport transport;
  private final int maxNegotiationExchanges;
  private final boolean closeTransportOnClose;
  private final long taskPollIntervalMillis;
  private final AtomicBoolean closed = new AtomicBoolean();
  private final Set<Invocation> invocations = java.util.concurrent.ConcurrentHashMap.newKeySet();
  private final ScheduledExecutorService timeoutScheduler =
      Executors.newSingleThreadScheduledExecutor(
          runnable -> {
            Thread thread = new Thread(runnable, "workflow-client-timeouts");
            thread.setDaemon(true);
            return thread;
          });
  private volatile EventCallback eventCallback = new EventCallback();
  private volatile ControlPoint controlPoint;

  /** Creates a caller-owned transport facade. */
  public DefaultWorkflowEngineClient(A2ATransport transport) {
    this(transport, 3, false);
  }

  /** Uses the configured resource exchange budget, not an SDK protocol round counter. */
  public DefaultWorkflowEngineClient(A2ATransport transport, WorkflowEngineClientConfig config) {
    this(transport, config.getMaxNegotiationExchanges(), false, config.getTaskPollIntervalMillis());
  }

  private DefaultWorkflowEngineClient(A2ATransport transport, int maxExchanges, boolean owning) {
    this(
        transport,
        maxExchanges,
        owning,
        WorkflowEngineClientConfig.DEFAULT_TASK_POLL_INTERVAL_MILLIS);
  }

  private DefaultWorkflowEngineClient(
      A2ATransport transport, int maxExchanges, boolean owning, long taskPollIntervalMillis) {
    this.transport = Objects.requireNonNull(transport, "transport");
    if (maxExchanges < 1)
      throw new IllegalArgumentException("Negotiation exchange budget must be positive");
    if (taskPollIntervalMillis < 100)
      throw new IllegalArgumentException("Task poll interval must be at least 100ms");
    this.maxNegotiationExchanges = maxExchanges;
    this.closeTransportOnClose = owning;
    this.taskPollIntervalMillis = taskPollIntervalMillis;
  }

  /** Creates a facade owning the supplied transport. */
  public static DefaultWorkflowEngineClient owning(A2ATransport transport) {
    return new DefaultWorkflowEngineClient(transport, 3, true);
  }

  /** Creates an owning facade with explicit resource settings. */
  public static DefaultWorkflowEngineClient owning(
      A2ATransport transport, WorkflowEngineClientConfig config) {
    return new DefaultWorkflowEngineClient(
        transport, config.getMaxNegotiationExchanges(), true, config.getTaskPollIntervalMillis());
  }

  private static ReceivedMessage negotiationResponse(SendMessageResult result) {
    for (ReceivedMessage received : result.getReceivedMessages()) {
      if (carriesNegotiationMetadata(received)) return received;
    }
    throw new IllegalArgumentException(
        "Unsupported INPUT_REQUIRED interaction: no Negotiation-T Propose");
  }

  private static boolean carriesNegotiationMetadata(ReceivedMessage received) {
    return received.taskMetadata().containsKey(A2ATExtension.NEGOTIATION_T.uri())
        || received.message() != null
            && received.message().metadata().containsKey(A2ATExtension.NEGOTIATION_T.uri())
        || received.artifacts().stream()
            .anyMatch(
                a ->
                    a.metadata() != null
                        && a.metadata().containsKey(A2ATExtension.NEGOTIATION_T.uri()));
  }

  private static NegotiationContext validateReply(
      NegotiationContext original, MessageContent reply) {
    if (!reply.extensions().contains(A2ATExtension.NEGOTIATION_T.uri())
        || !reply.metadata().containsKey(A2ATExtension.NEGOTIATION_T.uri())) {
      throw new IllegalArgumentException("Negotiation reply must carry and activate Negotiation-T");
    }
    NegotiationContext ending = A2atMessages.contextOf(reply.metadata());
    if (!original.id().equals(ending.id())
        || original.round() != ending.round()
        || original.maxRounds() != ending.maxRounds()
        || ending.performative() == NegotiationPerformative.PROPOSE) {
      throw new IllegalArgumentException(
          "Reply does not match the received negotiation context/round");
    }
    return ending;
  }

  @Override
  public void setControlPoint(ControlPoint callbacks) {
    this.controlPoint = callbacks;
  }

  @Override
  public void setEventCallback(EventCallback callback) {
    this.eventCallback = callback == null ? new EventCallback() : callback;
  }

  @Override
  public long callbackTimeoutSeconds() {
    return transport.sendTimeoutSeconds();
  }

  private static void validateTaskExtension(AgentCard card, MessageContent content) {
    String taskExtension = A2ATExtension.TASK_T.uri();
    List<String> declaredExtensions = A2ATransport.extractExtensionUris(card);
    boolean activated = content.extensions().contains(taskExtension);
    boolean supplied = content.metadata().containsKey(taskExtension);
    if (activated || supplied) {
      if (!activated || !supplied) {
        throw new IllegalArgumentException(
            "Task-T content must contain matching extension activation and metadata");
      }
      if (!declaredExtensions.contains(taskExtension)) {
        throw new IllegalArgumentException("Target agent does not declare Task-T: " + card.name());
      }
    }
    String negotiationExtension = A2ATExtension.NEGOTIATION_T.uri();
    if (content.extensions().contains(negotiationExtension)
        && !declaredExtensions.contains(negotiationExtension)) {
      throw new IllegalArgumentException(
          "Target agent does not declare Negotiation-T: " + card.name());
    }
  }

  private static Throwable unwrap(Throwable error) {
    Throwable current = error;
    while ((current instanceof CompletionException || current instanceof ExecutionException)
        && current.getCause() != null) {
      current = current.getCause();
    }
    return current;
  }

  @Override
  public CompletableFuture<SendMessageResult> sendTask(String agentName, MessageContent content) {
    TaskRequest request =
        TaskRequest.builder()
            .agentName(agentName)
            .executionId(UUID.randomUUID().toString())
            .taskId(UUID.randomUUID().toString())
            .build();
    return dispatchTask(request, content, controlPoint, eventCallback);
  }

  @Override
  public CompletableFuture<SendMessageResult> sendTask(
      String agentName, MessageContent content, NegotiationStrategy negotiationStrategy) {
    return sendTask(agentName, content, negotiationStrategy, eventCallback);
  }

  @Override
  public CompletableFuture<SendMessageResult> sendTask(
      String agentName,
      MessageContent content,
      NegotiationStrategy negotiationStrategy,
      EventCallback invocationEvents) {
    TaskRequest request =
        TaskRequest.builder()
            .agentName(agentName)
            .executionId(UUID.randomUUID().toString())
            .taskId(UUID.randomUUID().toString())
            .build();
    return sendWorkflowTask(request, content, negotiationStrategy, invocationEvents);
  }

  @Override
  public CompletableFuture<SendMessageResult> sendWorkflowTask(
      TaskRequest request,
      MessageContent content,
      NegotiationStrategy negotiationStrategy,
      EventCallback invocationEvents) {
    if (negotiationStrategy == null) {
      return CompletableFuture.failedFuture(new NullPointerException("negotiationStrategy"));
    }
    ControlPoint callbacks =
        ControlPoint.builder().onNegotiation(negotiationStrategy::resolve).build();
    return dispatchTask(
        request,
        content,
        callbacks,
        invocationEvents == null ? new EventCallback() : invocationEvents);
  }

  private CompletableFuture<SendMessageResult> dispatchTask(
      TaskRequest request,
      MessageContent content,
      ControlPoint callbacks,
      EventCallback invocationEvents) {
    if (request == null) {
      return CompletableFuture.failedFuture(new NullPointerException("request"));
    }
    if (content == null) {
      return CompletableFuture.failedFuture(
          new NullPointerException("onTask returned null content"));
    }
    AgentCard card = transport.getCard(request.getAgentName());
    if (closed.get() || card == null)
      return CompletableFuture.failedFuture(
          new IllegalStateException(
              closed.get()
                  ? "Workflow client closed"
                  : "Agent not found: " + request.getAgentName()));
    try {
      validateTaskExtension(card, content);
    } catch (RuntimeException error) {
      return CompletableFuture.failedFuture(error);
    }
    Invocation invocation = new Invocation(request, content, callbacks, invocationEvents);
    invocations.add(invocation);
    ScheduledFuture<?> timeout =
        timeoutScheduler.schedule(
            () -> {
              if (!invocation.completion.isDone()) {
                completeExceptionallyAfterCleanup(
                    card,
                    invocation,
                    new TimeoutException(
                        "Task interaction timed out after "
                            + callbackTimeoutSeconds()
                            + " seconds"));
              }
            },
            callbackTimeoutSeconds(),
            TimeUnit.SECONDS);
    invocation.completion.whenComplete(
        (result, error) -> {
          timeout.cancel(false);
          invocation.cancelPendingPoll();
          invocations.remove(invocation);
          if (invocation.completion.isCancelled()) {
            cancelRemoteTask(card, invocation, null)
                .whenComplete(
                    (ignored, cleanupError) ->
                        transport.closeConversation(card, invocation.contextId));
          } else {
            transport.closeConversation(card, invocation.contextId);
          }
          if (error != null && invocation.exchanges > 0)
            emit(
                invocation,
                EventType.NEGOTIATION_FAILED,
                Map.of(
                    "agent",
                    request.getAgentName(),
                    "exchange",
                    invocation.exchanges,
                    "errorType",
                    error.getClass().getSimpleName()));
        });
    if (closed.get()) invocation.completion.cancel(true);
    send(card, invocation, content, null)
        .thenCompose(result -> advance(card, invocation, result))
        .whenComplete(
            (result, error) -> {
              if (error != null) completeExceptionallyAfterCleanup(card, invocation, error);
              else if (invocation.finishing.compareAndSet(false, true))
                invocation.completion.complete(result);
            });
    return invocation.completion;
  }

  private void completeExceptionallyAfterCleanup(
      AgentCard card, Invocation invocation, Throwable error) {
    if (!invocation.finishing.compareAndSet(false, true)) {
      // A timeout may win before the first send exposes its remote task id. If that result arrives
      // later, advance() records the id and reaches this path again so the orphan can still be
      // cancelled. cancelRemoteTask() deduplicates an already-started cleanup.
      cancelRemoteTask(card, invocation, null);
      return;
    }
    Throwable primary = unwrap(error);
    cancelRemoteTask(card, invocation, primary)
        .whenComplete(
            (ignored, cleanupError) -> invocation.completion.completeExceptionally(primary));
  }

  private CompletableFuture<Void> cancelRemoteTask(
      AgentCard card, Invocation invocation, Throwable primary) {
    String remoteTaskId = invocation.remoteTaskId;
    if (remoteTaskId == null || invocation.remoteTaskTerminal) {
      return CompletableFuture.completedFuture(null);
    }
    synchronized (invocation) {
      if (invocation.cleanup != null) return invocation.cleanup;
      log.info(
          "[EngineClient] Canceling non-final remote task after local interaction ended: agent={}, taskId={}",
          invocation.task.getAgentName(),
          remoteTaskId);
      invocation.cleanup =
          transport
              .cancelTask(card, invocation.task.getAgentName(), remoteTaskId)
              .handle(
                  (result, cleanupError) -> {
                    if (cleanupError == null) {
                      invocation.remoteTaskTerminal = true;
                    } else {
                      Throwable failure = unwrap(cleanupError);
                      if (primary != null && failure != primary) primary.addSuppressed(failure);
                      log.warn(
                          "[EngineClient] Failed to cancel non-final remote task: agent={}, taskId={}",
                          invocation.task.getAgentName(),
                          remoteTaskId,
                          failure);
                    }
                    return null;
                  });
      return invocation.cleanup;
    }
  }

  private CompletableFuture<SendMessageResult> send(
      AgentCard card, Invocation invocation, MessageContent content, String taskId) {
    if (!invocation.active())
      return CompletableFuture.failedFuture(
          new java.util.concurrent.CancellationException("Task interaction is no longer active"));
    emit(
        invocation,
        EventType.AGENT_REQUEST,
        Map.of("agent", invocation.task.getAgentName(), "content", content));
    Map<String, String> trace = invocationTrace(invocation);
    return WireLog.call(
        trace,
        () ->
            transport.send(
                card,
                invocation.task.getAgentName(),
                content,
                invocation.contextId,
                taskId,
                event ->
                    forwardIntermediateEvent(
                        event,
                        invocation.task.getAgentName(),
                        (type, data) -> emit(invocation, type, data)),
                invocation::active));
  }

  private Map<String, String> invocationTrace(Invocation invocation) {
    Map<String, String> trace = new HashMap<>(invocation.parentTrace);
    if (invocation.task.getExecutionId() != null)
      trace.putIfAbsent("executionId", invocation.task.getExecutionId());
    if (invocation.task.getTaskId() != null)
      trace.putIfAbsent("logicalTaskId", invocation.task.getTaskId());
    trace.put("attempt", invocation.attempt);
    trace.put("contextId", invocation.contextId);
    return trace;
  }

  private CompletableFuture<SendMessageResult> advance(
      AgentCard card, Invocation invocation, SendMessageResult result) {
    if (result.getTask() != null) {
      if (result.getTask().id() == null
          || result.getTask().id().isBlank()
          || !invocation.contextId.equals(result.getTask().contextId())
          || invocation.remoteTaskId != null
              && !invocation.remoteTaskId.equals(result.getTask().id())) {
        return CompletableFuture.failedFuture(
            new IllegalArgumentException("Remote task/context identity changed"));
      }
      invocation.remoteTaskId = result.getTask().id();
      invocation.remoteTaskTerminal =
          result.getTask().status() != null
              && result.getTask().status().state() != null
              && result.getTask().status().state().isFinal();
    }
    if (!invocation.active())
      return CompletableFuture.failedFuture(
          new java.util.concurrent.CancellationException("Task interaction is no longer active"));
    if ("TASK_STATE_SUBMITTED".equals(result.getTaskState())
        || "TASK_STATE_WORKING".equals(result.getTaskState())) {
      return observeTask(card, invocation).thenCompose(next -> advance(card, invocation, next));
    }
    if (!"TASK_STATE_INPUT_REQUIRED".equals(result.getTaskState())
        && result.getTask() != null
        && !invocation.remoteTaskTerminal) {
      return CompletableFuture.failedFuture(
          new IllegalStateException(
              "Unsupported non-final remote task state: " + result.getTaskState()));
    }
    if (!"TASK_STATE_INPUT_REQUIRED".equals(result.getTaskState())) {
      if (result.getTask() == null
          && result.getTaskState().isEmpty()
          && result.getReceivedMessages().stream()
              .anyMatch(DefaultWorkflowEngineClient::carriesNegotiationMetadata)) {
        // A2A-T pre-task negotiation: the remote proposes on a bare message before creating any
        // task, so there is no taskId to anchor on; correlate by contextId + negotiation id and
        // continue with taskless follow-up sends instead of failing or treating it as output.
        log.info(
            "[EngineClient] Taskless Negotiation-T propose: agent={}",
            invocation.task.getAgentName());
        return negotiate(card, invocation, result, null);
      }
      emitAgentResponse(invocation, result);
      return CompletableFuture.completedFuture(result);
    }
    if (result.getTask() == null || result.getTask().id() == null) {
      return CompletableFuture.failedFuture(
          new IllegalArgumentException("INPUT_REQUIRED has no remote task identity"));
    }
    return negotiate(card, invocation, result, result.getTask().id());
  }

  /**
   * Shared negotiation loop for both carriers: a task-anchored {@code INPUT_REQUIRED} status
   * ({@code remoteTask} set) and a taskless bare-message Propose ({@code remoteTask == null},
   * pre-task A2A-T negotiation). The follow-up send reuses the remote task id when there is one and
   * stays taskless otherwise.
   */
  private CompletableFuture<SendMessageResult> negotiate(
      AgentCard card, Invocation invocation, SendMessageResult result, String remoteTask) {
    if (!invocation.original.extensions().contains(A2ATExtension.NEGOTIATION_T.uri())) {
      return CompletableFuture.failedFuture(
          new IllegalArgumentException(
              "Remote requested Negotiation-T although it was not activated by the host"));
    }
    ReceivedMessage received = negotiationResponse(result);
    NegotiationContext context = A2atMessages.contextOf(received);
    if (context.performative() != NegotiationPerformative.PROPOSE || context.isExhausted()) {
      return CompletableFuture.failedFuture(
          new IllegalArgumentException("Expected a valid Negotiation-T Propose"));
    }
    NegotiationContext previous = invocation.contexts.put(context.id(), context);
    if (previous != null
        && (context.round() < previous.round() || context.maxRounds() != previous.maxRounds())) {
      return CompletableFuture.failedFuture(
          new IllegalArgumentException("Negotiation round regressed or maxRounds changed"));
    }
    if (!invocation.answered.add(answerKey(remoteTask, context))) {
      if (remoteTask == null) {
        return CompletableFuture.failedFuture(
            new IllegalStateException(
                "Duplicate taskless Negotiation-T Propose with no remote task to observe: "
                    + context.id()
                    + " round "
                    + context.round()));
      }
      return observeTask(card, invocation).thenCompose(next -> advance(card, invocation, next));
    }
    if (++invocation.exchanges > maxNegotiationExchanges) {
      return CompletableFuture.failedFuture(
          new IllegalStateException("Negotiation exchange budget exhausted; no Abort generated"));
    }
    List<NegotiationRequest.Exchange> history =
        invocation.history.computeIfAbsent(context.id(), ignored -> new ArrayList<>());
    NegotiationRequest request =
        new NegotiationRequest(
            invocation.task, invocation.original, received, history, invocation.remaining());
    emit(
        invocation,
        EventType.NEGOTIATION_REQUEST,
        Map.of(
            "agent",
            invocation.task.getAgentName(),
            "request",
            request,
            "exchange",
            invocation.exchanges));
    CompletableFuture<NegotiationReply> answer;
    try {
      answer =
          Objects.requireNonNull(
              Objects.requireNonNull(invocation.callbacks, "onNegotiation handler is required")
                  .onNegotiation(request),
              "onNegotiation returned null future");
    } catch (RuntimeException error) {
      return CompletableFuture.failedFuture(error);
    }
    return answer.thenCompose(
        reply -> {
          Objects.requireNonNull(reply, "onNegotiation returned null reply");
          if (!invocation.active())
            return CompletableFuture.failedFuture(
                new java.util.concurrent.CancellationException("Late negotiation reply ignored"));
          history.add(new NegotiationRequest.Exchange(received, reply));
          if (reply instanceof NegotiationReply.Stop stop) {
            return CompletableFuture.failedFuture(
                new BusinessFailure(stop.code(), stop.reason(), Map.of()));
          }
          MessageContent content = ((NegotiationReply.Send) reply).content();
          NegotiationContext ending = validateReply(context, content);
          emit(
              invocation,
              EventType.NEGOTIATION_RESOLVED,
              Map.of(
                  "agent",
                  invocation.task.getAgentName(),
                  "exchange",
                  invocation.exchanges,
                  "reply",
                  reply));
          return send(card, invocation, content, remoteTask)
              .thenCompose(
                  next -> {
                    if (ending.performative() == NegotiationPerformative.ABORT) {
                      // A remote completion/ACK after Abort is not successful diagnosis.
                      next.setFailureCode("negotiation.aborted");
                      next.setFailureMessage(
                          "Business sent Abort; diagnosis was not completed successfully");
                      emitAgentResponse(invocation, next);
                      return CompletableFuture.completedFuture(next);
                    }
                    return advance(card, invocation, next);
                  });
        });
  }

  private static String answerKey(String remoteTask, NegotiationContext context) {
    return (remoteTask == null ? "taskless" : remoteTask)
        + ":"
        + context.id()
        + ":"
        + context.round();
  }

  /** A send ACK is not a task result. Observe without resending the business command. */
  private CompletableFuture<SendMessageResult> observeTask(AgentCard card, Invocation invocation) {
    if (invocation.remoteTaskId == null)
      return CompletableFuture.failedFuture(
          new IllegalArgumentException("Non-terminal response has no remote task identity"));
    return delayBeforeTaskPoll(invocation)
        .thenCompose(
            ignored -> {
              if (!invocation.active())
                return CompletableFuture.failedFuture(
                    new java.util.concurrent.CancellationException("Task wait ended"));
              return WireLog.call(
                  invocationTrace(invocation),
                  () ->
                      transport.getTask(
                          card, invocation.task.getAgentName(), invocation.remoteTaskId));
            });
  }

  private CompletableFuture<Void> delayBeforeTaskPoll(Invocation invocation) {
    PendingPoll pending = new PendingPoll();
    PendingPoll previous = invocation.pendingPoll.getAndSet(pending);
    if (previous != null) previous.cancel();
    pending.scheduled =
        timeoutScheduler.schedule(
            () -> {
              if (invocation.pendingPoll.compareAndSet(pending, null)) {
                pending.completion.complete(null);
              }
            },
            taskPollIntervalMillis,
            TimeUnit.MILLISECONDS);
    if (pending.completion.isDone()) pending.scheduled.cancel(false);
    return pending.completion;
  }

  private void emit(Invocation invocation, String type, Map<String, Object> data) {
    try {
      Map<String, Object> correlated = new HashMap<>(data);
      correlated.put("taskId", invocation.task.getTaskId());
      correlated.put("executionId", invocation.task.getExecutionId());
      invocation.events.onEvent(type, correlated);
    } catch (RuntimeException error) {
      log.warn("Event callback failed for {}", type, error);
    }
  }

  private void emitAgentResponse(Invocation invocation, SendMessageResult result) {
    Map<String, Object> data = new HashMap<>();
    data.put("agent", invocation.task.getAgentName());
    data.put("response", result.getText());
    data.put("receivedMessages", result.getReceivedMessages());
    emit(invocation, EventType.AGENT_RESPONSE, data);
  }

  private void forwardIntermediateEvent(
      ClientEvent event,
      String agentName,
      java.util.function.BiConsumer<String, Map<String, Object>> consumer) {
    Map<String, Object> data = ClientEventMapper.toMap(event, agentName);
    if (event instanceof TaskUpdateEvent tue) {
      if (tue.getUpdateEvent() instanceof TaskStatusUpdateEvent sue) {
        String state = sue.status().state().name();
        log.info(
            "[EngineClient] Agent {} status update: {} (final={})",
            agentName,
            state,
            sue.isFinal());
        consumer.accept(EventType.AGENT_STATUS_UPDATE, data);
      } else if (tue.getUpdateEvent()
          instanceof org.a2aproject.sdk.spec.TaskArtifactUpdateEvent ae) {
        log.info(
            "[EngineClient] Agent {} artifact update: {} ({})",
            agentName,
            ae.artifact().name(),
            ae.artifact().artifactId());
        consumer.accept(EventType.AGENT_ARTIFACT_UPDATE, data);
      }
    } else if (event instanceof MessageEvent) {
      String text = (String) data.getOrDefault("text", "");
      log.info("[EngineClient] Agent {} message event: {} chars", agentName, text.length());
      consumer.accept(EventType.AGENT_MESSAGE_EVENT, data);
    }
  }

  private void emit(EventCallback callback, String type, Map<String, Object> data) {
    try {
      callback.onEvent(type, data);
    } catch (RuntimeException error) {
      log.warn("Event callback failed for {}", type, error);
    }
  }

  @Override
  public CompletableFuture<SendMessageResult> getTask(String agentName, String taskId) {
    if (closed.get())
      return CompletableFuture.failedFuture(new IllegalStateException("Workflow client closed"));
    if (agentName == null || agentName.isBlank() || taskId == null || taskId.isBlank()) {
      return CompletableFuture.failedFuture(
          new IllegalArgumentException("agentName and taskId must not be blank"));
    }
    AgentCard agentCard = transport.getCard(agentName);
    if (agentCard == null) {
      return CompletableFuture.failedFuture(new RuntimeException("Agent not found: " + agentName));
    }
    return transport.getTask(agentCard, agentName, taskId);
  }

  @Override
  public CompletableFuture<ListTasksResult> listTasks(String agentName, ListTasksParams params) {
    if (closed.get())
      return CompletableFuture.failedFuture(new IllegalStateException("Workflow client closed"));
    if (agentName == null || agentName.isBlank() || params == null) {
      return CompletableFuture.failedFuture(
          new IllegalArgumentException("agentName and params must not be null or blank"));
    }
    AgentCard agentCard = transport.getCard(agentName);
    if (agentCard == null) {
      return CompletableFuture.failedFuture(new RuntimeException("Agent not found: " + agentName));
    }
    return transport.listTasks(agentCard, agentName, params);
  }

  @Override
  public CompletableFuture<SendMessageResult> cancelTask(String agentName, String taskId) {
    if (closed.get())
      return CompletableFuture.failedFuture(new IllegalStateException("Workflow client closed"));
    if (agentName == null || agentName.isBlank() || taskId == null || taskId.isBlank()) {
      return CompletableFuture.failedFuture(
          new IllegalArgumentException("agentName and taskId must not be blank"));
    }
    AgentCard agentCard = transport.getCard(agentName);
    if (agentCard == null) {
      return CompletableFuture.failedFuture(new RuntimeException("Agent not found: " + agentName));
    }
    return transport.cancelTask(agentCard, agentName, taskId);
  }

  @Override
  public CompletableFuture<SendMessageResult> subscribeToTask(
      String agentName,
      String taskId,
      java.util.function.Consumer<java.util.Map<String, Object>> eventCallback) {
    if (closed.get())
      return CompletableFuture.failedFuture(new IllegalStateException("Workflow client closed"));
    if (agentName == null || agentName.isBlank() || taskId == null || taskId.isBlank()) {
      return CompletableFuture.failedFuture(
          new IllegalArgumentException("agentName and taskId must not be blank"));
    }
    AgentCard agentCard = transport.getCard(agentName);
    if (agentCard == null) {
      return CompletableFuture.failedFuture(new RuntimeException("Agent not found: " + agentName));
    }
    return transport.subscribeToTask(
        agentCard,
        agentName,
        taskId,
        event -> {
          forwardIntermediateEvent(
              event, agentName, (type, data) -> emit(this.eventCallback, type, data));
          if (eventCallback != null) {
            eventCallback.accept(ClientEventMapper.toMap(event, agentName));
          }
        });
  }

  @Override
  public void close() {
    if (!closed.compareAndSet(false, true)) return;
    invocations.forEach(invocation -> invocation.completion.cancel(true));
    timeoutScheduler.shutdownNow();
    if (closeTransportOnClose) transport.close();
  }

  private static final class PendingPoll {
    final CompletableFuture<Void> completion = new CompletableFuture<>();
    volatile ScheduledFuture<?> scheduled;

    void cancel() {
      ScheduledFuture<?> current = scheduled;
      if (current != null) current.cancel(false);
      completion.completeExceptionally(
          new java.util.concurrent.CancellationException("Task wait ended"));
    }
  }

  private final class Invocation {
    final TaskRequest task;
    final MessageContent original;
    final ControlPoint callbacks;
    final EventCallback events;
    final String contextId = UUID.randomUUID().toString();
    final String attempt = UUID.randomUUID().toString();
    final Map<String, String> parentTrace = Map.copyOf(WireLog.context());
    final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(callbackTimeoutSeconds());
    final CompletableFuture<SendMessageResult> completion = new CompletableFuture<>();
    final Map<String, List<NegotiationRequest.Exchange>> history = new HashMap<>();
    final Set<String> answered = new HashSet<>();
    final Map<String, NegotiationContext> contexts = new HashMap<>();
    final AtomicBoolean finishing = new AtomicBoolean();
    final AtomicReference<PendingPoll> pendingPoll = new AtomicReference<>();
    volatile String remoteTaskId;
    volatile boolean remoteTaskTerminal;
    CompletableFuture<Void> cleanup;
    int exchanges;

    Invocation(
        TaskRequest task, MessageContent content, ControlPoint callbacks, EventCallback events) {
      this.task = task;
      this.original = content;
      this.callbacks = callbacks;
      this.events = events;
    }

    Duration remaining() {
      return Duration.ofNanos(Math.max(0, deadline - System.nanoTime()));
    }

    boolean active() {
      return !closed.get() && !finishing.get() && !completion.isDone() && !remaining().isZero();
    }

    void cancelPendingPoll() {
      PendingPoll pending = pendingPoll.getAndSet(null);
      if (pending != null) pending.cancel();
    }
  }
}
