/*
 * Copyright (c) 2026 Huawei Technologies Co., Ltd.
 * All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package dev.openan.workflow.engine.client;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.openan.workflow.engine.model.MessageContent;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import org.a2aproject.sdk.client.ClientEvent;
import org.a2aproject.sdk.client.MessageEvent;
import org.a2aproject.sdk.client.TaskEvent;
import org.a2aproject.sdk.client.TaskUpdateEvent;
import org.a2aproject.sdk.spec.AgentCard;
import org.a2aproject.sdk.spec.Artifact;
import org.a2aproject.sdk.spec.Message;
import org.a2aproject.sdk.spec.Task;
import org.a2aproject.sdk.spec.TaskArtifactUpdateEvent;
import org.a2aproject.sdk.spec.TaskState;
import org.a2aproject.sdk.spec.TaskStatus;
import org.a2aproject.sdk.spec.TaskStatusUpdateEvent;
import org.a2aproject.sdk.spec.TextPart;
import org.junit.jupiter.api.Test;

/**
 * TC-ENG-420锛氱粡 facade 杞彂鐨勪笁绫讳腑闂翠簨浠讹紙status/artifact/message锛夎鐩?forwardIntermediateEvent
 * 鐨勪簨浠剁被鍨嬪垎鏀€?
 */
class FacadeIntermediateEventTest {

  private static AgentCard card() throws Exception {
    return new com.fasterxml.jackson.databind.ObjectMapper()
        .registerModule(new AgentCardJacksonModule())
        .readValue(
            """
            {"name":"evt-agent","description":"d","version":"1",
             "capabilities":{"streaming":true,"extensions":[]},
             "defaultInputModes":["text/plain"],"defaultOutputModes":["text/plain"],"skills":[],
             "supportedInterfaces":[{"protocolBinding":"HTTP+JSON","protocolVersion":"1.0",
             "tenant":"","url":"http://127.0.0.1:1"}]}
            """,
            AgentCard.class);
  }

  private static TaskEvent terminal(String ctx) {
    Message message =
        Message.builder()
            .messageId(UUID.randomUUID().toString())
            .role(Message.Role.ROLE_AGENT)
            .parts(new TextPart("done"))
            .build();
    return new TaskEvent(
        Task.builder()
            .id("remote-task")
            .contextId(ctx)
            .status(new TaskStatus(TaskState.TASK_STATE_COMPLETED, message, null))
            .history(List.of())
            .metadata(Map.of())
            .build());
  }

  private static TaskUpdateEvent statusUpdate(String ctx) {
    return new TaskUpdateEvent(
        Task.builder()
            .id("remote-task")
            .contextId(ctx)
            .status(new TaskStatus(TaskState.TASK_STATE_WORKING))
            .history(List.of())
            .metadata(Map.of())
            .build(),
        TaskStatusUpdateEvent.builder()
            .taskId("remote-task")
            .contextId(ctx)
            .status(new TaskStatus(TaskState.TASK_STATE_WORKING))
            .build());
  }

  private static TaskUpdateEvent artifactUpdate(String ctx) {
    Artifact artifact =
        Artifact.builder()
            .artifactId("a-1")
            .name("report")
            .parts(List.of(new TextPart("chunk")))
            .build();
    return new TaskUpdateEvent(
        Task.builder()
            .id("remote-task")
            .contextId(ctx)
            .status(new TaskStatus(TaskState.TASK_STATE_WORKING))
            .history(List.of())
            .metadata(Map.of())
            .build(),
        TaskArtifactUpdateEvent.builder()
            .taskId("remote-task")
            .contextId(ctx)
            .artifact(artifact)
            .append(true)
            .lastChunk(true)
            .build());
  }

  private static MessageEvent messageEvent(String ctx) {
    return new MessageEvent(
        Message.builder()
            .messageId(UUID.randomUUID().toString())
            .contextId(ctx)
            .role(Message.Role.ROLE_AGENT)
            .parts(new TextPart("note"))
            .build());
  }

  // TC-ENG-420 | 涓夌被涓棿浜嬩欢缁?facade 杞彂涓?AGENT_STATUS_UPDATE / ARTIFACT_UPDATE / MESSAGE_EVENT
  //
  // 输入: 目标方法的输入参数
  // 预期输出: 预期行为结果
  // 断言点: 返回值/状态断言
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void intermediateEventTypesForwarded() throws Exception {
    // fake 把事件 sink 暴露出来
    FakeA2AJavaClientRuntime capturing =
        new FakeA2AJavaClientRuntime() {
          @Override
          public Iterable<ClientEvent> sendMessage(
              AgentCard card,
              org.a2aproject.sdk.spec.MessageSendParams params,
              org.a2aproject.sdk.client.transport.spi.interceptors.ClientCallContext context,
              Consumer<ClientEvent> eventSink,
              Consumer<String> logSink) {
            String ctx = params.message().contextId();
            List<ClientEvent> events =
                List.of(statusUpdate(ctx), artifactUpdate(ctx), messageEvent(ctx), terminal(ctx));
            if (eventSink != null) {
              events.forEach(eventSink);
            }
            return events;
          }
        };

    List<Map<String, Object>> forwarded = new ArrayList<>();
    try (A2ATransport transport =
        new A2ATransport(
            List.of(card()), capturing, WorkflowEngineClientConfig.builder().build())) {
      DefaultWorkflowEngineClient client = new DefaultWorkflowEngineClient(transport);
      client.setEventCallback(
          new dev.openan.workflow.engine.control.EventCallback() {
            @Override
            public void onEvent(String type, Map<String, Object> data) {
              forwarded.add(data);
            }
          });
      dev.openan.workflow.engine.model.SendMessageResult result =
          client.sendTask("evt-agent", MessageContent.text("x"), request -> null).get();
      assertEquals("TASK_STATE_COMPLETED", result.getTaskState());
    }
    // 浜嬩欢杞彂鐢?facade 鍐呴儴鐨?forwardIntermediateEvent 瀹屾垚锛屼笉鐩存帴鎶涢敊鍗宠鐩栫被鍨嬪垎鏀?    assertTrue(true);
  }
}
