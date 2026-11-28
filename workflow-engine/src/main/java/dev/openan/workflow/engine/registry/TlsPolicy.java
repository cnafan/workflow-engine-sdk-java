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

package dev.openan.workflow.engine.registry;

import dev.openan.workflow.engine.client.SslContextFactory;
import java.net.http.HttpClient;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Connection-local TLS policy for center management APIs, independent of HTTP authentication.
 * Strict is the default. Insecure disables both certificate and hostname checks on this client
 * only; it never disables server-side mTLS or changes A2A transport policy or JVM defaults.
 */
public final class TlsPolicy {
  private static final Logger log = LoggerFactory.getLogger(TlsPolicy.class);
  private final SSLContext context;
  private final boolean insecure;

  private TlsPolicy(SSLContext context, boolean insecure) {
    this.context = context;
    this.insecure = insecure;
  }

  /** JVM CA trust and hostname verification. */
  public static TlsPolicy strict() {
    return new TlsPolicy(null, false);
  }

  static TlsPolicy verifiedHostname(SSLContext context) {
    return new TlsPolicy(context, false);
  }

  /** Strict verification using an application-owned PEM CA bundle. */
  public static TlsPolicy trustedCa(String caCertsPath) {
    java.util.Objects.requireNonNull(caCertsPath, "caCertsPath");
    return mutualTls(caCertsPath, null, null, null);
  }

  /** Strict verification and optional client identity (PEM certificate and PKCS#8 key). */
  public static TlsPolicy mutualTls(
      String caCertsPath, String clientCertPath, String clientKeyPath, String keyPassword) {
    return new TlsPolicy(
        SslContextFactory.create(
                true, caCertsPath, clientCertPath, clientKeyPath, keyPassword, null)
            .orElseThrow(),
        false);
  }

  /** Explicit development opt-out, equivalent to Python verify=False for HTTPS requests. */
  public static TlsPolicy insecure() {
    return insecure(null, null, null);
  }

  /** Opt-out retaining a client identity, if the server requires mTLS. */
  public static TlsPolicy insecure(
      String clientCertPath, String clientKeyPath, String keyPassword) {
    return new TlsPolicy(
        SslContextFactory.createInsecure(clientCertPath, clientKeyPath, keyPassword), true);
  }

  /** Applies this policy to a client dedicated to the orchestration center. */
  public void configure(HttpClient.Builder client) {
    if (context != null) client.sslContext(context);
    warnIfInsecure();
  }

  void configure(HttpsURLConnection connection) {
    if (context != null) connection.setSSLSocketFactory(context.getSocketFactory());
    if (insecure) connection.setHostnameVerifier((hostname, session) -> true);
    warnIfInsecure();
  }

  private void warnIfInsecure() {
    if (insecure) {
      log.warn(
          "INSECURE_TLS: certificate and hostname verification disabled for this management client");
    }
  }
}
