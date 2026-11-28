/*
 * Copyright (c) 2026 Huawei Technologies Co., Ltd.
 * All Rights Reserved.
 *
 * SPDX-License-Identifier: Apache-2.0
 *
 *    Licensed under the Apache License, Version 2.0 (the License); you may
 *    not use this file except in compliance with the License. You may obtain
 *    a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an AS IS BASIS, WITHOUT
 *    WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 *    License for the specific language governing permissions and limitations
 *    under the License.
 */

package dev.openan.workflow.engine.registry;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.openan.workflow.engine.model.Workflow;
import dev.openan.workflow.engine.model.WorkflowSearchResult;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Load and search PSOP workflows from the orchestration center's external API.
 *
 * <ul>
 *   <li>{@code load} -- GET /api/v1/orchestrate/psop/{psop_id} (full workflow)
 *   <li>{@code search} -- POST /api/v1/orchestrate/search (summary list by intent)
 * </ul>
 *
 * <p>Defaults verify the server using JVM trust and hostname checks. Explicit {@code
 * sslVerify=false} skips both checks for this connection only, for controlled development without
 * local trust files. It does not remove the server's HTTPS certificate or satisfy a server
 * requirement for mTLS.
 */
public class LoadPsop {
  private static final Logger log = LoggerFactory.getLogger(LoadPsop.class);
  private static final ObjectMapper mapper = new ObjectMapper();

  /** Deadlines for a storage read: the center answers a published revision from its store. */
  static final Timeouts DEFAULT_TIMEOUTS =
      new Timeouts(Duration.ofSeconds(30), Duration.ofSeconds(30));

  /**
   * Deadlines for a retrieval. The center ranks its catalog with a language model, so one search
   * routinely takes tens of seconds; the storage deadline is short enough to abort it.
   */
  static final Timeouts SEARCH_TIMEOUTS =
      new Timeouts(Duration.ofSeconds(30), Duration.ofSeconds(120));

  public static Workflow load(String baseUrl, String psopId, String accessToken, boolean sslVerify)
      throws Exception {
    return load(baseUrl, psopId, accessToken, sslVerify, DEFAULT_TIMEOUTS);
  }

  /** Loads one workflow with explicit connection and response-read deadlines. */
  public static Workflow load(
      String baseUrl, String psopId, String accessToken, boolean sslVerify, Timeouts timeouts)
      throws Exception {
    return load(
        baseUrl,
        psopId,
        tokenAuth(accessToken),
        sslVerify ? TlsPolicy.strict() : TlsPolicy.insecure(),
        timeouts);
  }

  /** Loads a workflow using independent HTTP authentication and TLS policy. */
  public static Workflow load(String baseUrl, String psopId, HttpAuthProvider auth, TlsPolicy tls)
      throws Exception {
    return load(baseUrl, psopId, auth, tls, DEFAULT_TIMEOUTS);
  }

  /** Loads a workflow with per-request credentials and explicit deadlines. */
  public static Workflow load(
      String baseUrl, String psopId, HttpAuthProvider auth, TlsPolicy tls, Timeouts timeouts)
      throws Exception {
    return loadForExecution(baseUrl, psopId, auth, tls, timeouts).workflow();
  }

  /** Loads the PSOP and its reporting identity using the same authenticated center request. */
  public static LoadedPsop loadForExecution(
      String baseUrl, String psopId, HttpAuthProvider auth, TlsPolicy tls) throws Exception {
    return loadForExecution(baseUrl, psopId, auth, tls, DEFAULT_TIMEOUTS);
  }

  private static LoadedPsop loadForExecution(
      String baseUrl, String psopId, HttpAuthProvider auth, TlsPolicy tls, Timeouts timeouts)
      throws Exception {
    Objects.requireNonNull(psopId, "psopId");
    if (psopId.isBlank() || psopId.equals(".") || psopId.equals("..")) {
      throw new IllegalArgumentException("psopId must identify a workflow");
    }
    String url =
        HttpAuthentication.baseUrl(baseUrl)
            + "/api/v1/orchestrate/psop/"
            + URLEncoder.encode(psopId, StandardCharsets.UTF_8).replace("+", "%20");
    log.info("[Registry] Loading PSOP from {}", url);
    CenterHttpClient.Response resp = execute("GET", url, null, auth, tls, timeouts);
    if (resp.statusCode() != 200) {
      throw requestFailure(resp);
    }
    LoadedPsop loaded =
        HttpResponseParser.parse(
            "Orchestration center",
            "load workflow",
            resp.statusCode(),
            resp.body(),
            data -> {
              @SuppressWarnings("unchecked")
              Map<String, Object> psopData = (Map<String, Object>) data.getOrDefault("data", data);
              return new LoadedPsop(
                  Workflow.fromMap(psopData),
                  psopData.get("execution_reporting_source") instanceof String source
                      ? source
                      : null);
            });
    Workflow wf = loaded.workflow();
    log.info("[Registry] Loaded workflow: {}, {} steps", wf.getName(), wf.getSteps().size());
    return loaded;
  }

  public static List<WorkflowSearchResult> search(
      String baseUrl, String intent, int topN, String accessToken, boolean sslVerify)
      throws Exception {
    return search(baseUrl, intent, topN, accessToken, sslVerify, SEARCH_TIMEOUTS);
  }

  public static Workflow load(String baseUrl, String psopId) throws Exception {
    return load(baseUrl, psopId, null, true);
  }

  /** Searches using credentials evaluated before every request. */
  public static List<WorkflowSearchResult> search(
      String baseUrl, String intent, int topN, HttpAuthProvider auth, TlsPolicy tls)
      throws Exception {
    return search(baseUrl, intent, topN, auth, tls, SEARCH_TIMEOUTS);
  }

  /** Searches workflows with explicit connection and response-read deadlines. */
  public static List<WorkflowSearchResult> search(
      String baseUrl,
      String intent,
      int topN,
      String accessToken,
      boolean sslVerify,
      Timeouts timeouts)
      throws Exception {
    return search(
        baseUrl,
        intent,
        topN,
        tokenAuth(accessToken),
        sslVerify ? TlsPolicy.strict() : TlsPolicy.insecure(),
        timeouts);
  }

  /** Searches with independent authentication, TLS policy and explicit deadlines. */
  public static List<WorkflowSearchResult> search(
      String baseUrl,
      String intent,
      int topN,
      HttpAuthProvider auth,
      TlsPolicy tls,
      Timeouts timeouts)
      throws Exception {
    String url = HttpAuthentication.baseUrl(baseUrl) + "/api/v1/orchestrate/search";
    log.info("[Registry] Searching PSOP at {} (top_n={})", url, topN);
    String jsonBody = mapper.writeValueAsString(Map.of("intent", intent, "top_n", topN));
    CenterHttpClient.Response resp = execute("POST", url, jsonBody, auth, tls, timeouts);
    if (resp.statusCode() != 200) {
      throw requestFailure(resp);
    }
    List<WorkflowSearchResult> results =
        HttpResponseParser.parse(
            "Orchestration center",
            "search workflows",
            resp.statusCode(),
            resp.body(),
            data -> {
              @SuppressWarnings("unchecked")
              List<Map<String, Object>> rawResults =
                  (List<Map<String, Object>>) data.getOrDefault("data", List.of());
              List<WorkflowSearchResult> parsed = new ArrayList<>();
              for (Map<String, Object> raw : rawResults) {
                parsed.add(
                    WorkflowSearchResult.builder()
                        .workflowId(
                            (String) raw.getOrDefault("workflow_id", raw.getOrDefault("id", "")))
                        .workflowType((String) raw.getOrDefault("workflow_type", ""))
                        .name((String) raw.getOrDefault("name", ""))
                        .description((String) raw.getOrDefault("description", null))
                        .createdAt(
                            raw.get("created_at") != null ? raw.get("created_at").toString() : "")
                        .score(raw.get("score") instanceof Number n ? n.doubleValue() : 1.0)
                        .userIntent((String) raw.getOrDefault("user_intent", null))
                        .relatedPreflow((String) raw.getOrDefault("related_preflow", null))
                        .tasksSummary((String) raw.getOrDefault("tasks_summary", null))
                        .build());
              }
              return parsed;
            });
    log.info("[Registry] Search returned {} workflow(s)", results.size());
    return results;
  }

  private static CenterHttpClient.Response execute(
      String method,
      String url,
      String jsonBody,
      HttpAuthProvider auth,
      TlsPolicy tls,
      Timeouts timeouts)
      throws Exception {
    Objects.requireNonNull(timeouts, "timeouts");
    Objects.requireNonNull(auth, "auth");
    Objects.requireNonNull(tls, "tls");
    URI uri = URI.create(url);
    var response = CenterHttpClient.execute(method, uri, jsonBody, auth, tls, timeouts, 0);
    // Both operations are read-only (search uses POST); no business execution/write is replayed.
    if (response.statusCode() == 401
        && HttpAuthentication.onUnauthorized(auth, uri, response.credentials())) {
      return CenterHttpClient.execute(method, uri, jsonBody, auth, tls, timeouts, 0);
    }
    return response;
  }

  private static HttpAuthProvider tokenAuth(String token) {
    return token == null || token.isEmpty()
        ? HttpAuthProvider.none()
        : HttpAuthProvider.bearer(token);
  }

  private static HttpServiceException requestFailure(CenterHttpClient.Response response) {
    return new HttpServiceException(
        "Orchestration center", response.statusCode(), response.body(), response.credentials());
  }

  public record LoadedPsop(Workflow workflow, String reportingSource) {}

  /** Convenience: search with defaults (top_n=5, no token, ssl_verify=true). */
  public static List<WorkflowSearchResult> search(String baseUrl, String intent) throws Exception {
    return search(baseUrl, intent, 5, null, true);
  }

  /** Connection and response-read deadlines for orchestration-center calls. */
  public record Timeouts(Duration connectTimeout, Duration readTimeout) {
    public Timeouts {
      requirePositiveAndSupported(connectTimeout, "connectTimeout");
      requirePositiveAndSupported(readTimeout, "readTimeout");
    }

    private static void requirePositiveAndSupported(Duration value, String name) {
      if (value == null || value.isZero() || value.isNegative()) {
        throw new IllegalArgumentException(name + " must be positive");
      }
      long milliseconds;
      try {
        milliseconds = value.toMillis();
      } catch (ArithmeticException error) {
        throw new IllegalArgumentException(name + " exceeds HttpURLConnection limit", error);
      }
      if (milliseconds < 1) {
        throw new IllegalArgumentException(name + " must be at least 1 millisecond");
      }
      if (milliseconds > Integer.MAX_VALUE) {
        throw new IllegalArgumentException(name + " exceeds HttpURLConnection limit");
      }
    }

    int connectTimeoutMillis() {
      return Math.toIntExact(connectTimeout.toMillis());
    }

    int readTimeoutMillis() {
      return Math.toIntExact(readTimeout.toMillis());
    }
  }
}
