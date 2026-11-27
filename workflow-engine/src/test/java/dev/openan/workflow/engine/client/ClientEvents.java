/*
 * Copyright (c) 2026 Huawei Technologies Co., Ltd.
 * All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.openan.workflow.engine.client;

import java.util.List;
import java.util.Map;
import org.a2aproject.sdk.client.ClientEvent;
import org.a2aproject.sdk.client.TaskEvent;
import org.a2aproject.sdk.client.TaskUpdateEvent;
import org.a2aproject.sdk.spec.Message;
import org.a2aproject.sdk.spec.Task;
import org.a2aproject.sdk.spec.TaskState;
import org.a2aproject.sdk.spec.TaskStatus;
import org.a2aproject.sdk.spec.TaskStatusUpdateEvent;

/** Package-private builders for scripted SDK events used in branch coverage tests. */
final class ClientEvents {

  private ClientEvents() {}

  static ClientEvent inputRequired(String contextId, Object negotiationContext) {
    org.a2aproject.sdk.spec.Message statusMessage =
        org.a2aproject.sdk.spec.Message.builder()
            .messageId("m")
            .role(org.a2aproject.sdk.spec.Message.Role.ROLE_AGENT)
            .parts(new org.a2aproject.sdk.spec.TextPart("propose"))
            .metadata(
                negotiationContext == null
                    ? Map.of()
                    : Map.of(
                        "negotiationContext",
                        negotiationContext,
                        A2ATExtension.NEGOTIATION_T.uri(),
                        Map.of("negotiationContext", negotiationContext)))
            .build();
    Task task =
        Task.builder()
            .id("rt")
            .contextId(contextId)
            .status(new TaskStatus(TaskState.TASK_STATE_INPUT_REQUIRED, statusMessage, null))
            .history(List.of())
            .metadata(
                negotiationContext == null
                    ? Map.of()
                    : Map.of(
                        A2ATExtension.NEGOTIATION_T.uri(),
                        Map.of("negotiationContext", negotiationContext)))
            .build();
    TaskStatusUpdateEvent status =
        TaskStatusUpdateEvent.builder()
            .taskId("rt")
            .contextId(contextId)
            .status(new TaskStatus(TaskState.TASK_STATE_INPUT_REQUIRED, statusMessage, null))
            .build();
    return new TaskUpdateEvent(task, status);
  }

  static ClientEvent taskEvent(String contextId, TaskState state) {
    return new TaskEvent(
        Task.builder()
            .id("rt")
            .contextId(contextId)
            .status(new TaskStatus(state))
            .history(List.of())
            .metadata(Map.of())
            .build());
  }

  static Message message(String id, String text, String contextId, Map<String, Object> metadata) {
    Message.Builder builder =
        Message.builder()
            .messageId(id)
            .role(Message.Role.ROLE_AGENT)
            .metadata(metadata)
            .parts(new org.a2aproject.sdk.spec.TextPart(text));
    if (contextId != null) {
      builder.contextId(contextId);
    }
    return builder.build();
  }
}
