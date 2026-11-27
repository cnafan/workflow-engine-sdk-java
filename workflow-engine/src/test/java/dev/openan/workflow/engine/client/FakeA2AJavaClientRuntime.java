/*
 * Copyright (c) 2026 Huawei Technologies Co., Ltd.
 * All Rights Reserved.
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package dev.openan.workflow.engine.client;

import dev.openan.workflow.engine.model.SendMessageResult;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;
import org.a2aproject.sdk.client.ClientEvent;
import org.a2aproject.sdk.client.transport.spi.interceptors.ClientCallContext;
import org.a2aproject.sdk.jsonrpc.common.wrappers.ListTasksResult;
import org.a2aproject.sdk.spec.AgentCard;
import org.a2aproject.sdk.spec.ListTasksParams;
import org.a2aproject.sdk.spec.MessageSendParams;
import org.a2aproject.sdk.spec.Task;

/**
 * Scriptable in-process fake of {@link A2AJavaClientRuntime} for coverage tests. No network;
 * handlers return scripted SDK events or throw scripted errors. Mirrors the Python SDK's
 * FakeStreamClient seam.
 */
class FakeA2AJavaClientRuntime implements A2AJavaClientRuntime {

  Function<MessageSendParams, List<ClientEvent>> sendHandler = params -> List.of();
  Function<String, Task> getTaskHandler = taskId -> null;
  Function<ListTasksParams, ListTasksResult> listTasksHandler = params -> null;
  Function<String, Task> cancelTaskHandler = taskId -> null;
  SubscribeHandler subscribeHandler = (taskId, context, sink) -> {};
  Runnable closeHandler = () -> {};

  interface SubscribeHandler {
    void handle(
        String taskId,
        org.a2aproject.sdk.client.transport.spi.interceptors.ClientCallContext context,
        Consumer<ClientEvent> sink);
  }

  FakeA2AJavaClientRuntime sendReturns(Function<MessageSendParams, List<ClientEvent>> handler) {
    this.sendHandler = handler;
    return this;
  }

  FakeA2AJavaClientRuntime getTaskReturns(Function<String, Task> handler) {
    this.getTaskHandler = handler;
    return this;
  }

  FakeA2AJavaClientRuntime cancelTaskReturns(Function<String, Task> handler) {
    this.cancelTaskHandler = handler;
    return this;
  }

  FakeA2AJavaClientRuntime subscribe(SubscribeHandler handler) {
    this.subscribeHandler = handler;
    return this;
  }

  FakeA2AJavaClientRuntime onClose(Runnable handler) {
    this.closeHandler = handler;
    return this;
  }

  @Override
  public Iterable<ClientEvent> sendMessage(
      AgentCard card,
      MessageSendParams params,
      ClientCallContext context,
      Consumer<ClientEvent> eventSink,
      Consumer<String> logSink) {
    List<ClientEvent> events = sendHandler.apply(params);
    if (eventSink != null) {
      events.forEach(eventSink);
    }
    return events;
  }

  @Override
  public Task getTask(AgentCard card, String taskId, ClientCallContext context) {
    return getTaskHandler.apply(taskId);
  }

  @Override
  public ListTasksResult listTasks(
      AgentCard card, ListTasksParams params, ClientCallContext context) {
    return listTasksHandler.apply(params);
  }

  @Override
  public Task cancelTask(AgentCard card, String taskId, ClientCallContext context) {
    return cancelTaskHandler.apply(taskId);
  }

  @Override
  public java.util.concurrent.CompletableFuture<SendMessageResult> subscribeToTask(
      AgentCard card,
      String taskId,
      ClientCallContext context,
      Consumer<ClientEvent> eventCallback) {
    subscribeHandler.handle(taskId, context, eventCallback);
    return java.util.concurrent.CompletableFuture.completedFuture(
        SendMessageResult.builder().build());
  }

  @Override
  public void close() {
    closeHandler.run();
  }
}
