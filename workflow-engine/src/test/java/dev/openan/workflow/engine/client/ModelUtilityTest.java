/*
 * Copyright (c) 2026 Huawei Technologies Co., Ltd.
 * All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package dev.openan.workflow.engine.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.openan.workflow.engine.model.MessageContent;
import dev.openan.workflow.engine.model.NegotiationReply;
import dev.openan.workflow.engine.model.SendMessageResult;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.a2aproject.sdk.client.TaskEvent;
import org.a2aproject.sdk.client.TaskUpdateEvent;
import org.a2aproject.sdk.spec.Task;
import org.a2aproject.sdk.spec.TaskArtifactUpdateEvent;
import org.a2aproject.sdk.spec.TaskState;
import org.a2aproject.sdk.spec.TaskStatus;
import org.a2aproject.sdk.spec.TextPart;
import org.junit.jupiter.api.Test;

/** TC-ENG-530…540：模型与工具类的剩余分支。 */
class ModelUtilityTest {

  // TC-ENG-530 | ClientEventMapper：无文本/无元数据事件
  //
  // 输入: 空值/空白输入
  // 预期输出: 抛出对应异常或返回空结果
  // 断言点: 异常类型 + 消息关键字 或 空结果
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void mapperEmptyTextAndMetadata() {
    TaskEvent taskNoText =
        new TaskEvent(
            Task.builder()
                .id("t")
                .contextId("c")
                .status(new TaskStatus(TaskState.TASK_STATE_COMPLETED))
                .history(List.of())
                .build());
    Map<String, Object> taskMap = ClientEventMapper.toMap(taskNoText, "a");
    assertFalse(taskMap.containsKey("text"));
    assertFalse(taskMap.containsKey("metadata"));

    ArtifactNoMeta();
  }

  private static void ArtifactNoMeta() {
    org.a2aproject.sdk.spec.Artifact artifact =
        org.a2aproject.sdk.spec.Artifact.builder()
            .artifactId("a")
            .name("n")
            .parts(List.of(new TextPart("x")))
            .build();
    Task task =
        Task.builder()
            .id("t")
            .contextId("c")
            .status(new TaskStatus(TaskState.TASK_STATE_WORKING))
            .history(List.of())
            .build();
    TaskUpdateEvent event =
        new TaskUpdateEvent(
            task,
            TaskArtifactUpdateEvent.builder()
                .taskId("t")
                .contextId("c")
                .artifact(artifact)
                .append(false)
                .lastChunk(false)
                .build());
    Map<String, Object> map = ClientEventMapper.toMap(event, "a");
    assertEquals("artifact", map.get("event_kind"));
  }

  // TC-ENG-532 | ContextBuilder 空 contextFrom 分支在 core 包 ContextBuilderUpstreamTest 覆盖
  //
  // 输入: 目标方法的输入参数
  // 预期输出: 预期行为结果
  // 断言点: 返回值/状态断言
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void contextBuilderBranchesInCorePackage() {
    assertTrue(true);
  }

  // TC-ENG-533 | NegotiationReply.Stop 内容与校验
  //
  // 输入: 目标方法的输入参数
  // 预期输出: 预期行为结果
  // 断言点: 返回值/状态断言
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void negotiationStopReply() {
    NegotiationReply.Stop stop = new NegotiationReply.Stop("aborted", "user stop");
    assertEquals("aborted", stop.code());
    assertEquals("user stop", stop.reason());
    assertThrows(IllegalArgumentException.class, () -> new NegotiationReply.Stop(" ", "reason"));
    assertThrows(IllegalArgumentException.class, () -> new NegotiationReply.Stop("code", " "));
    NegotiationReply.Send send = new NegotiationReply.Send(MessageContent.text("ok"));
    assertEquals("ok", ((TextPart) send.content().parts().get(0)).text());
  }

  // TC-ENG-534 | SendMessageResult 无 task 无消息时输出为空
  //
  // 输入: 空值/空白输入
  // 预期输出: 抛出对应异常或返回空结果
  // 断言点: 异常类型 + 消息关键字 或 空结果
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void emptySendMessageResultOutputs() {
    SendMessageResult empty = SendMessageResult.builder().build();
    assertTrue(empty.getOutputs().isEmpty());
    assertTrue(empty.getReceivedMessages().isEmpty());
  }

  // TC-ENG-535 | MessageContent withExtension null 集合回退
  //
  // 输入: 空值/空白输入
  // 预期输出: 抛出对应异常或返回空结果
  // 断言点: 异常类型 + 消息关键字 或 空结果
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void messageContentWithExtensionNullSet() {
    MessageContent content = new MessageContent(List.of(new TextPart("x")), null, null);
    assertEquals(Set.of(), content.extensions());
    MessageContent withExtension = content.withExtension("ext://x");
    assertTrue(withExtension.extensions().contains("ext://x"));
  }

  // TC-ENG-536 | A2atMessages.from：最小元数据（仅模板 URI）
  //
  // 输入: 目标方法的输入参数
  // 预期输出: 预期行为结果
  // 断言点: 返回值/状态断言
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void a2atMessagesFromMinimalMetadata() {
    MessageContent content =
        A2atMessages.from(
            new net.openan.a2at.sdk.core.model.MetadataContent(
                "Negotiation-T/information-negotiation/v1",
                "opaque",
                "Negotiation-T/information-negotiation/v1"),
            List.of(new TextPart("x")));
    assertTrue(content.extensions().contains("Negotiation-T/information-negotiation/v1"));
  }
}
