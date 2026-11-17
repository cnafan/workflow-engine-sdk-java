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

/*
 * Copyright (c) 2026 OpenAN. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package dev.openan.workflow.engine.spring;

import jakarta.servlet.http.HttpServletRequest;
import org.a2aproject.sdk.spec.A2AError;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Optional slash-style aliases for A2A action endpoints.
 *
 * <p>Every operation delegates to {@link A2AController}; the aliases therefore retain the canonical
 * endpoint's authentication, A2A error envelope, streaming, and cleanup behavior.
 */
@RestController
@ConditionalOnProperty(
    prefix = "a2at.server",
    name = {"enabled", "slash-action-aliases-enabled"},
    havingValue = "true")
public class A2ASlashActionAliasController {

  private final A2AController delegate;

  public A2ASlashActionAliasController(A2AController delegate) {
    this.delegate = delegate;
  }

  @PostMapping("${a2at.server.path-prefix}/message/send")
  ResponseEntity<String> sendMessage(HttpServletRequest request, @RequestBody String body) {
    return delegate.sendMessage(request, body);
  }

  @PostMapping(
      value = "${a2at.server.path-prefix}/message/stream",
      produces = MediaType.TEXT_EVENT_STREAM_VALUE)
  SseEmitter streamMessage(HttpServletRequest request, @RequestBody String body) {
    return delegate.streamMessage(request, body);
  }

  @PostMapping("${a2at.server.path-prefix}/tasks/{id}/cancel")
  ResponseEntity<String> cancelTask(
      HttpServletRequest request,
      @PathVariable("id") String taskId,
      @RequestBody(required = false) String body) {
    return delegate.cancelTask(request, taskId, body);
  }

  @PostMapping(
      value = "${a2at.server.path-prefix}/tasks/{id}/subscribe",
      produces = MediaType.TEXT_EVENT_STREAM_VALUE)
  SseEmitter subscribeToTask(HttpServletRequest request, @PathVariable("id") String taskId) {
    return delegate.subscribeToTask(request, taskId);
  }

  @ExceptionHandler(A2AError.class)
  ResponseEntity<String> a2aError(A2AError error) {
    return delegate.a2aError(error);
  }
}
