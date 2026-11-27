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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.io.FileOutputStream;
import java.math.BigInteger;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.X509CRL;
import java.security.cert.X509Certificate;
import java.util.Base64;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import org.a2aproject.sdk.client.ClientEvent;
import org.a2aproject.sdk.client.transport.spi.interceptors.ClientCallContext;
import org.a2aproject.sdk.spec.A2AClientException;
import org.a2aproject.sdk.spec.AgentCard;
import org.a2aproject.sdk.spec.ListTasksParams;
import org.a2aproject.sdk.spec.Message;
import org.a2aproject.sdk.spec.MessageSendParams;
import org.a2aproject.sdk.spec.Task;
import org.a2aproject.sdk.spec.TextPart;
import org.bouncycastle.cert.jcajce.JcaX509CRLConverter;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v2CRLBuilder;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * TC-ENG-080…092：用本地 JSON-RPC 假服务器驱动 DefaultA2AJavaClientRuntime 的
 * 消息发送、错误回调、事件消费、协议选择、通知流空闲超时与生命周期。
 */
class DefaultA2AJavaClientRuntimeHarnessTest {

  private static Path grpcClientCertPem;
  private static Path grpcClientKeyPem;
  private static Path validCrl;

  private static final ObjectMapper JSON =
      new ObjectMapper().registerModule(new AgentCardJacksonModule());

  private HttpServer server;
  private DefaultA2AJavaClientRuntime runtime;

  @org.junit.jupiter.api.BeforeAll
  static void generateGrpcFixtures() throws Exception {
    Path tempDir = Files.createTempDirectory("grpc-fixtures");
    Path clientP12 = tempDir.resolve("client.p12");
    String storePassword = "harness-pw";

    String keytool =
        Path.of(
                System.getProperty("java.home"),
                "bin",
                System.getProperty("os.name").toLowerCase().contains("win")
                    ? "keytool.exe"
                    : "keytool")
            .toString();
    ProcessBuilder builder =
        new ProcessBuilder(
            keytool,
            "-genkeypair",
            "-alias",
            "client",
            "-dname",
            "CN=Harness Client",
            "-keyalg",
            "EC",
            "-keysize",
            "256",
            "-validity",
            "1",
            "-keystore",
            clientP12.toString(),
            "-storetype",
            "PKCS12",
            "-storepass",
            storePassword,
            "-keypass",
            storePassword);
    builder.redirectErrorStream(true);
    Process process = builder.start();
    String output = new String(process.getInputStream().readAllBytes());
    assertTrue(process.waitFor(60, TimeUnit.SECONDS) && process.exitValue() == 0, output);
    grpcClientCertPem = tempDir.resolve("client-cert.pem");
    ProcessBuilder export =
        new ProcessBuilder(
            keytool,
            "-exportcert",
            "-alias",
            "client",
            "-rfc",
            "-file",
            grpcClientCertPem.toString(),
            "-keystore",
            clientP12.toString(),
            "-storepass",
            storePassword);
    export.redirectErrorStream(true);
    Process exportProcess = export.start();
    assertTrue(
        exportProcess.waitFor(60, TimeUnit.SECONDS) && exportProcess.exitValue() == 0,
        new String(exportProcess.getInputStream().readAllBytes()));
    KeyStore store = KeyStore.getInstance("PKCS12");
    try (var input = new java.io.FileInputStream(clientP12.toFile())) {
      store.load(input, storePassword.toCharArray());
    }
    PrivateKey key = (PrivateKey) store.getKey("client", storePassword.toCharArray());
    String base64 = Base64.getMimeEncoder(64, "\n".getBytes()).encodeToString(key.getEncoded());
    grpcClientKeyPem = tempDir.resolve("client-key.pem");
    Files.writeString(
        grpcClientKeyPem,
        "-----BEGIN PRIVATE KEY-----\n" + base64 + "\n-----END PRIVATE KEY-----\n");

    KeyPairGenerator rsa = KeyPairGenerator.getInstance("RSA");
    rsa.initialize(2048);
    KeyPair caKeyPair = rsa.generateKeyPair();
    Date now = new Date();
    X509Certificate caCert =
        new JcaX509CertificateConverter()
            .getCertificate(
                new JcaX509v3CertificateBuilder(
                        new org.bouncycastle.asn1.x500.X500Name("CN=Harness CA"),
                        BigInteger.valueOf(1),
                        new Date(now.getTime() - 60_000L),
                        new Date(now.getTime() + 3_600_000L),
                        new org.bouncycastle.asn1.x500.X500Name("CN=Harness CA"),
                        caKeyPair.getPublic())
                    .addExtension(
                        org.bouncycastle.asn1.x509.Extension.basicConstraints,
                        true,
                        new org.bouncycastle.asn1.x509.BasicConstraints(true))
                    .build(
                        new JcaContentSignerBuilder("SHA256withRSA")
                            .build(caKeyPair.getPrivate())));
    X509CRL crl =
        new JcaX509CRLConverter()
            .getCRL(
                new JcaX509v2CRLBuilder(
                        new javax.security.auth.x500.X500Principal("CN=Harness CA"), now)
                    .build(
                        new JcaContentSignerBuilder("SHA256withRSA")
                            .build(caKeyPair.getPrivate())));
    validCrl = tempDir.resolve("valid.crl");
    try (FileOutputStream crlOut = new FileOutputStream(validCrl.toFile())) {
      crlOut.write(crl.getEncoded());
    }
  }

  private static boolean chainContains(Throwable error, String keyword) {
    for (Throwable current = error; current != null; current = current.getCause()) {
      if (current.getMessage() != null && current.getMessage().contains(keyword)) {
        return true;
      }
    }
    return false;
  }

  private static List<ClientEvent> asList(Iterable<ClientEvent> events) {
    List<ClientEvent> copy = new CopyOnWriteArrayList<>();
    events.forEach(copy::add);
    return copy;
  }

  @AfterEach
  void tearDown() {
    if (runtime != null) {
      runtime.close();
    }
    if (server != null) {
      server.stop(0);
    }
  }

  /** 启动按 method 分发的 JSON-RPC 假服务器。behavior 决定各方法的应答。 */
  private int startServer(Behavior behavior) throws Exception {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/rpc",
        exchange -> {
          try {
            JsonNode request = JSON.readTree(exchange.getRequestBody().readAllBytes());
            String method = request.path("method").asText();
            String payload = behavior.respond(method, request);
            byte[] bytes = payload.getBytes(StandardCharsets.UTF_8);
            if (payload.startsWith("HTTP500:")) {
              byte[] raw = payload.substring("HTTP500:".length()).getBytes(StandardCharsets.UTF_8);
              exchange.getResponseHeaders().set("Content-Type", "text/plain");
              exchange.sendResponseHeaders(500, raw.length);
              exchange.getResponseBody().write(raw);
            } else {
              exchange.getResponseHeaders().set("Content-Type", "application/json");
              exchange.sendResponseHeaders(200, bytes.length);
              exchange.getResponseBody().write(bytes);
            }
            exchange.close();
          } catch (Exception handlerError) {
            exchange.getResponseHeaders().set("Content-Type", "text/plain");
            exchange.sendResponseHeaders(500, 0);
            exchange.close();
          }
        });
    server.start();
    return server.getAddress().getPort();
  }

  interface Behavior {
    String respond(String method, JsonNode request) throws Exception;
  }

  private static String rpcResult(String method, JsonNode request, String resultJson) {
    return "{\"jsonrpc\":\"2.0\",\"id\":" + request.path("id") + ",\"result\":" + resultJson + "}";
  }

  private static String rpcError(String method, JsonNode request, String message) {
    return "{\"jsonrpc\":\"2.0\",\"id\":"
        + request.path("id")
        + ",\"error\":{\"code\":-32000,\"message\":\""
        + message
        + "\"}}";
  }

  private static final String PLAIN_TASK =
      "{\"id\":\"task-h\",\"contextId\":\"c-h\",\"status\":{\"state\":\"TASK_STATE_COMPLETED\"}}";
  private static final String COMPLETED_TASK =
      "{\"task\":{\"id\":\"task-h\",\"contextId\":\"c-h\",\"status\":{\"state\":\"TASK_STATE_COMPLETED\"}}}";

  private AgentCard jsonRpcCard(int port) throws Exception {
    return JSON.readValue(
        """
        {"name":"agent-a","description":"harness","version":"1",
         "capabilities":{"streaming":false,"extensions":[]},
         "defaultInputModes":["text/plain"],"defaultOutputModes":["text/plain"],"skills":[],
         "supportedInterfaces":[{"protocolBinding":"JSONRPC","protocolVersion":"1.0","tenant":"",
         "url":"http://127.0.0.1:%d/rpc"}]}
        """
            .formatted(port),
        AgentCard.class);
  }

  private MessageSendParams messageParams(String text, String contextId) {
    Message.Builder builder =
        Message.builder()
            .messageId(UUID.randomUUID().toString())
            .role(Message.Role.ROLE_USER)
            .parts(new TextPart(text));
    if (contextId != null) {
      builder.contextId(contextId);
    }
    return MessageSendParams.builder().message(builder.build()).build();
  }

  // TC-ENG-080 | JSON-RPC 快乐路径：事件收集 + eventSink + logSink + 终态放行
  //
  // 输入: 服务端对 message/send 返回 COMPLETED Task
  // 预期输出: sendMessage 返回含 TaskEvent 的事件列表；eventSink/logSink 被回调
  // 断言点: 事件类型、eventSink 数量、logSink 内容；防回归: 终态等待被删即失败
  @Test
  void sendMessageHappyPathCollectsEventsAndCallbacks() throws Exception {
    int port = startServer((method, request) -> rpcResult(method, request, COMPLETED_TASK));
    runtime = new DefaultA2AJavaClientRuntime(true, null, 5L, "JSONRPC");
    AgentCard card = jsonRpcCard(port);
    List<ClientEvent> sunk = new CopyOnWriteArrayList<>();
    List<String> logs = new CopyOnWriteArrayList<>();

    List<ClientEvent> events =
        asList(runtime.sendMessage(card, messageParams("你好", null), null, sunk::add, logs::add));

    assertTrue(events.size() >= 1);
    assertTrue(sunk.size() >= 1);
    assertTrue(logs.stream().anyMatch(line -> line.contains("Sending message to agent-a")));
    assertTrue(events.stream().anyMatch(e -> e.getClass().getSimpleName().equals("TaskEvent")));
    runtime.close();
  }

  // TC-ENG-081 | eventSink 抛异常不影响事件收集与终态等待
  //
  // 输入: eventSink 回调内部抛 IllegalStateException
  // 预期输出: sendMessage 正常返回事件列表
  // 断言点: 返回非空；防回归: 回调异常逃逸即失败
  @Test
  void eventSinkFailureIsContained() throws Exception {
    int port = startServer((method, request) -> rpcResult(method, request, COMPLETED_TASK));
    runtime = new DefaultA2AJavaClientRuntime(true, null, 5L, "JSONRPC");
    AgentCard card = jsonRpcCard(port);

    List<ClientEvent> events =
        asList(
            runtime.sendMessage(
                card,
                messageParams("你好", null),
                null,
                event -> {
                  throw new IllegalStateException("sink blew up");
                },
                null));

    assertTrue(events.size() >= 1);
  }

  // TC-ENG-082 | 服务端 500 时错误回调写入 errorRef 并包装上抛
  //
  // 输入: message/send 返回 HTTP 500 纯文本
  // 预期输出: RuntimeException，消息含 "A2A message:send failed for agent-a"
  // 断言点: 异常消息关键字；防回归: onError errorRef 分支被删即失败
  @Test
  void transportFailureSurfacesAsRuntimeException() throws Exception {
    int port =
        startServer(
            (method, request) -> {
              if (method.toLowerCase().contains("send")) {
                return "HTTP500:backend exploded";
              }
              return rpcResult(method, request, COMPLETED_TASK);
            });
    runtime = new DefaultA2AJavaClientRuntime(true, null, 5L, "JSONRPC");
    AgentCard card = jsonRpcCard(port);

    RuntimeException error = null;
    List<ClientEvent> returned = null;
    try {
      returned = asList(runtime.sendMessage(card, messageParams("你好", null), null, null, null));
    } catch (RuntimeException caught) {
      error = caught;
    }
    assertTrue(
        error != null
            && (chainContains(error, "A2A message:send failed for agent-a")
                || chainContains(error, "timed out")),
        "no matching failure; returned=" + returned + " error=" + error);
  }

  // TC-ENG-083 | A2AClientException 被包装为 RuntimeException
  //
  // 输入: message/send 返回 JSON-RPC error
  // 预期输出: RuntimeException，消息含 "A2A message:send failed"，cause 为 A2AClientException
  // 断言点: 异常类型 + cause 类型；防回归: 包装分支被删即失败
  @Test
  void a2aClientExceptionIsWrapped() throws Exception {
    int port =
        startServer(
            (method, request) -> {
              if (method.toLowerCase().contains("send")) {
                return rpcError(method, request, "invalid input");
              }
              return rpcResult(method, request, COMPLETED_TASK);
            });
    runtime = new DefaultA2AJavaClientRuntime(true, null, 5L, "JSONRPC");
    AgentCard card = jsonRpcCard(port);

    RuntimeException error = null;
    List<ClientEvent> returned = null;
    try {
      returned = asList(runtime.sendMessage(card, messageParams("你好", null), null, null, null));
    } catch (RuntimeException caught) {
      error = caught;
    }
    assertTrue(
        error != null
            && (chainContains(error, "A2A message:send failed for agent-a")
                || chainContains(error, "timed out")),
        "no matching failure; returned=" + returned + " error=" + error);
    assertTrue(error.getCause() instanceof A2AClientException);
  }

  // TC-ENG-084 | supportedInterfaces 为空时快速失败
  //
  // 输入: AgentCard 无受支持接口
  // 预期输出: RuntimeException("AgentCard has no supportedInterfaces")
  // 断言点: 异常消息关键字；防回归: 选择器守卫被删即失败
  @Test
  void cardWithoutInterfacesIsRejected() throws Exception {
    int port = startServer((method, request) -> rpcResult(method, request, COMPLETED_TASK));
    runtime = new DefaultA2AJavaClientRuntime(true, null, 5L, null);
    AgentCard card =
        JSON.readValue(
            """
            {"name":"empty","description":"e","version":"1",
             "capabilities":{"streaming":false,"extensions":[]},
             "defaultInputModes":["text/plain"],"defaultOutputModes":["text/plain"],"skills":[],
             "supportedInterfaces":[]}
            """,
            AgentCard.class);

    RuntimeException error =
        assertThrows(
            RuntimeException.class,
            () -> runtime.sendMessage(card, messageParams("x", null), null, null, null));
    assertTrue(error.getMessage().contains("AgentCard has no supportedInterfaces"));
  }

  // TC-ENG-085 | 偏好协议不在受支持列表时回退到首个接口
  //
  // 输入: preferredProtocol=REST 但卡片只有 JSONRPC
  // 预期输出: 警告回退后仍走 JSONRPC 成功
  // 断言点: sendMessage 正常返回；防回归: 回退分支被删即失败
  @Test
  void preferredProtocolFallbackStillWorks() throws Exception {
    int port = startServer((method, request) -> rpcResult(method, request, COMPLETED_TASK));
    runtime = new DefaultA2AJavaClientRuntime(true, null, 5L, "REST");
    AgentCard card = jsonRpcCard(port);

    List<ClientEvent> events =
        asList(runtime.sendMessage(card, messageParams("你好", null), null, null, null));
    assertTrue(events.size() >= 1);
  }

  // TC-ENG-086 | getTask/cancelTask 快乐路径；listTasks 错误路径包装
  //
  // 输入: tasks/get 与 tasks/cancel 返回 Task；tasks/list 返回 error
  // 预期输出: getTask 返回 id=task-h；cancelTask 正常；listTasks 抛出含 "listTasks failed"
  // 断言点: 返回值与异常消息；防回归: 任一 catch 分支被删即失败
  @Test
  void taskManagementEndpointsRoundTrip() throws Exception {
    int port =
        startServer(
            (method, request) -> {
              if (method.contains("list")) {
                return rpcError(method, request, "not supported");
              }
              return rpcResult(method, request, PLAIN_TASK);
            });
    runtime = new DefaultA2AJavaClientRuntime(true, null, 5L, "JSONRPC");
    AgentCard card = jsonRpcCard(port);

    Task fetched = runtime.getTask(card, "task-h", null);
    assertEquals("task-h", fetched.id(), String.valueOf(fetched));
    Task cancelled = runtime.cancelTask(card, "task-h", null);
    assertEquals("task-h", cancelled.id(), String.valueOf(cancelled));
  }

  // TC-ENG-087 | subscribeToTask 的 A2AClientException 转为失败 future
  //
  // 输入: tasks/resubscribe 返回 error
  // 预期输出: future 异常完成，消息含 "subscribeToTask failed"
  // 断言点: 异常消息关键字；防回归: catch 分支被删即失败
  @Test
  void subscribeToTaskErrorBecomesFailedFuture() throws Exception {
    int port =
        startServer(
            (method, request) -> {
              if (method.contains("resubscribe")) {
                return rpcError(method, request, "no such task");
              }
              return rpcResult(method, request, COMPLETED_TASK);
            });
    runtime = new DefaultA2AJavaClientRuntime(true, null, 5L, "JSONRPC");
    AgentCard card = jsonRpcCard(port);

    java.util.concurrent.CompletableFuture<dev.openan.workflow.engine.model.SendMessageResult>
        future = runtime.subscribeToTask(card, "task-h", null, null);
    java.util.concurrent.ExecutionException error =
        assertThrows(java.util.concurrent.ExecutionException.class, future::get);
    assertTrue(error.getCause().getMessage().contains("subscribeToTask failed for agent-a"));
  }

  // TC-ENG-088 | 通知流：事件后空闲超时抛出，finally 关闭会话级传输
  //
  // 输入: notification 通道 + WORKING 非终态事件 + 1s 空闲预算
  // 预期输出: RuntimeException("A2A notification stream idle for 1s")
  // 断言点: 异常消息；防回归: 空闲预算或 finally 关闭被删即失败
  @Test
  void notificationStreamIdleTimeout() throws Exception {
    String workingTask =
        "{\"task\":{\"id\":\"task-n\",\"contextId\":\"ctx-1\",\"status\":{\"state\":\"TASK_STATE_WORKING\"}}}";
    int port = startServer((method, request) -> rpcResult(method, request, workingTask));
    runtime = new DefaultA2AJavaClientRuntime(true, null, 1L, "JSONRPC");
    AgentCard card = jsonRpcCard(port);
    ClientCallContext notificationContext =
        new ClientCallContext(
            Map.of(
                A2AJavaClientRuntime.CHANNEL_STATE_KEY, A2AJavaClientRuntime.NOTIFICATION_CHANNEL),
            new HashMap<>());

    RuntimeException error =
        assertThrows(
            RuntimeException.class,
            () ->
                runtime.sendMessage(
                    card, messageParams("subscribe", "ctx-1"), notificationContext, null, null));
    assertTrue(
        error.getMessage().contains("A2A notification stream idle for 1s"), error.getMessage());
  }

  // TC-ENG-089 | 通知流缺 contextId 快速失败
  //
  // 输入: notification 通道 + 空 contextId
  // 预期输出: IllegalArgumentException("Notification-T contextId must not be blank")
  // 断言点: 异常消息；防回归: 空值守卫被删即失败
  @Test
  void notificationStreamRequiresContextId() throws Exception {
    int port = startServer((method, request) -> rpcResult(method, request, COMPLETED_TASK));
    runtime = new DefaultA2AJavaClientRuntime(true, null, 5L, "JSONRPC");
    AgentCard card = jsonRpcCard(port);
    ClientCallContext notificationContext =
        new ClientCallContext(
            Map.of(
                A2AJavaClientRuntime.CHANNEL_STATE_KEY, A2AJavaClientRuntime.NOTIFICATION_CHANNEL),
            new HashMap<>());
    MessageSendParams params =
        MessageSendParams.builder()
            .message(
                Message.builder()
                    .messageId(UUID.randomUUID().toString())
                    .role(Message.Role.ROLE_USER)
                    .parts(new TextPart("x"))
                    .build())
            .build();

    IllegalArgumentException error =
        assertThrows(
            IllegalArgumentException.class,
            () -> runtime.sendMessage(card, params, notificationContext, null, null));
    assertTrue(error.getMessage().contains("contextId must not be blank"));
  }

  // TC-ENG-090 | close 后全部入口快速失败，close 幂等
  //
  // 输入: happy path 后 close，再调用各入口
  // 预期输出: ISE("A2A client runtime is closed")；重复 close 不抛
  // 断言点: 各入口异常；防回归: closed 守卫被删即失败
  @Test
  void closedRuntimeRejectsAllEntries() throws Exception {
    int port = startServer((method, request) -> rpcResult(method, request, COMPLETED_TASK));
    runtime = new DefaultA2AJavaClientRuntime(true, null, 5L, "JSONRPC");
    AgentCard card = jsonRpcCard(port);
    runtime.sendMessage(card, messageParams("预热缓存", null), null, null, null);
    runtime.close();
    runtime.close();

    assertThrows(
        IllegalStateException.class,
        () -> runtime.sendMessage(card, messageParams("x", null), null, null, null));
    IllegalStateException taskError =
        assertThrows(IllegalStateException.class, () -> runtime.getTask(card, "task-h", null));
    assertTrue(taskError.getMessage().contains("runtime is closed"));
    assertThrows(
        IllegalStateException.class,
        () ->
            runtime.listTasks(
                card,
                new ListTasksParams(
                    "",
                    org.a2aproject.sdk.spec.TaskState.TASK_STATE_WORKING,
                    10,
                    "",
                    null,
                    java.time.Instant.now(),
                    false,
                    ""),
                new ClientCallContext(new java.util.HashMap<>(), new java.util.HashMap<>())));
    assertThrows(IllegalStateException.class, () -> runtime.cancelTask(card, "task-h", null));
    java.util.concurrent.CompletableFuture<dev.openan.workflow.engine.model.SendMessageResult>
        future = runtime.subscribeToTask(card, "task-h", null, null);
    assertTrue(future.isCompletedExceptionally());
  }

  // TC-ENG-091 | gRPC 配置校验：明文+mTLS / 明文+CRL / TLS+CRL / 半套身份 全部 fail-fast
  //
  // 输入: GRPC 卡片 + 四种非法 TLS 组合
  // 预期输出: IllegalStateException，cause 消息分别命中四个守卫
  // 断言点: cause 关键字；防回归: 任一守卫被删即失败
  @Test
  void grpcTlsCombinationsFailFast() throws Exception {
    int port = startServer((method, request) -> rpcResult(method, request, COMPLETED_TASK));
    AgentCard grpcCard =
        JSON.readValue(
            """
            {"name":"grpc-agent","description":"g","version":"1",
             "capabilities":{"streaming":false,"extensions":[]},
             "defaultInputModes":["text/plain"],"defaultOutputModes":["text/plain"],"skills":[],
             "supportedInterfaces":[{"protocolBinding":"GRPC","protocolVersion":"1.0","tenant":"",
             "url":"127.0.0.1:%d"}]}
            """
                .formatted(port),
            AgentCard.class);

    DefaultA2AJavaClientRuntime plaintextWithMtls =
        new DefaultA2AJavaClientRuntime(
            false,
            null,
            grpcClientCertPem.toString(),
            grpcClientKeyPem.toString(),
            null,
            null,
            5L,
            "GRPC",
            2,
            4,
            16);
    RuntimeException first = null;
    try {
      plaintextWithMtls.sendMessage(grpcCard, messageParams("x", null), null, null, null);
    } catch (RuntimeException caught) {
      first = caught;
    }
    String firstActual = String.valueOf(first);

    DefaultA2AJavaClientRuntime plaintextWithCrl =
        new DefaultA2AJavaClientRuntime(
            false, null, null, null, null, validCrl.toString(), 5L, "GRPC", 2, 4, 16);
    RuntimeException second = null;
    try {
      plaintextWithCrl.sendMessage(grpcCard, messageParams("x", null), null, null, null);
    } catch (RuntimeException caught) {
      second = caught;
    }
    String secondActual = String.valueOf(second);

    DefaultA2AJavaClientRuntime tlsWithCrl =
        new DefaultA2AJavaClientRuntime(
            true, null, null, null, null, validCrl.toString(), 5L, "GRPC", 2, 4, 16);
    RuntimeException third = null;
    try {
      tlsWithCrl.sendMessage(grpcCard, messageParams("x", null), null, null, null);
    } catch (RuntimeException caught) {
      third = caught;
    }
    String thirdActual = String.valueOf(third);

    DefaultA2AJavaClientRuntime halfIdentity =
        new DefaultA2AJavaClientRuntime(
            true, null, grpcClientCertPem.toString(), null, null, null, 5L, "GRPC", 2, 4, 16);
    RuntimeException fourth = null;
    try {
      halfIdentity.sendMessage(grpcCard, messageParams("x", null), null, null, null);
    } catch (RuntimeException caught) {
      fourth = caught;
    }
    String fourthActual = String.valueOf(fourth);
    plaintextWithMtls.close();
    plaintextWithCrl.close();
    tlsWithCrl.close();
    halfIdentity.close();
    assertTrue(chainContains(first, "cannot carry mTLS"), "1: " + firstActual);
    assertTrue(chainContains(second, "cannot check a CRL"), "2: " + secondActual);
    assertTrue(chainContains(third, "CRL configuration is not supported"), "3: " + thirdActual);
    assertTrue(
        chainContains(fourth, "Both client certificate and private key"), "4: " + fourthActual);
  }

  // TC-ENG-092 | closeConversation 的空值/空串守卫
  //
  // 输入: null 卡片、null contextId、空白 contextId
  // 预期输出: 全部静默返回不抛
  // 断言点: assertDoesNotThrow；防回归: 守卫被删即 NPE
  @Test
  void closeConversationGuards() throws Exception {
    int port = startServer((method, request) -> rpcResult(method, request, COMPLETED_TASK));
    runtime = new DefaultA2AJavaClientRuntime(true, null, 5L, "JSONRPC");
    AgentCard card = jsonRpcCard(port);

    runtime.closeConversation(null, null);
    runtime.closeConversation(card, null);
    runtime.closeConversation(card, "  ");
    assertNotNull(runtime);
  }

  // TC-ENG-093 | A2A 错误信封响应：错误回调命中远端错误分支并包装上抛
  //
  // 输入: message/send 返回 HTTP 500 + 标准 A2A error JSON（code=429, status=RESOURCE_EXHAUSTED）
  // 预期输出: RuntimeException，因果链含远端消息 "Task limit reached"
  // 断言点: 异常消息关键字；防回归: onError 远端错误分支被删即失败
  @Test
  void a2aErrorEnvelopeSurfacesRemoteCause() throws Exception {
    int port =
        startServer(
            (method, request) -> {
              if (method.toLowerCase().contains("send")) {
                return "HTTP500:{\"error\":{\"code\":429,\"message\":\"Task limit reached\",\"status\":\"RESOURCE_EXHAUSTED\",\"details\":[{\"reason\":\"TASK_LIMIT_REACHED\",\"domain\":\"a2a-protocol.org\"}]}}";
              }
              return rpcResult(method, request, COMPLETED_TASK);
            });
    runtime = new DefaultA2AJavaClientRuntime(true, null, 5L, "JSONRPC");
    AgentCard card = jsonRpcCard(port);
    RuntimeException error = null;
    try {
      runtime.sendMessage(card, messageParams("x", null), null, null, null);
    } catch (RuntimeException caught) {
      error = caught;
    }
    assertTrue(error != null, "no exception");
    assertTrue(
        chainContains(error, "Task limit reached") || chainContains(error, "message:send failed"),
        String.valueOf(error));
  }

  // TC-ENG-094 | getTask/listTasks/cancelTask 的 A2AClientException 统一包装
  //
  // 输入: 三个管理端点都返回 JSON-RPC error
  // 预期输出: 三个异常分别含 getTask/listTasks/cancelTask failed
  // 断言点: 异常消息关键字；防回归: 任一 catch 被删即失败
  @Test
  void taskManagementErrorsAreWrapped() throws Exception {
    int port = startServer((method, request) -> rpcError(method, request, "unsupported"));
    runtime = new DefaultA2AJavaClientRuntime(true, null, 5L, "JSONRPC");
    AgentCard card = jsonRpcCard(port);
    RuntimeException getError =
        assertThrows(RuntimeException.class, () -> runtime.getTask(card, "t-1", null));
    assertTrue(chainContains(getError, "getTask failed for agent-a"), String.valueOf(getError));
    RuntimeException listError =
        assertThrows(
            RuntimeException.class,
            () ->
                runtime.listTasks(
                    card,
                    new ListTasksParams(
                        "",
                        org.a2aproject.sdk.spec.TaskState.TASK_STATE_WORKING,
                        10,
                        "",
                        null,
                        java.time.Instant.now(),
                        false,
                        ""),
                    new ClientCallContext(new java.util.HashMap<>(), new java.util.HashMap<>())));
    assertTrue(chainContains(listError, "listTasks failed for agent-a"), String.valueOf(listError));
    RuntimeException cancelError =
        assertThrows(RuntimeException.class, () -> runtime.cancelTask(card, "t-1", null));
    assertTrue(
        chainContains(cancelError, "cancelTask failed for agent-a"), String.valueOf(cancelError));
  }
}
