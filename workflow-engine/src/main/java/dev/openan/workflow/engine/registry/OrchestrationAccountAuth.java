/*
 * Copyright (c) 2026 Huawei Technologies Co., Ltd.
 * All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *     http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and limitations under the License.
 */
package dev.openan.workflow.engine.registry;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.function.LongSupplier;

/**
 * Thread-safe first-party account login for PSOP loading and execution reporting. Logs in lazily at
 * {@code POST /api/v1/auth/token}, caches the opaque Bearer until its advertised lifetime, and
 * renews by login (not an OAuth refresh grant). Share one instance across executions. Credentials
 * are scoped to the configured center's PSOP and reporting APIs; no cookies, agent credentials or
 * global TLS state. HTTP sends the password in plaintext; prefer verified HTTPS outside isolated
 * local development.
 */
public final class OrchestrationAccountAuth implements HttpAuthProvider {
  private static final ObjectMapper JSON = new ObjectMapper();
  private final URI loginUri;
  private final URI centerUri;
  private final String apiPath;
  private final String eventsPath;
  private final String username;
  private final String password;
  private final TlsPolicy tls;
  private final LoadPsop.Timeouts timeouts;
  private final LongSupplier clock;
  private String token;
  private long validUntil;

  /** Uses 30-second connection/read deadlines and the supplied independent center TLS policy. */
  public OrchestrationAccountAuth(String baseUrl, String username, String password, TlsPolicy tls) {
    this(
        baseUrl,
        username,
        password,
        tls,
        new LoadPsop.Timeouts(Duration.ofSeconds(30), Duration.ofSeconds(30)));
  }

  /**
   * Uses explicit deadlines for credential acquisition, independently of PSOP request deadlines.
   */
  public OrchestrationAccountAuth(
      String baseUrl, String username, String password, TlsPolicy tls, LoadPsop.Timeouts timeouts) {
    this(baseUrl, username, password, tls, timeouts, System::nanoTime);
  }

  OrchestrationAccountAuth(
      String baseUrl,
      String username,
      String password,
      TlsPolicy tls,
      LoadPsop.Timeouts timeouts,
      LongSupplier clock) {
    String base = HttpAuthentication.baseUrl(baseUrl);
    centerUri = URI.create(base);
    loginUri = URI.create(base + "/api/v1/auth/token");
    apiPath = centerUri.getRawPath() + "/api/v1/orchestrate/";
    eventsPath = centerUri.getRawPath() + CenterExecutionReporting.EVENTS_PATH;
    if (username == null || username.isBlank() || password == null || password.isBlank()) {
      throw new IllegalArgumentException(
          "Orchestration account username and password are required");
    }
    this.username = username;
    this.password = password;
    this.tls = Objects.requireNonNull(tls, "tls");
    this.timeouts = Objects.requireNonNull(timeouts, "timeouts");
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  @Override
  public synchronized Map<String, String> headers(URI requestUri) {
    requireScope(requestUri);
    if (token == null || clock.getAsLong() - validUntil >= 0) login();
    return Map.of("Authorization", "Bearer " + token);
  }

  @Override
  public synchronized boolean onUnauthorized(URI requestUri, Map<String, String> rejectedHeaders) {
    requireScope(requestUri);
    if (Objects.equals(rejectedHeaders.get("Authorization"), "Bearer " + token)) token = null;
    return true;
  }

  private void requireScope(URI uri) {
    if (uri == null
        || !centerUri.getScheme().equalsIgnoreCase(uri.getScheme())
        || !centerUri.getHost().equalsIgnoreCase(uri.getHost())
        || port(centerUri) != port(uri)
        || uri.getRawUserInfo() != null
        || uri.getRawQuery() != null
        || uri.getRawFragment() != null
        || !(uri.getRawPath().startsWith(apiPath) || uri.getRawPath().equals(eventsPath))
        || !uri.normalize().getRawPath().equals(uri.getRawPath())) {
      throw new IllegalArgumentException(
          "Account credentials are restricted to this center's PSOP and execution reporting APIs");
    }
  }

  private static int port(URI uri) {
    return uri.getPort() >= 0
        ? uri.getPort()
        : "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
  }

  private synchronized void login() {
    token = null; // Failed renewal must not preserve an expired/rejected credential.
    long started = clock.getAsLong();
    try {
      String body = JSON.writeValueAsString(Map.of("username", username, "password", password));
      var response =
          CenterHttpClient.execute(
              "POST", loginUri, body, HttpAuthProvider.none(), tls, timeouts, 64 * 1024);
      if (response.statusCode() != 200) {
        // Login responses may echo any submitted secret; discard bodies for every status.
        throw new HttpServiceException(
            "Orchestration account login", response.statusCode(), "", Map.of());
      }
      Session acquired =
          HttpResponseParser.parse(
              "Orchestration account login",
              "issue token",
              response.statusCode(),
              response.body(),
              data -> {
                Object rawToken = data.get("access_token");
                if (!(rawToken instanceof String receivedToken)
                    || !(data.get("token_type") instanceof String type)
                    || !"Bearer".equalsIgnoreCase(type)
                    || !(data.get("expires_in") instanceof Integer
                        || data.get("expires_in") instanceof Long)) {
                  throw new IllegalArgumentException();
                }
                HttpAuthProvider.bearer(receivedToken); // Validate without rendering its contents.
                long seconds = ((Number) data.get("expires_in")).longValue();
                if (seconds <= 0 || seconds > Long.MAX_VALUE / 2 / 1_000_000_000L) {
                  throw new IllegalArgumentException();
                }
                long lifetime = seconds * 1_000_000_000L;
                // Start before the request and allow bounded skew, even for a one-second TTL.
                return new Session(
                    receivedToken, started + lifetime - Math.min(lifetime / 10, 30_000_000_000L));
              });
      token = acquired.token;
      validUntil = acquired.validUntil;
    } catch (HttpServiceException | HttpResponseException safeFailure) {
      throw safeFailure;
    } catch (Exception failure) {
      // No parser/transport causes: they may render the password, token or full login body.
      throw new IllegalStateException(
          "Orchestration account login failed (" + failure.getClass().getSimpleName() + ")");
    }
  }

  // Intentionally no generated toString(): opaque credentials must not become diagnostics.
  private static final class Session {
    private final String token;
    private final long validUntil;

    Session(String token, long validUntil) {
      this.token = token;
      this.validUntil = validUntil;
    }
  }
}
