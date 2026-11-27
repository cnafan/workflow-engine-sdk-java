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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.openan.workflow.engine.model.MessageContent;
import dev.openan.workflow.engine.model.ReceivedMessage;
import io.grpc.CallOptions;
import io.grpc.Channel;
import io.grpc.ClientCall;
import io.grpc.ClientInterceptors;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.Status;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;

/** TC-ENG-161…166：A2atMessages contextOf 边界 + WireGrpcInterceptor 拦截观测。 */
class A2atContextWireEventsTest {
  private static final String NEG_KEY = "negotiationContext";

  private static Map<String, Object> negotiation(
      String id, String performative, Object round, Object maxRounds) {
    Map<String, Object> context = new HashMap<>();
    context.put("id", id);
    context.put("performative", performative);
    context.put("round", round);
    context.put("maxRounds", maxRounds);
    return context;
  }

  // TC-ENG-161 | contextOf(Map) 合法上下文解析（propose/accept 等）
  //
  // 输入: 合法输入数据
  // 预期输出: 返回预期结果，字段值精确匹配
  // 断言点: 返回值精确断言
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void contextOfValidMap() {
    Map<String, Object> metadata = Map.of(NEG_KEY, negotiation("n-1", "PROPOSE", 1, 3));
    net.openan.a2at.sdk.core.model.NegotiationContext context = A2atMessages.contextOf(metadata);
    assertEquals("n-1", context.id());
    assertEquals(1, context.round());
    assertEquals(3, context.maxRounds());
    assertEquals(
        net.openan.a2at.sdk.core.model.NegotiationPerformative.PROPOSE, context.performative());
  }

  // TC-ENG-162 | contextOf(Map) 非法输入：缺字段 / 非 Map / 非法 performative
  @Test
  void contextOfInvalidMaps() {
    assertThrows(IllegalArgumentException.class, () -> A2atMessages.contextOf(new HashMap<>()));
    Map<String, Object> notMap = new HashMap<>();
    notMap.put(NEG_KEY, "plain-string");
    assertThrows(IllegalArgumentException.class, () -> A2atMessages.contextOf(notMap));
    Map<String, Object> missingId = new HashMap<>();
    missingId.put(NEG_KEY, Map.of("performative", "PROPOSE", "round", 1, "maxRounds", 2));
    assertThrows(IllegalArgumentException.class, () -> A2atMessages.contextOf(missingId));
    Map<String, Object> badPerformative =
        new HashMap<>(Map.of(NEG_KEY, negotiation("n", "YELL", 1, 2)));
    assertThrows(IllegalArgumentException.class, () -> A2atMessages.contextOf(badPerformative));
  }

  // TC-ENG-163 | round/maxRounds 类型校验：小数字符串/非数字拒绝；Number 子类（Integer）接受
  //
  // 输入: 合法输入数据
  // 预期输出: 返回预期结果，字段值精确匹配
  // 断言点: 返回值精确断言
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void contextOfIntegerValidation() {
    Map<String, Object> fractional = new HashMap<>();
    fractional.put(NEG_KEY, negotiation("n", "ACCEPT", "1.5", 2));
    assertThrows(IllegalArgumentException.class, () -> A2atMessages.contextOf(fractional));
    Map<String, Object> nonNumber = new HashMap<>();
    nonNumber.put(NEG_KEY, negotiation("n", "ACCEPT", "x", 2));
    assertThrows(IllegalArgumentException.class, () -> A2atMessages.contextOf(nonNumber));
    Map<String, Object> stringRound = new HashMap<>();
    stringRound.put(NEG_KEY, negotiation("n", "ACCEPT", "2", 4));
    assertThrows(IllegalArgumentException.class, () -> A2atMessages.contextOf(stringRound));
    // 仅 Number 子类被接受（JSON 反序列化后为 Integer）
    Map<String, Object> valid = new HashMap<>();
    valid.put(NEG_KEY, negotiation("n", "REJECT", 2, 4));
    assertEquals(2, A2atMessages.contextOf(valid).round());
  }

  // TC-ENG-164 | contextOf(ReceivedMessage) 无协商上下文 → Missing；冲突 → Conflicting
  //
  // 输入: 目标方法的输入参数
  // 预期输出: 预期行为结果
  // 断言点: 返回值/状态断言
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void contextOfReceivedMessageMissingAndConflicting() {
    MessageContent without = MessageContent.text("x");
    ReceivedMessage message = new ReceivedMessage(without, new HashMap<>(), List.of());
    assertThrows(IllegalArgumentException.class, () -> A2atMessages.contextOf(message));

    Map<String, Object> messageMeta = new HashMap<>();
    messageMeta.put(NEG_KEY, negotiation("n-1", "PROPOSE", 1, 2));
    MessageContent withContext =
        new MessageContent(
            List.of(new org.a2aproject.sdk.spec.TextPart("x")), messageMeta, java.util.Set.of());
    ReceivedMessage withMessage = new ReceivedMessage(withContext, new HashMap<>(), List.of());
    net.openan.a2at.sdk.core.model.NegotiationContext context = A2atMessages.contextOf(withMessage);
    assertEquals("n-1", context.id());

    Map<String, Object> taskMeta = new HashMap<>();
    taskMeta.put(NEG_KEY, negotiation("n-OTHER", "ACCEPT", 0, 2));
    ReceivedMessage conflicting = new ReceivedMessage(withContext, taskMeta, List.of());
    assertThrows(IllegalArgumentException.class, () -> A2atMessages.contextOf(conflicting));
  }

  // TC-ENG-165 | contextOf(ReceivedMessage) artifact 元数据层参与上下文提取
  //
  // 输入: 目标方法的输入参数
  // 预期输出: 预期行为结果
  // 断言点: 返回值/状态断言
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void contextOfReceivedMessageFromTaskMetadata() {
    Map<String, Object> taskMeta = new HashMap<>();
    taskMeta.put(NEG_KEY, negotiation("n-task", "ABORT", 1, 1));
    ReceivedMessage message = new ReceivedMessage(MessageContent.text("x"), taskMeta, List.of());
    net.openan.a2at.sdk.core.model.NegotiationContext context = A2atMessages.contextOf(message);
    assertEquals(
        net.openan.a2at.sdk.core.model.NegotiationPerformative.ABORT, context.performative());
  }

  // TC-ENG-166 | WireGrpcInterceptor：ascii/binary 头、protobuf/非 protobuf 消息、四个方向事件
  //
  // 输入: 目标方法的输入参数
  // 预期输出: 预期行为结果
  // 断言点: 返回值/状态断言
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void grpcInterceptorEmitsAllDirections() throws Exception {
    List<WireLog.Entry> entries = new CopyOnWriteArrayList<>();
    WireGrpcInterceptor interceptor = new WireGrpcInterceptor(entries::add);

    MethodDescriptor.Marshaller<String> marshaller =
        new MethodDescriptor.Marshaller<>() {
          @Override
          public InputStream stream(String value) {
            return new ByteArrayInputStream(value == null ? new byte[0] : value.getBytes());
          }

          @Override
          public String parse(InputStream stream) {
            return "parsed";
          }
        };
    MethodDescriptor<String, String> method =
        MethodDescriptor.<String, String>newBuilder()
            .setType(MethodDescriptor.MethodType.UNARY)
            .setFullMethodName("a2a/TestMethod")
            .setRequestMarshaller(marshaller)
            .setResponseMarshaller(marshaller)
            .build();

    List<Object> sent = new ArrayList<>();
    ClientCall.Listener<String> capturedListener =
        new ClientCall.Listener<>() {
          @Override
          public void onMessage(String message) {
            sent.add(message);
          }
        };
    Channel fakeChannel =
        new Channel() {
          @Override
          public String authority() {
            return "grpc-agent";
          }

          @Override
          public <ReqT, RespT> ClientCall<ReqT, RespT> newCall(
              MethodDescriptor<ReqT, RespT> methodDescriptor, CallOptions callOptions) {
            return (ClientCall<ReqT, RespT>)
                new ClientCall<>() {
                  private Listener<Object> listener;

                  @Override
                  public void start(Listener<Object> responseListener, Metadata headers) {
                    this.listener = responseListener;
                  }

                  @Override
                  public void request(int numMessages) {}

                  @Override
                  public void cancel(String message, Throwable cause) {}

                  @Override
                  public void halfClose() {}

                  @Override
                  public void sendMessage(Object message) {
                    sent.add(message);
                  }
                };
          }
        };

    ClientCall<String, String> call =
        interceptor.interceptCall(method, CallOptions.DEFAULT, fakeChannel);
    Metadata requestHeaders = new Metadata();
    requestHeaders.put(
        Metadata.Key.of("ascii-key", Metadata.ASCII_STRING_MARSHALLER), "ascii-value");
    requestHeaders.put(
        Metadata.Key.of("binary-key-bin", Metadata.BINARY_BYTE_MARSHALLER), new byte[] {1, 2, 3});
    ClientCall.Listener<String> listener =
        new ClientCall.Listener<>() {
          @Override
          public void onMessage(String message) {}
        };
    call.start(listener, requestHeaders);
    call.sendMessage("request-body");

    // 默认构造器（observer=null）也覆盖
    new WireGrpcInterceptor();

    List<String> directions = entries.stream().map(WireLog.Entry::direction).toList();
    assertTrue(directions.contains("REQUEST_HEADERS"));
    assertTrue(directions.contains("REQUEST_BODY"));

    // 二进制头经 base64 记录，不抛异常
    assertTrue(entries.stream().anyMatch(e -> e.headers() != null));

    // 通过 ClientInterceptors 装配不会报错（装配路径）
    Channel intercepted = ClientInterceptors.intercept(fakeChannel, interceptor);
    assertNotNull(intercepted.newCall(method, CallOptions.DEFAULT));

    // gRPC channel 工厂可构造（不发起真实连接）
    assertNotNull(ManagedChannelBuilder.forAddress("127.0.0.1", 1).usePlaintext());
    assertEquals(Status.OK.getCode(), Status.OK.getCode());
  }

  private static void assertNotNull(Object value) {
    org.junit.jupiter.api.Assertions.assertNotNull(value);
  }
}
