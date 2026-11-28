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
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package dev.openan.workflow.engine.registry;

import dev.openan.workflow.engine.runner.ContentCapture;
import dev.openan.workflow.engine.runner.ExecutionReporter;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Shares the PSOP connection and the SDK-owned durable event queue across workflow runs. */
public final class CenterExecutionReporting implements AutoCloseable {
  static final String EVENTS_PATH = "/api/v1/execution-events";
  private static final Logger LOG = LoggerFactory.getLogger(CenterExecutionReporting.class);
  private final URI endpoint;
  private final HttpAuthProvider auth;
  private final TlsPolicy tls;
  private final Path spool;
  private final ContentCapture capture;
  private String source;
  private ExecutionReporter reporter;

  /** Guards the "reporting is off" line so one host session explains itself once. */
  private boolean announcedDisabled;

  private boolean closed;

  /** Name of the system property or environment variable that relocates the default spool. */
  public static final String SPOOL_VARIABLE = "OPENAN_EXECUTION_SPOOL";

  /** Name of the variable that gives one host process its own spool under the default root. */
  public static final String INSTANCE_VARIABLE = "OPENAN_EXECUTION_INSTANCE";

  /**
   * Spools under {@code OPENAN_EXECUTION_SPOOL} when it is set, otherwise {@code
   * ~/.openan/execution-events}. Each host process should own its own directory; set it when that
   * directory has to live on a mounted volume. A system property wins over the environment so a
   * host embedding the SDK, or a test, can choose the location for one process.
   *
   * <p>Two host processes running as the same center account share one reporting identity, so by
   * default they would also share one spool and the second would fail to start. Give each one an
   * {@code OPENAN_EXECUTION_INSTANCE} name to separate them; unlike a path it survives restarts of
   * that instance, so its pending events are still replayed next time.
   */
  public CenterExecutionReporting(String baseUrl, HttpAuthProvider auth, TlsPolicy tls) {
    this(baseUrl, auth, tls, defaultSpool());
  }

  private static Path defaultSpool() {
    String configured = System.getProperty(SPOOL_VARIABLE);
    if (configured == null || configured.isBlank()) {
      configured = System.getenv(SPOOL_VARIABLE);
    }
    Path root =
        configured == null || configured.isBlank()
            ? Path.of(System.getProperty("user.home"), ".openan", "execution-events")
            : Path.of(configured);
    String instance = System.getProperty(INSTANCE_VARIABLE);
    if (instance == null || instance.isBlank()) {
      instance = System.getenv(INSTANCE_VARIABLE);
    }
    return instance == null || instance.isBlank() ? root : root.resolve(instance);
  }

  /**
   * Uses the given directory for the durable event queue. One directory belongs to one host
   * process: sharing it makes two hosts replay each other's pending events. Reported content is the
   * readable summary, with no structured outputs; see {@link #CenterExecutionReporting(String,
   * HttpAuthProvider, TlsPolicy, Path, ContentCapture)}.
   */
  public CenterExecutionReporting(
      String baseUrl, HttpAuthProvider auth, TlsPolicy tls, Path spool) {
    this(baseUrl, auth, tls, spool, ContentCapture.summaries());
  }

  /**
   * Uses the given directory and content policy.
   *
   * <p>The default policy reports each task's readable summary and no structured outputs, because
   * the center's contract requires the application to name the keys it is willing to share. A host
   * whose replay needs structured results passes a policy that names them, or {@link
   * ContentCapture#all()} when it has decided every output is safe to disclose.
   */
  public CenterExecutionReporting(
      String baseUrl, HttpAuthProvider auth, TlsPolicy tls, Path spool, ContentCapture capture) {
    this.endpoint = URI.create(HttpAuthentication.baseUrl(baseUrl) + EVENTS_PATH);
    this.auth = auth;
    this.tls = tls;
    this.spool = spool;
    this.capture = capture;
  }

  /**
   * Returns the reporter for the center's authenticated identity and published revision, or null
   * when this run will not be reported. Every reason for not reporting is logged once, because the
   * alternative is a host that looks healthy while the center never hears about its runs.
   */
  public synchronized ExecutionReporter forPsop(LoadPsop.LoadedPsop psop) {
    String requestedSource = psop.reportingSource();
    if (closed) return null;
    if (requestedSource == null || psop.workflow().getRevision() == null) {
      if (!announcedDisabled) {
        announcedDisabled = true;
        LOG.info(
            "Execution reporting is off: the center did not offer a reporting identity for this"
                + " PSOP and account");
      }
      return null;
    }
    if (!requestedSource.matches("urn:openan:host:identity-[0-9a-f]{64}")) {
      LOG.warn("The center returned an invalid execution reporting source");
      return null;
    }
    if (reporter != null) {
      if (source.equals(requestedSource)) return reporter;
      LOG.warn("The center reporting identity changed during the host session");
      return null;
    }
    HttpAuthProvider checkedAuth =
        new HttpAuthProvider() {
          @Override
          public Map<String, String> headers(URI uri) {
            return HttpAuthentication.headers(auth, uri);
          }

          @Override
          public boolean onUnauthorized(URI uri, Map<String, String> rejectedHeaders) {
            return HttpAuthentication.onUnauthorized(auth, uri, rejectedHeaders);
          }
        };
    try {
      var http =
          HttpClient.newBuilder()
              .connectTimeout(Duration.ofSeconds(3))
              .version(HttpClient.Version.HTTP_1_1);
      tls.configure(http);
      reporter =
          new ExecutionReporter(
              endpoint, checkedAuth, requestedSource, spool, capture, http.build());
      source = requestedSource;
      LOG.info(
          "Execution reporting enabled: endpoint={} source={} spool={}", endpoint, source, spool);
      return reporter;
    } catch (IOException | RuntimeException error) {
      LOG.warn(
          "Could not initialize execution reporting ({}: {})",
          error.getClass().getSimpleName(),
          error.getMessage());
      return null;
    }
  }

  public synchronized ExecutionReporter activeReporter() {
    return reporter;
  }

  /**
   * Whether the center authorized reporting for this host session. False after a PSOP that carried
   * no reporting identity, so a host can show that its runs are not being recorded.
   */
  public synchronized boolean isEnabled() {
    return reporter != null;
  }

  @Override
  public synchronized void close() {
    closed = true;
    if (reporter != null) reporter.close();
  }
}
