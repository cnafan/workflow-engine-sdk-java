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
import dev.openan.workflow.engine.control.NegotiationStrategy;
import dev.openan.workflow.engine.model.MessageContent;
import dev.openan.workflow.engine.model.SendMessageResult;
import dev.openan.workflow.engine.model.TaskRequest;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import org.a2aproject.sdk.jsonrpc.common.wrappers.ListTasksResult;
import org.a2aproject.sdk.spec.ListTasksParams;

/** Sends final content and coordinates task interaction. Content generation belongs to the host. */
public interface WorkflowEngineClient {
  /**
   * Sends final task content outside the DAG using the configured negotiation callback. The same
   * task/context association is retained until the remote task reaches a final state.
   */
  CompletableFuture<SendMessageResult> sendTask(String agentName, MessageContent content);

  /**
   * Sends final task content outside the DAG with a per-call negotiation strategy. This overload
   * does not mutate the client's configured {@link ControlPoint}.
   */
  CompletableFuture<SendMessageResult> sendTask(
      String agentName, MessageContent content, NegotiationStrategy negotiationStrategy);

  /**
   * Sends final task content with callbacks scoped to this invocation. Implementations that emit
   * protocol lifecycle events should override this method so concurrent workflow executions do not
   * share mutable callback state.
   */
  default CompletableFuture<SendMessageResult> sendTask(
      String agentName,
      MessageContent content,
      NegotiationStrategy negotiationStrategy,
      EventCallback eventCallback) {
    return sendTask(agentName, content, negotiationStrategy);
  }

  /** Sends a DAG task using the executor's execution and logical task IDs. */
  default CompletableFuture<SendMessageResult> sendWorkflowTask(
      TaskRequest request,
      MessageContent content,
      NegotiationStrategy negotiationStrategy,
      EventCallback eventCallback) {
    return sendTask(request.getAgentName(), content, negotiationStrategy, eventCallback);
  }

  /**
   * Maximum wait for one task interaction, from initial content preparation through remote task
   * completion and any negotiation exchanges. The same budget applies independently to a route
   * callback.
   */
  default long callbackTimeoutSeconds() {
    return 600;
  }

  void setControlPoint(ControlPoint controlPoint);

  void setEventCallback(EventCallback callback);

  /** Queries an existing remote task. */
  CompletableFuture<SendMessageResult> getTask(String agentName, String taskId);

  /** Lists remote tasks visible to the configured authentication identity. */
  CompletableFuture<ListTasksResult> listTasks(String agentName, ListTasksParams params);

  /** Cancels an existing remote task; this is not a Negotiation-T Abort. */
  CompletableFuture<SendMessageResult> cancelTask(String agentName, String taskId);

  /** Subscribes to an existing task, preserving its identity. */
  CompletableFuture<SendMessageResult> subscribeToTask(
      String agentName, String taskId, Consumer<Map<String, Object>> eventCallback);

  void close();
}
