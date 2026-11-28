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

package dev.openan.workflow.engine.examples.workbench;

import static org.junit.jupiter.api.Assertions.*;

import com.sun.net.httpserver.HttpServer;
import dev.openan.workflow.engine.registry.HttpAuthProvider;
import dev.openan.workflow.engine.registry.HttpResponseException;
import dev.openan.workflow.engine.registry.HttpServiceException;
import dev.openan.workflow.engine.registry.TlsPolicy;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class WorkbenchCenterAccessTest {
  @ParameterizedTest
  @ValueSource(ints = {401, 403, 500})
  void centerErrorsNeverSelectAnAlternateWorkflow(int status) throws Exception {
    var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/",
        exchange -> {
          exchange.getRequestBody().readAllBytes();
          byte[] body = "rejected".getBytes(StandardCharsets.UTF_8);
          exchange.sendResponseHeaders(status, body.length);
          try (var output = exchange.getResponseBody()) {
            output.write(body);
          } finally {
            exchange.close();
          }
        });
    server.start();
    try {
      var orchestrator = orchestrator("http://127.0.0.1:" + server.getAddress().getPort(), false);
      assertEquals(
          status,
          assertThrows(HttpServiceException.class, () -> orchestrator.resolveWorkflow("intent"))
              .getStatusCode());
    } finally {
      server.stop(0);
    }
  }

  @Test
  void searchAndLoadBothReceiveCenterCredentialsAndLoadRejectionIsNotHidden() throws Exception {
    var received = new AtomicInteger();
    var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/",
        exchange -> {
          exchange.getRequestBody().readAllBytes();
          if ("Bearer center.token"
              .equals(exchange.getRequestHeaders().getFirst("Authorization"))) {
            received.incrementAndGet();
          }
          boolean search = exchange.getRequestURI().getPath().endsWith("/search");
          byte[] body =
              (search ? "{\"data\":[{\"workflow_id\":\"flow\"}]}" : "denied")
                  .getBytes(StandardCharsets.UTF_8);
          exchange.sendResponseHeaders(search ? 200 : 403, body.length);
          try (var output = exchange.getResponseBody()) {
            output.write(body);
          } finally {
            exchange.close();
          }
        });
    server.start();
    try {
      var orchestrator = orchestrator("http://127.0.0.1:" + server.getAddress().getPort(), false);
      assertEquals(
          403,
          assertThrows(HttpServiceException.class, () -> orchestrator.resolveWorkflow("intent"))
              .getStatusCode());
      assertEquals(2, received.get());
    } finally {
      server.stop(0);
    }
  }

  @Test
  void explicitLocalDemoDoesNotContactTheCenterOrEvaluateItsCredentials() throws Exception {
    HttpAuthProvider forbidden =
        uri -> {
          throw new AssertionError("must not acquire center credentials");
        };
    var orchestrator =
        new WorkbenchOrchestrator(
            "http://127.0.0.1:1",
            null,
            false,
            null,
            null,
            null,
            new WorkbenchOrchestrator.CenterAccess(forbidden, TlsPolicy.strict(), true));
    assertEquals(3, orchestrator.resolveWorkflow("intent").workflow().getSteps().size());
    assertThrows(
        Exception.class, () -> orchestrator("http://127.0.0.1:1", false).resolveWorkflow("intent"));
  }

  private static final String HOSTLESS =
      "{\"id\":\"hostless\",\"name\":\"Hostless\",\"steps\":[]}";
  private static final String OTHER_HOST =
      "{\"id\":\"other\",\"name\":\"Other\",\"workflow_host\":\"Other Host\",\"steps\":[]}";
  private static final String LEGACY =
      """
      {"id":"legacy","name":"Legacy","steps":[{"name":"merge","step_type":"SelfLoop",
       "subtasks":[{"agent":"Host Agent","description":"summarise"}]}]}
      """;

  @Test
  void aPsopDeclaredForAnotherHostIsSkippedForTheNextCandidate() throws Exception {
    var server = catalog(List.of("other", "hostless"), Map.of("other", OTHER_HOST, "hostless", HOSTLESS));
    try {
      assertEquals("hostless", orchestrator(server, Set.of()).resolveWorkflow("intent").psopId());
    } finally {
      server.stop(0);
    }
  }

  @Test
  void noCandidateExecutedByThisHostFailsNamingEachDeclaredHost() throws Exception {
    var server = catalog(List.of("other"), Map.of("other", OTHER_HOST));
    try {
      var failure =
          assertThrows(
              IllegalStateException.class,
              () -> orchestrator(server, Set.of()).resolveWorkflow("intent"));
      assertTrue(failure.getMessage().contains("other"), failure.getMessage());
      assertTrue(failure.getMessage().contains("Other Host"), failure.getMessage());
    } finally {
      server.stop(0);
    }
  }

  @Test
  void aLegacySelfLoopHostIsHonouredOnlyWhenThisHostServesIt() throws Exception {
    var server = catalog(List.of("legacy"), Map.of("legacy", LEGACY));
    try {
      var failure =
          assertThrows(
              IllegalStateException.class,
              () -> orchestrator(server, Set.of("Transport Workbench Agent")).resolveWorkflow("intent"));
      assertTrue(failure.getMessage().contains("Host Agent"), failure.getMessage());
      assertEquals(
          "legacy", orchestrator(server, Set.of("Host Agent")).resolveWorkflow("intent").psopId());
    } finally {
      server.stop(0);
    }
  }

  /** A center that ranks {@code ranked} and answers each load with the definition given for it. */
  private static HttpServer catalog(List<String> ranked, Map<String, String> definitions)
      throws Exception {
    var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/",
        exchange -> {
          exchange.getRequestBody().readAllBytes();
          String path = exchange.getRequestURI().getPath();
          String body =
              path.endsWith("/search")
                  ? "{\"data\":["
                      + ranked.stream()
                          .map(id -> "{\"workflow_id\":\"" + id + "\",\"score\":1.0}")
                          .collect(Collectors.joining(","))
                      + "]}"
                  : "{\"status\":\"success\",\"data\":"
                      + definitions.get(path.substring(path.lastIndexOf('/') + 1))
                      + "}";
          byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
          exchange.sendResponseHeaders(200, bytes.length);
          try (var output = exchange.getResponseBody()) {
            output.write(bytes);
          } finally {
            exchange.close();
          }
        });
    server.start();
    return server;
  }

  private static WorkbenchOrchestrator orchestrator(HttpServer server, Set<String> servedHosts) {
    return new WorkbenchOrchestrator(
        "http://127.0.0.1:" + server.getAddress().getPort(),
        null,
        false,
        null,
        null,
        null,
        new WorkbenchOrchestrator.CenterAccess(
            HttpAuthProvider.bearer("center.token"), TlsPolicy.strict(), false, servedHosts));
  }

  private static WorkbenchOrchestrator orchestrator(String url, boolean local) {
    return new WorkbenchOrchestrator(
        url,
        null,
        false,
        null,
        null,
        null,
        new WorkbenchOrchestrator.CenterAccess(
            HttpAuthProvider.bearer("center.token"), TlsPolicy.strict(), local));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "rejected",
        "empty",
        "malformed",
        "cancelled",
        "constructor",
        "completed",
        "workflow-failed",
        "close-failed"
      })
  void suppliedRuntimeIsClosedExactlyOnceOnEveryExecutionExit(String scenario) throws Exception {
    dev.openan.workflow.engine.examples.testsupport.OfflineA2ATLlmClient.install();
    var closes = new AtomicInteger();
    var sends = new AtomicInteger();
    var cancellation = new ExecutionCancellation();
    if (scenario.equals("cancelled")) cancellation.cancel();
    var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/",
        exchange -> {
          exchange.getRequestBody().readAllBytes();
          boolean search = exchange.getRequestURI().getPath().endsWith("/search");
          int status = scenario.equals("rejected") || scenario.equals("close-failed") ? 401 : 200;
          String body =
              scenario.equals("malformed")
                  ? "InvalidSyntheticBody"
                  : search
                      ? scenario.equals("empty")
                          ? "{\"data\":[]}"
                          : "{\"data\":[{\"workflow_id\":\"flow\"}]}"
                      : scenario.equals("workflow-failed")
                          ? "{\"data\":{\"name\":\"flow\",\"steps\":[{\"name\":\"invalid\",\"next\":[{\"step\":\"missing\"}]}]}}"
                          : "{\"data\":{\"name\":\"flow\",\"steps\":[]}}";
          byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
          exchange.sendResponseHeaders(status, bytes.length);
          try (var output = exchange.getResponseBody()) {
            output.write(bytes);
          } finally {
            exchange.close();
          }
        });
    server.start();
    dev.openan.workflow.engine.client.A2AJavaClientRuntime runtime =
        new dev.openan.workflow.engine.client.A2AJavaClientRuntime() {
          public Iterable<org.a2aproject.sdk.client.ClientEvent> sendMessage(
              org.a2aproject.sdk.spec.AgentCard card,
              org.a2aproject.sdk.spec.MessageSendParams params,
              org.a2aproject.sdk.client.transport.spi.interceptors.ClientCallContext context,
              java.util.function.Consumer<org.a2aproject.sdk.client.ClientEvent> sink,
              java.util.function.Consumer<String> logs) {
            sends.incrementAndGet();
            return java.util.List.of();
          }

          public void close() {
            closes.incrementAndGet();
            if (scenario.equals("close-failed"))
              throw new IllegalStateException("SyntheticCloseFailure");
          }
        };
    try {
      // A non-existent credentials path makes transport construction fail deterministically.
      String credentials =
          scenario.equals("constructor")
              ? "classpath:nonexistent-review-credentials-" + java.util.UUID.randomUUID() + ".json"
              : null;
      var orchestrator =
          new WorkbenchOrchestrator(
              "http://127.0.0.1:" + server.getAddress().getPort(),
              credentials,
              false,
              dev.openan.workflow.engine.examples.testsupport.OfflineA2ATLlmClient.envPath(),
              runtime,
              null,
              new WorkbenchOrchestrator.CenterAccess(
                  HttpAuthProvider.bearer("center.token"), TlsPolicy.strict(), false));
      switch (scenario) {
        case "completed" -> assertNotNull(orchestrator.run("intent", false, cancellation));
        case "rejected", "close-failed" ->
            assertEquals(
                401,
                assertThrows(
                        HttpServiceException.class,
                        () -> orchestrator.run("intent", false, cancellation))
                    .getStatusCode());
        case "malformed" ->
            assertThrows(
                HttpResponseException.class, () -> orchestrator.run("intent", false, cancellation));
        case "cancelled" ->
            assertThrows(
                java.util.concurrent.CancellationException.class,
                () -> orchestrator.run("intent", false, cancellation));
        case "empty" ->
            assertTrue(
                assertThrows(
                        IllegalStateException.class,
                        () -> orchestrator.run("intent", false, cancellation))
                    .getMessage()
                    .contains("no matching workflow"));
        case "constructor" ->
            assertTrue(
                assertThrows(
                        IllegalStateException.class,
                        () -> orchestrator.run("intent", false, cancellation))
                    .getMessage()
                    .contains("Failed to load credentials"));
        case "workflow-failed" ->
            assertTrue(
                assertThrows(
                        IllegalStateException.class,
                        () -> orchestrator.run("intent", false, cancellation))
                    .getMessage()
                    .contains("missing"));
        default -> fail("Unknown scenario");
      }
      assertEquals(1, closes.get());
      assertEquals(0, sends.get());
    } finally {
      server.stop(0);
    }
  }
}
