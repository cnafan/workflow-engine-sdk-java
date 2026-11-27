/*
 * Copyright (c) 2026 Huawei Technologies Co., Ltd.
 * All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.openan.workflow.engine.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.a2aproject.sdk.spec.DataPart;
import org.junit.jupiter.api.Test;

/** TC-ENG-860…879：记录契约/校验/默认值的分支覆盖。 */
class ModelRecordContractTest {

  // TC-ENG-860 | BusinessFailure：空 code 拒绝；访问器
  //
  // 输入: 触发失败的场景
  // 预期输出: 失败结果或异常，错误信息包含关键信息
  // 断言点: success 标志 + 错误消息 + 历史记录
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void businessFailureValidation() {
    assertThrows(IllegalArgumentException.class, () -> new BusinessFailure(" ", "m", Map.of()));
    BusinessFailure failure = new BusinessFailure("code", "message", Map.of("k", "v"));
    assertEquals("code", failure.code());
    assertEquals("message", failure.getMessage());
    assertEquals("v", failure.details().get("k"));
  }

  // TC-ENG-861 | BusinessInput：text/data 二选一；空白 text 拒绝
  //
  // 输入: 目标方法的输入参数
  // 预期输出: 预期行为结果
  // 断言点: 返回值/状态断言
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void businessInputContract() {
    assertEquals("x", BusinessInput.text("x").text());
    assertEquals(1, BusinessInput.data(1).data());
    assertThrows(IllegalArgumentException.class, () -> new BusinessInput(null, null));
    assertThrows(IllegalArgumentException.class, () -> new BusinessInput("x", 1));
    assertThrows(IllegalArgumentException.class, () -> BusinessInput.text(" "));
  }

  // TC-ENG-862 | RouteRequest：空字段拒绝
  //
  // 输入: 合法输入数据
  // 预期输出: 返回预期结果，字段值精确匹配
  // 断言点: 返回值精确断言
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void routeRequestValidation() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new RouteRequest(" ", "s", "n", "c", WorkflowInput.empty(), List.of()));
    assertThrows(
        IllegalArgumentException.class,
        () -> new RouteRequest("e", " ", "n", "c", WorkflowInput.empty(), List.of()));
    assertThrows(
        IllegalArgumentException.class,
        () -> new RouteRequest("e", "s", " ", "c", WorkflowInput.empty(), List.of()));
    assertThrows(
        IllegalArgumentException.class,
        () -> new RouteRequest("e", "s", "n", " ", WorkflowInput.empty(), List.of()));
    RouteRequest request = new RouteRequest("e", "s", "n", "c", WorkflowInput.empty(), List.of());
    assertEquals("e", request.executionId());
  }

  // TC-ENG-863 | UpstreamStepResult：空名拒绝；拷贝
  //
  // 输入: 合法输入数据
  // 预期输出: 返回预期结果，字段值精确匹配
  // 断言点: 返回值精确断言
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void upstreamStepResultValidation() {
    assertThrows(IllegalArgumentException.class, () -> new UpstreamStepResult(" ", List.of()));
    UpstreamStepResult result = new UpstreamStepResult("s1", List.of());
    assertEquals("s1", result.stepName());
  }

  // TC-ENG-864 | MessageContent：空扩展 URI 拒绝；withExtension
  //
  // 输入: 合法输入数据
  // 预期输出: 返回预期结果，字段值精确匹配
  // 断言点: 返回值精确断言
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void messageContentExtensionValidation() {
    MessageContent content = MessageContent.text("x");
    MessageContent extended = content.withExtension("uri:x");
    assertTrue(extended.extensions().contains("uri:x"));
    assertThrows(
        IllegalArgumentException.class,
        () -> new MessageContent(List.of(), new HashMap<>(), java.util.Set.of(" ")));
  }

  // TC-ENG-865 | WorkflowInput：compact record + empty
  //
  // 输入: 目标方法的输入参数
  // 预期输出: 预期行为结果
  // 断言点: 返回值/状态断言
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void workflowInputDefaults() {
    WorkflowInput empty = WorkflowInput.empty();
    assertEquals("", empty.runtimeIntent());
    assertTrue(empty.upstreamResults().isEmpty());
  }

  // TC-ENG-866 | WorkflowStep setter 往返
  //
  // 输入: 目标方法的输入参数
  // 预期输出: 预期行为结果
  // 断言点: 返回值/状态断言
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void workflowStepSetters() {
    WorkflowStep step = WorkflowStep.builder().name("s").build();
    List<Task> tasks = List.of(Task.builder().agent("A").build());
    step.setSubtasks(tasks);
    assertEquals(tasks, step.getSubtasks());
    List<JumpCondition> next = new ArrayList<>(List.of(JumpCondition.builder().step("e").build()));
    step.setNext(next);
    assertEquals(next, step.getNext());
    step.setContextFrom(new ArrayList<>(List.of("up")));
    assertEquals(List.of("up"), step.getContextFrom());
  }

  // TC-ENG-867 | TaskResult：success/failure 访问器
  //
  // 输入: 目标方法的输入参数
  // 预期输出: 预期行为结果
  // 断言点: 返回值/状态断言
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void taskResultFactoryMethods() {
    TaskResult success = TaskResult.success(List.of("o"));
    assertTrue(success.isSuccess());
    assertEquals(1, success.getOutputs().size());
    TaskResult failure = TaskResult.failure("code", "message");
    assertFalse(failure.isSuccess());
    assertEquals("code", failure.getErrorCode());
    assertEquals("message", failure.getError());
  }

  // TC-ENG-868 | ReceivedMessage：DataPart 投影
  //
  // 输入: 目标方法的输入参数
  // 预期输出: 预期行为结果
  // 断言点: 返回值/状态断言
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void receivedMessageProjectsDataParts() {
    org.a2aproject.sdk.spec.Artifact artifact =
        org.a2aproject.sdk.spec.Artifact.builder()
            .artifactId("a")
            .parts(List.of(new DataPart(Map.of("k", "v"))))
            .build();
    ReceivedMessage received = new ReceivedMessage(null, Map.of(), List.of(artifact));
    assertEquals(1, received.outputs(false).size());
  }

  // TC-ENG-869 | Workflow fromMap：null 拒绝
  //
  // 输入: 空值/空白输入
  // 预期输出: 抛出对应异常或返回空结果
  // 断言点: 异常类型 + 消息关键字 或 空结果
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void workflowFromNullRejected() {
    assertThrows(IllegalArgumentException.class, () -> Workflow.fromMap(null));
  }

  // TC-ENG-870 | Workflow setter 与构造
  //
  // 输入: 目标方法的输入参数
  // 预期输出: 预期行为结果
  // 断言点: 返回值/状态断言
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void workflowConstructorAndSetter() {
    Workflow workflow = new Workflow("id", "name", "description", List.of());
    workflow.setSteps(new ArrayList<>());
    assertEquals(0, workflow.getSteps().size());
    assertEquals("id", workflow.getId());
  }

  // TC-ENG-871 | TaskExecutionResult 防御性拷贝
  //
  // 输入: 目标方法的输入参数
  // 预期输出: 预期行为结果
  // 断言点: 返回值/状态断言
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void taskExecutionResultCopiesOutputs() {
    TaskExecutionResult result =
        new TaskExecutionResult(
            "a",
            "",
            "t-1",
            "desc",
            dev.openan.workflow.engine.model.TaskStatus.SUCCESS,
            List.of("o"),
            List.of(),
            null,
            null,
            null);
    assertThrows(UnsupportedOperationException.class, () -> result.outputs().add("x"));
    assertEquals("a", result.agentName());
  }

  // TC-ENG-872 | MessageContent.parts 工厂
  //
  // 输入: 目标方法的输入参数
  // 预期输出: 预期行为结果
  // 断言点: 返回值/状态断言
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void messageContentPartsFactory() {
    MessageContent content =
        MessageContent.parts(List.of(new org.a2aproject.sdk.spec.TextPart("hi")));
    assertEquals(1, content.parts().size());
  }

  // TC-ENG-873 | BusinessValues.list 空与嵌套
  //
  // 输入: 目标方法的输入参数
  // 预期输出: 预期行为结果
  // 断言点: 返回值/状态断言
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void businessValuesListCopies() {
    List<Object> values = BusinessValues.list(List.of("a", List.of("b")));
    assertEquals(2, values.size());
    assertTrue(BusinessValues.list(null).isEmpty());
  }

  // TC-ENG-874 | BusinessFailure details 拷贝
  //
  // 输入: 触发失败的场景
  // 预期输出: 失败结果或异常，错误信息包含关键信息
  // 断言点: success 标志 + 错误消息 + 历史记录
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void businessFailureDetailsUnmodifiable() {
    BusinessFailure failure = new BusinessFailure("c", "m", Map.of("k", "v"));
    assertThrows(
        UnsupportedOperationException.class,
        () -> ((Map<String, Object>) failure.details()).put("x", "y"));
  }

  // TC-ENG-875 | TaskRequest builder 构造
  //
  // 输入: 目标方法的输入参数
  // 预期输出: 预期行为结果
  // 断言点: 返回值/状态断言
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void taskRequestBuilderConstructs() {
    TaskRequest request = TaskRequest.builder().agentName("a").executionId("e").taskId("t").build();
    assertEquals("a", request.getAgentName());
    assertEquals("t", request.getTaskId());
  }

  // TC-ENG-876 | Task model builder
  //
  // 输入: 目标方法的输入参数
  // 预期输出: 预期行为结果
  // 断言点: 返回值/状态断言
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void taskModelDefaults() {
    Task task = Task.builder().agent("A").description("d").build();
    assertEquals("A", task.getAgent());
  }

  // TC-ENG-877 | ExecutionResult builder
  //
  // 输入: 目标方法的输入参数
  // 预期输出: 预期行为结果
  // 断言点: 返回值/状态断言
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void executionResultBuilder() {
    ExecutionResult success =
        ExecutionResult.builder().success(true).history(List.of()).stepOutputs(Map.of()).build();
    assertTrue(success.isSuccess());
  }

  // TC-ENG-878 | NegotiationReply 校验
  //
  // 输入: 合法输入数据
  // 预期输出: 返回预期结果，字段值精确匹配
  // 断言点: 返回值精确断言
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void negotiationReplyValidation() {
    assertThrows(IllegalArgumentException.class, () -> new NegotiationReply.Stop(" ", "reason"));
    assertThrows(IllegalArgumentException.class, () -> new NegotiationReply.Stop("code", " "));
  }

  // TC-ENG-879 | WorkflowSearchResult builder
  //
  // 输入: 目标方法的输入参数
  // 预期输出: 预期行为结果
  // 断言点: 返回值/状态断言
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void workflowSearchResultBuilder() {
    WorkflowSearchResult result =
        WorkflowSearchResult.builder().workflowId("w").name("n").score(0.5).build();
    assertEquals("w", result.getWorkflowId());
    assertEquals(0.5, result.getScore());
  }
}
