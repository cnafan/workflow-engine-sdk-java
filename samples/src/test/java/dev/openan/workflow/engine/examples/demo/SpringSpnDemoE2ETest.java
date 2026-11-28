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

package dev.openan.workflow.engine.examples.demo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.openan.workflow.engine.examples.testsupport.OfflineA2ATLlmClient;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/** Runs the actual northbound Spring demo with local OMCs and the offline SDK provider. */
class SpringSpnDemoE2ETest {
  private static final String SPOOL_PROPERTY =
      dev.openan.workflow.engine.registry.CenterExecutionReporting.SPOOL_VARIABLE;
  private dev.openan.workflow.engine.examples.testsupport.CapturedLogs captured;
  @org.junit.jupiter.api.io.TempDir java.nio.file.Path centerTestDirectory;
  private String previousCardLocations;
  private String previousSpool;
  private final int[] cityPorts = new int[2];
  private java.nio.file.Path demoCredentials;

  private static JsonNode centerData(HttpClient http, String url, String session) throws Exception {
    var response =
        http.send(
            HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(5))
                .header("Authorization", "Bearer " + session)
                .GET()
                .build(),
            HttpResponse.BodyHandlers.ofString());
    assertEquals(200, response.statusCode(), url);
    return new ObjectMapper().readTree(response.body()).path("data");
  }

  @org.junit.jupiter.api.BeforeEach
  void captureLogs() throws Exception {
    captured = new dev.openan.workflow.engine.examples.testsupport.CapturedLogs();
    previousCardLocations = System.getProperty("A2A_AGENT_CARD_LOCATIONS");
    // Keep existing developer OMCs running: use isolated loopback ports and public demo materials.
    try (var first = new java.net.ServerSocket(0); var second = new java.net.ServerSocket(0)) {
      cityPorts[0] = first.getLocalPort();
      cityPorts[1] = second.getLocalPort();
    }
    var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
    var locations = new java.util.ArrayList<String>();
    for (int city = 1; city <= 2; city++) {
      try (var input = getClass().getClassLoader().getResourceAsStream(
          "agentcard/spn_domain_agent_city" + city + ".json")) {
        var card = mapper.readTree(input);
        ((com.fasterxml.jackson.databind.node.ObjectNode) card.path("supportedInterfaces").get(0))
            .put("url", cityTarget(city) + "/a2a/json");
        java.nio.file.Path path = centerTestDirectory.resolve("city" + city + ".json");
        mapper.writeValue(path.toFile(), card);
        locations.add(path.toUri().toString());
      }
    }
    locations.add("classpath:agentcard/transport_workbench_agent.json");
    System.setProperty("A2A_AGENT_CARD_LOCATIONS", String.join(",", locations));
    // The reporting queue must not leak into the real home directory of whoever runs the tests.
    previousSpool = System.getProperty(SPOOL_PROPERTY);
    System.setProperty(SPOOL_PROPERTY, centerTestDirectory.resolve("execution-events").toString());
    String credentials;
    try (var input = getClass().getClassLoader().getResourceAsStream("spn_agent_credentials.example.json")) {
      credentials = new String(java.util.Objects.requireNonNull(input).readAllBytes(),
          java.nio.charset.StandardCharsets.UTF_8);
    }
    credentials = credentialsWithCityPorts(credentials);
    demoCredentials = centerTestDirectory.resolve("credentials.json");
    java.nio.file.Files.writeString(demoCredentials, credentials);
  }

  protected String cityTarget(int city) {
    return "https://127.0.0.1:" + cityPorts[city - 1];
  }

  protected String demoCredentialsPath() {
    return demoCredentials.toString();
  }

  private String credentialsWithCityPorts(String json) throws Exception {
    var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
    var root = (com.fasterxml.jackson.databind.node.ObjectNode) mapper.readTree(json);
    var fields = root.fields();
    while (fields.hasNext()) {
      var entry = fields.next();
      var bearer =
          (com.fasterxml.jackson.databind.node.ObjectNode) entry.getValue().path("bearerAuth");
      var login = java.net.URI.create(bearer.path("login_url").asText());
      int city = entry.getKey().endsWith("City2") ? 2 : 1;
      bearer.put(
          "login_url",
          login.getScheme() + "://127.0.0.1:" + cityPorts[city - 1] + login.getPath());
    }
    return mapper.writeValueAsString(root);
  }

  @Test
  @Timeout(120)
  void defaultLocalDemoNegotiatesOnlyCity1WithoutChangingGlobalSwitch() throws Exception {
    String enabled = System.getProperty("a2at.samples.negotiation");
    String city = System.getProperty("a2at.samples.negotiation.city");
    int offset = captured.length();
    System.clearProperty("a2at.samples.negotiation");
    System.clearProperty("a2at.samples.negotiation.city");
    try {
      runDemo();
      {
        String evidence = captured.since(offset);
        org.junit.jupiter.api.Assertions.assertTrue(
            evidence.contains("NEGOTIATION_DEMO enabled=true, city=city1"));
        org.junit.jupiter.api.Assertions.assertTrue(
            evidence.contains("[SpnDomainAgentCity1Executor] NEGOTIATION_APPLIED"));
        org.junit.jupiter.api.Assertions.assertFalse(
            evidence.contains("[SpnDomainAgentCity2Executor] NEGOTIATION_APPLIED"));
        org.junit.jupiter.api.Assertions.assertTrue(
            evidence.contains(
                "[SpnDomainAgentCity2Executor] Parameters sufficient, skipping negotiation"));
        assertNegotiationWireEvidence(evidence);
        for (String cityName : java.util.List.of("City1", "City2")) {
          org.junit.jupiter.api.Assertions.assertTrue(
              evidence.contains(
                  "[SpnDomainAgent" + cityName + "Executor] Authorization-T accepted"));
          org.junit.jupiter.api.Assertions.assertTrue(
              evidence.contains(
                  "[SpnDomainAgent" + cityName + "Executor] NOTIFICATION_SUBSCRIBED"));
        }
        org.junit.jupiter.api.Assertions.assertFalse(
            evidence.contains("OPERATION_FAILED extension="));
        org.junit.jupiter.api.Assertions.assertFalse(
            evidence.matches("(?s).*\\ndata: +\\\"performative\\\".*"));
      }
      org.junit.jupiter.api.Assertions.assertNull(System.getProperty("a2at.samples.negotiation"));
    } finally {
      if (enabled == null) System.clearProperty("a2at.samples.negotiation");
      else System.setProperty("a2at.samples.negotiation", enabled);
      if (city == null) System.clearProperty("a2at.samples.negotiation.city");
      else System.setProperty("a2at.samples.negotiation.city", city);
    }
  }

  @Test
  @Timeout(120)
  void bothCitiesNegotiateIndependentlyAndComplete() throws Exception {
    String previous = System.getProperty("a2at.samples.negotiation.city");
    int offset = captured.length();
    System.setProperty("a2at.samples.negotiation.city", "both");
    try {
      missingPortNegotiatesAndCompletesThroughTheSameSpringWorkflow();
      {
        String evidence = captured.since(offset);
        for (String city : java.util.List.of("City1", "City2")) {
          org.junit.jupiter.api.Assertions.assertTrue(
              evidence.contains("[SpnDomainAgent" + city + "Executor] NEGOTIATION_APPLIED"), city);
        }
        org.junit.jupiter.api.Assertions.assertTrue(
            evidence.contains("port=P781-珠江新城-PTN7900-23-TPA1EG24-17"));
        org.junit.jupiter.api.Assertions.assertTrue(
            evidence.contains("port=P882-珠江新城-PTN7900-23-TPA1EG24-11"));
      }
    } finally {
      if (previous == null) System.clearProperty("a2at.samples.negotiation.city");
      else System.setProperty("a2at.samples.negotiation.city", previous);
    }
  }

  @Test
  @Timeout(120)
  void missingPortNegotiatesAndCompletesThroughTheSameSpringWorkflow() throws Exception {
    int offset = captured.length();
    String previous = System.getProperty("a2at.samples.negotiation");
    System.setProperty("a2at.samples.negotiation", "true");
    try {
      runDemo();
      {
        String evidence = captured.since(offset);
        org.junit.jupiter.api.Assertions.assertTrue(evidence.contains("DEMO_NEGOTIATION"));
        org.junit.jupiter.api.Assertions.assertTrue(evidence.contains("TASK_STATE_INPUT_REQUIRED"));
        org.junit.jupiter.api.Assertions.assertTrue(
            evidence.matches("(?s).*\\\"performative\\\"\\s*:\\s*\\\"PROPOSE\\\".*"));
        org.junit.jupiter.api.Assertions.assertTrue(
            evidence.matches("(?s).*\\\"performative\\\"\\s*:\\s*\\\"ACCEPT\\\".*"));
        org.junit.jupiter.api.Assertions.assertTrue(evidence.contains("NORTHBOUND_DONE"));
        assertNegotiationWireEvidence(evidence);
      }
    } finally {
      if (previous == null) System.clearProperty("a2at.samples.negotiation");
      else System.setProperty("a2at.samples.negotiation", previous);
    }
  }

  protected String negotiationWireBoundary() {
    return "DIRECT_HTTP";
  }

  private void assertNegotiationWireEvidence(String evidence) {
    String uri = dev.openan.workflow.engine.client.A2ATExtension.NEGOTIATION_T.uri();
    String taskUri = dev.openan.workflow.engine.client.A2ATExtension.TASK_T.uri();
    var records = java.util.Arrays.asList(evidence.split("(?m)^.*? PROTOCOL - "));
    org.junit.jupiter.api.Assertions.assertTrue(
        records.stream()
            .anyMatch(
                record ->
                    record.startsWith("[" + negotiationWireBoundary() + "] REQUEST")
                        && record.contains("A2A-Extensions: " + taskUri)
                        && record.contains(uri)),
        "Missing Negotiation-T activation on the initial Task-T request");
    String request =
        records.stream()
            .filter(record -> record.startsWith("[" + negotiationWireBoundary() + "] REQUEST"))
            .filter(record -> record.contains("\"performative\": \"ACCEPT\""))
            .findFirst()
            .orElseThrow(() -> new AssertionError("No actual ACCEPT request log"));
    var id = java.util.regex.Pattern.compile("requestId=([^\\r\\n]+)").matcher(request);
    org.junit.jupiter.api.Assertions.assertTrue(id.find());
    String requestId = id.group(1);
    org.junit.jupiter.api.Assertions.assertTrue(
        records.stream()
            .anyMatch(
                record ->
                    record.startsWith("[" + negotiationWireBoundary() + "] REQUEST")
                        && record.contains("requestId=" + requestId)
                        && record.contains("A2A-Extensions: " + uri)),
        "Missing actual negotiation activation header");
    org.junit.jupiter.api.Assertions.assertTrue(request.contains("\"" + uri + "\":"));
    org.junit.jupiter.api.Assertions.assertTrue(request.contains("\"negotiationContext\": {"));
    org.junit.jupiter.api.Assertions.assertTrue(request.contains("=== Body ===\n{\n"));
    org.junit.jupiter.api.Assertions.assertTrue(
        records.stream()
            .anyMatch(
                record ->
                    record.contains("RESPONSE_BODY")
                        && record.contains("=== SSE data (JSON display; not wire text) ===")
                        && record.contains("\"performative\": \"PROPOSE\"")),
        "Missing observed PROPOSE SSE data");
  }

  protected String[] arguments() {
    return new String[] {
      "--a2a.transport-mode=direct",
      "--a2a.orch-url=http://127.0.0.1:1",
      "--a2a.local-workflow-enabled=true",
      "--a2a.credentials-path=" + demoCredentialsPath(),
      "--a2a.a2at-env-path=" + OfflineA2ATLlmClient.envPath()
    };
  }

  @Test
  @Timeout(120)
  void northboundComplaintCompletesAndResourcesClose() throws Exception {
    String previous = System.getProperty("a2at.samples.negotiation");
    int offset = captured.length();
    System.setProperty("a2at.samples.negotiation", "false");
    try {
      runDemo();
      {
        String evidence = captured.since(offset);
        org.junit.jupiter.api.Assertions.assertTrue(
            evidence.contains("NEGOTIATION_DEMO enabled=false"));
        org.junit.jupiter.api.Assertions.assertFalse(evidence.contains("DEMO_NEGOTIATION agent="));
        org.junit.jupiter.api.Assertions.assertFalse(evidence.contains("NEGOTIATION_APPLIED"));
      }
    } finally {
      if (previous == null) System.clearProperty("a2at.samples.negotiation");
      else System.setProperty("a2at.samples.negotiation", previous);
    }
  }

  @org.junit.jupiter.api.AfterEach
  void closeCapture() {
    if (previousCardLocations == null) System.clearProperty("A2A_AGENT_CARD_LOCATIONS");
    else System.setProperty("A2A_AGENT_CARD_LOCATIONS", previousCardLocations);
    if (previousSpool == null) System.clearProperty(SPOOL_PROPERTY);
    else System.setProperty(SPOOL_PROPERTY, previousSpool);
    captured.close();
  }

  private void runDemo() throws Exception {
    runDemo(arguments());
  }

  private void runDemo(String[] arguments) throws Exception {
    OfflineA2ATLlmClient.install();
    String previous = System.getProperty("a2at.env.path");
    System.setProperty("a2at.env.path", OfflineA2ATLlmClient.envPath());
    try {
      // run() propagates failure unless the northbound Task reaches COMPLETED.
      new SpringSpnDemo().run(arguments);
    } finally {
      if (previous == null) System.clearProperty("a2at.env.path");
      else System.setProperty("a2at.env.path", previous);
    }
  }

  @Test
  @Timeout(120)
  void northboundDemoWaitsForItsExecutionIdAtALocalCenter() throws Exception {
    var server =
        com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
    var mapper = new ObjectMapper();
    String revision = "a".repeat(64);
    String source = "urn:openan:host:identity-" + "b".repeat(64);
    String psopId = dev.openan.workflow.engine.examples.workbench.WorkbenchOrchestrator.SPN_PSOP_ID;
    var reported = new java.util.concurrent.ConcurrentLinkedQueue<JsonNode>();
    var searches = new java.util.concurrent.atomic.AtomicInteger();
    // The PSOP is found from the task's intent; nothing pins it, so the demo must search first.
    server.createContext(
        "/api/v1/orchestrate/search",
        exchange -> {
          try (exchange) {
            if (!"Bearer synthetic-local-center-session"
                .equals(exchange.getRequestHeaders().getFirst("Authorization"))) {
              exchange.sendResponseHeaders(401, -1);
              return;
            }
            searches.incrementAndGet();
            var intent = mapper.readTree(exchange.getRequestBody().readAllBytes());
            assertTrue(intent.path("intent").asText().length() > 0, "the search carries the task");
            byte[] response =
                mapper.writeValueAsBytes(
                    java.util.Map.of(
                        "status",
                        "success",
                        "data",
                        java.util.List.of(
                            java.util.Map.of(
                                "workflow_id", psopId, "name", "Local test PSOP", "score", 1.0))));
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
          }
        });
    server.createContext(
        "/api/v1/orchestrate/psop/" + psopId,
        exchange -> {
          try (exchange) {
            if (!"Bearer synthetic-local-center-session"
                .equals(exchange.getRequestHeaders().getFirst("Authorization"))) {
              exchange.sendResponseHeaders(401, -1);
              return;
            }
            var definition =
                java.util.Map.of(
                    "id",
                    psopId,
                    "name",
                    "Local test PSOP",
                    "revision",
                    revision,
                    "execution_reporting_source",
                    source,
                    "steps",
                    java.util.List.of());
            byte[] response =
                mapper.writeValueAsBytes(java.util.Map.of("status", "success", "data", definition));
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
          }
        });
    server.createContext(
        "/api/v1/execution-events",
        exchange -> {
          try (exchange) {
            if (!"Bearer synthetic-local-center-session"
                .equals(exchange.getRequestHeaders().getFirst("Authorization"))) {
              exchange.sendResponseHeaders(401, -1);
              return;
            }
            var batch = mapper.readTree(exchange.getRequestBody().readAllBytes());
            batch.forEach(reported::add);
            byte[] ack =
                mapper.writeValueAsBytes(
                    java.util.Map.of(
                        "execution_id",
                        batch.get(0).path("subject").asText(),
                        "accepted",
                        batch.size(),
                        "duplicates",
                        0,
                        "contiguous_through",
                        batch.get(batch.size() - 1).path("seq").asText()));
            exchange.sendResponseHeaders(200, ack.length);
            exchange.getResponseBody().write(ack);
          }
        });
    server.start();
    try {
      String url = "http://127.0.0.1:" + server.getAddress().getPort();
      var online =
          new ArrayList<>(
              Arrays.stream(arguments())
                  .filter(
                      arg ->
                          !arg.startsWith("--a2a.orch-url=")
                              && !arg.startsWith("--a2a.local-workflow-enabled="))
                  .toList());
      online.add("--a2a.orch-url=" + url);
      online.add("--a2a.local-workflow-enabled=false");
      online.add("--a2a.orch-username=");
      online.add("--a2a.orch-password=");
      online.add("--a2a.orch-api-token=synthetic-local-center-session");
      runDemo(online.toArray(String[]::new));

      assertEquals(1, searches.get(), "the demo retrieves its PSOP by intent, exactly once");
      assertEquals(2, reported.size());
      JsonNode first = reported.remove();
      JsonNode last = reported.remove();
      assertEquals(source, first.path("source").asText());
      assertEquals(psopId, first.path("data").path("psop_id").asText());
      assertEquals(revision, first.path("data").path("psop_revision").asText());
      assertTrue(last.path("type").asText().endsWith("execution.finished.v1"));
      assertEquals(first.path("subject").asText(), last.path("subject").asText());
      assertTrue(
          captured
              .since(0)
              .contains(
                  "EXECUTION_REPORTING_ACKNOWLEDGED execution_id="
                      + last.path("subject").asText()));
    } finally {
      server.stop(0);
    }
  }

  @Test
  @Timeout(120)
  @EnabledIfEnvironmentVariable(named = "OPENAN_TEST_DEMO_CENTER_URL", matches = "https?://.+")
  void northboundDemoIsVisibleInCenterExecutionHistory() throws Exception {
    String center = System.getenv("OPENAN_TEST_DEMO_CENTER_URL");
    String session = System.getenv("OPENAN_TEST_DEMO_CENTER_SESSION");
    var onlineArguments =
        new ArrayList<>(
            Arrays.stream(arguments())
                .filter(
                    arg ->
                        !arg.startsWith("--a2a.orch-url=")
                            && !arg.startsWith("--a2a.local-workflow-enabled="))
                .toList());
    onlineArguments.add("--a2a.orch-url=" + center);
    onlineArguments.add("--a2a.local-workflow-enabled=false");
    onlineArguments.add("--a2a.orch-username=");
    onlineArguments.add("--a2a.orch-password=");
    onlineArguments.add("--a2a.orch-api-token=" + session);
    onlineArguments.add(
        "--a2a.orch-ca-certs-path="
            + System.getenv().getOrDefault("OPENAN_TEST_DEMO_CENTER_CA", ""));

    runDemo(onlineArguments.toArray(String[]::new));
    String logs = captured.since(0);
    assertTrue(logs.contains("EXECUTION_REPORTING_ACKNOWLEDGED"));
    var confirmation =
        Pattern.compile("Execution reporting confirmed by the center, execution_id=([0-9a-f-]+)")
            .matcher(logs);
    assertTrue(confirmation.find(), "The Demo must log its own confirmed execution ID");
    String id = UUID.fromString(confirmation.group(1)).toString();

    var client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3));
    String ca = System.getenv("OPENAN_TEST_DEMO_CENTER_CA");
    if (ca != null && !ca.isBlank()) {
      dev.openan.workflow.engine.registry.TlsPolicy.trustedCa(ca).configure(client);
    }
    HttpClient http = client.build();
    JsonNode records = centerData(http, center + "/rest/v1/orchestrate/execution-records", session);
    assertTrue(
        records.findValuesAsText("execution_id").contains(id),
        "Execution history must list this Demo");
    JsonNode detail =
        centerData(http, center + "/rest/v1/orchestrate/execution-records/" + id, session);
    assertEquals("psop_spn_cross_city_diagnosis", detail.path("psop_id").asText());
    assertTrue(detail.path("psop_revision").asText().matches("[0-9a-f]{64}"));
    assertEquals("success", detail.path("status").asText());
    assertEquals("complete", detail.path("event_coverage").asText());
    assertEquals(0, detail.path("declared_gaps").asInt(-1));
    Set<String> taskRefs = new HashSet<>();
    Set<UUID> taskIds = new HashSet<>();
    for (JsonNode task : detail.path("execution_history")) {
      assertEquals("succeeded", task.path("outcome").asText());
      taskRefs.add(task.path("task_ref").asText());
      taskIds.add(UUID.fromString(task.path("task_id").asText()));
    }
    assertEquals(Set.of("s0/t0", "s1/t0", "s2/t0"), taskRefs);
    assertEquals(taskRefs.size(), taskIds.size());
    assertEquals(
        id,
        centerData(http, center + "/api/v1/executions/" + id, session)
            .path("execution_id")
            .asText());
  }

  @Test
  @Timeout(120)
  void malformedCenterResponseCannotLeakTokenIntoNorthboundTaskOrLogs() throws Exception {
    String token = "SyntheticNorthboundCenterCredential12345";
    var requests = new java.util.concurrent.atomic.AtomicInteger();
    var server = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/", exchange -> {
      if (("Bearer " + token).equals(exchange.getRequestHeaders().getFirst("Authorization"))) requests.incrementAndGet();
      exchange.getRequestBody().readAllBytes();
      byte[] body = token.getBytes(java.nio.charset.StandardCharsets.UTF_8);
      exchange.sendResponseHeaders(200, body.length);
      try (var output = exchange.getResponseBody()) { output.write(body); }
      finally { exchange.close(); }
    });
    server.start();
    int offset = captured.length();
    String previous = System.getProperty("a2at.env.path");
    try {
      OfflineA2ATLlmClient.install();
      System.setProperty("a2at.env.path", OfflineA2ATLlmClient.envPath());
      var onlineArguments = new java.util.ArrayList<>(java.util.Arrays.stream(arguments())
          .filter(arg -> !arg.startsWith("--a2a.orch-url=") && !arg.startsWith("--a2a.local-workflow-enabled="))
          .toList());
      onlineArguments.add("--a2a.orch-url=http://127.0.0.1:" + server.getAddress().getPort());
      onlineArguments.add("--a2a.local-workflow-enabled=false");
      onlineArguments.add("--a2a.orch-api-token=" + token);
      var error = org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class,
          () -> new SpringSpnDemo().run(onlineArguments.toArray(String[]::new)));
      var stack = new java.io.StringWriter();
      error.printStackTrace(new java.io.PrintWriter(stack));
      org.junit.jupiter.api.Assertions.assertFalse(stack.toString().contains(token));
      org.junit.jupiter.api.Assertions.assertTrue(error.getMessage().contains("TASK_STATE_FAILED"));
      org.junit.jupiter.api.Assertions.assertTrue(error.getMessage().contains("invalid response"));
      String evidence = captured.since(offset);
      org.junit.jupiter.api.Assertions.assertFalse(evidence.contains(token));
      org.junit.jupiter.api.Assertions.assertTrue(evidence.contains("HttpResponseException"));
      org.junit.jupiter.api.Assertions.assertTrue(evidence.contains("SHUTDOWN_DONE success=false"));
      org.junit.jupiter.api.Assertions.assertFalse(evidence.contains("LOCAL_DEMO_WORKFLOW"));
      org.junit.jupiter.api.Assertions.assertFalse(evidence.contains("stage=execute-workflow"));
      org.junit.jupiter.api.Assertions.assertEquals(1, requests.get());
    } finally {
      server.stop(0);
      if (previous == null) System.clearProperty("a2at.env.path");
      else System.setProperty("a2at.env.path", previous);
    }
  }
}
