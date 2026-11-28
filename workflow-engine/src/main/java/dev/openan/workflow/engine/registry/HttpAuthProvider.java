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

import java.net.URI;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * Supplies management HTTP credentials immediately before each request. Independent of A2A
 * authentication: applications own OAuth2 acquisition/refresh; OrchestrationAccountAuth provides
 * optional first-party account sessions for PSOP APIs. Implementations shared across threads must
 * be thread-safe and must not log credentials.
 */
@FunctionalInterface
public interface HttpAuthProvider {
  /** Returns authentication headers for this request; never null. */
  Map<String, String> headers(URI requestUri);

  /**
   * Allows one credential refresh after a rejected PSOP read/search or idempotent observation
   * batch. The headers are those actually sent, so implementations can avoid invalidating a newer
   * token acquired by another thread. Defaults to no retry.
   */
  default boolean onUnauthorized(URI requestUri, Map<String, String> rejectedHeaders) {
    return false;
  }

  /** Explicit anonymous access, only for a service configured to allow it. */
  static HttpAuthProvider none() {
    return uri -> Map.of();
  }

  /** Uses a fixed Bearer token. Missing or malformed tokens fail before any request is sent. */
  static HttpAuthProvider bearer(String token) {
    requireBearerToken(token);
    return bearer(() -> token);
  }

  /** Reads the current token once per request; the supplier owns refresh and expiry handling. */
  static HttpAuthProvider bearer(Supplier<String> tokens) {
    Objects.requireNonNull(tokens, "tokens");
    return uri -> {
      String token = tokens.get();
      requireBearerToken(token);
      return Map.of("Authorization", "Bearer " + token);
    };
  }

  private static void requireBearerToken(String token) {
    if (token == null || !token.matches("[A-Za-z0-9._~+/-]+=*")) {
      throw new IllegalArgumentException("Bearer token is missing or malformed");
    }
  }
}
