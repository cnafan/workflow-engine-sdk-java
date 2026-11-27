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

import static org.junit.jupiter.api.Assertions.*;

import com.google.protobuf.StringValue;
import io.grpc.*;
import io.grpc.protobuf.ProtoUtils;
import io.grpc.stub.ServerCalls;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;

class WireGrpcInterceptorTest {
  // TC-ENG | capturesRealGrpcMetadataAndProtobufWithoutInventingHttpBody
  //
  // 输入: 目标方法的输入参数
  // 预期输出: 预期行为结果
  // 断言点: 返回值/状态断言
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void capturesRealGrpcMetadataAndProtobufWithoutInventingHttpBody() throws Exception {
    var method =
        MethodDescriptor.<StringValue, StringValue>newBuilder()
            .setType(MethodDescriptor.MethodType.UNARY)
            .setFullMethodName("test.Protocol/Echo")
            .setRequestMarshaller(ProtoUtils.marshaller(StringValue.getDefaultInstance()))
            .setResponseMarshaller(ProtoUtils.marshaller(StringValue.getDefaultInstance()))
            .build();
    AtomicReference<StringValue> captured = new AtomicReference<>();
    AtomicReference<Metadata> capturedHeaders = new AtomicReference<>();
    var service =
        ServerServiceDefinition.builder("test.Protocol")
            .addMethod(
                method,
                ServerCalls.asyncUnaryCall(
                    (StringValue request, io.grpc.stub.StreamObserver<StringValue> response) -> {
                      captured.set(request);
                      response.onNext(request);
                      response.onCompleted();
                    }))
            .build();
    ServerInterceptor receiver =
        new ServerInterceptor() {
          public <Q, R> ServerCall.Listener<Q> interceptCall(
              ServerCall<Q, R> call, Metadata headers, ServerCallHandler<Q, R> next) {
            capturedHeaders.set(headers);
            return next.startCall(
                new ForwardingServerCall.SimpleForwardingServerCall<Q, R>(call) {
                  public void sendHeaders(Metadata headers) {
                    var key = Metadata.Key.of("x-multi", Metadata.ASCII_STRING_MARSHALLER);
                    headers.put(key, "one");
                    headers.put(key, "two");
                    super.sendHeaders(headers);
                  }
                },
                headers);
          }
        };
    Server server =
        ServerBuilder.forPort(0)
            .addService(ServerInterceptors.intercept(service, receiver))
            .build()
            .start();
    List<WireLog.Entry> logs = new CopyOnWriteArrayList<>();
    ManagedChannel channel =
        ManagedChannelBuilder.forAddress("127.0.0.1", server.getPort())
            .usePlaintext()
            .intercept(new WireGrpcInterceptor(logs::add))
            .build();
    try {
      Metadata headers = new Metadata();
      var version = Metadata.Key.of("a2a-version", Metadata.ASCII_STRING_MARSHALLER);
      headers.put(version, "1.0");
      headers.put(
          Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER), "Bearer hidden");
      CountDownLatch done = new CountDownLatch(1);
      AtomicReference<Status> status = new AtomicReference<>();
      AtomicReference<StringValue> received = new AtomicReference<>();
      ClientCall<StringValue, StringValue> call =
          WireLog.call(
              Map.of("executionId", "exec-1"),
              () ->
                  channel.newCall(
                      method, CallOptions.DEFAULT.withDeadlineAfter(5, TimeUnit.SECONDS)));
      call.start(
          new ClientCall.Listener<>() {
            public void onMessage(StringValue value) {
              received.set(value);
            }

            public void onClose(Status value, Metadata trailers) {
              status.set(value);
              done.countDown();
            }
          },
          headers);
      call.request(1);
      StringValue payload = StringValue.of("诊断输入");
      call.sendMessage(payload);
      call.halfClose();
      assertTrue(done.await(6, TimeUnit.SECONDS));
      assertTrue(status.get().isOk());
      assertEquals(payload, captured.get());
      assertEquals(payload, received.get());
      assertEquals("1.0", capturedHeaders.get().get(version));
      assertTrue(logs.stream().allMatch(e -> e.boundary().equals("DIRECT_GRPC")));
      assertTrue(logs.stream().allMatch(e -> e.correlation().get("executionId").equals("exec-1")));
      assertEquals(1, logs.stream().map(WireLog.Entry::requestId).distinct().count());
      var request =
          logs.stream().filter(e -> e.direction().equals("REQUEST_BODY")).findFirst().orElseThrow();
      assertEquals("protobuf-json-view", request.representation());
      assertEquals("\"诊断输入\"", request.body());
      var response =
          logs.stream()
              .filter(e -> e.direction().equals("RESPONSE_HEADERS"))
              .findFirst()
              .orElseThrow();
      assertEquals(List.of("one", "two"), response.headers().get("x-multi"));
      assertFalse(logs.toString().contains("hidden"));
    } finally {
      channel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
      server.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
    }
  }
}
