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

import dev.openan.workflow.engine.examples.config.WorkbenchClientProperties;
import dev.openan.workflow.engine.examples.util.EnvResolver;
import jakarta.annotation.PreDestroy;
import org.a2aproject.sdk.spec.AgentCard;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/** Keeps Authorization-T/Notification-T protocol lifecycles independent from individual tasks. */
@Component
public final class SpringWorkbenchExtensionLifecycle {
  private static final Logger log =
      LoggerFactory.getLogger(SpringWorkbenchExtensionLifecycle.class);

  private final WorkbenchClientProperties properties;
  private final String localAgentName;
  private WorkbenchExtensionLifecycle lifecycle;
  private volatile java.util.concurrent.CompletableFuture<Void> firstRecovery =
      new java.util.concurrent.CompletableFuture<>();

  public SpringWorkbenchExtensionLifecycle(
      WorkbenchClientProperties properties, AgentCard localAgentCard) {
    this.properties = properties;
    this.localAgentName = localAgentCard.name();
  }

  @EventListener(ApplicationReadyEvent.class)
  public synchronized void start() {
    if (lifecycle != null && lifecycle.isActive()) {
      return;
    }
    firstRecovery = new java.util.concurrent.CompletableFuture<>();
    WorkbenchExtensionLifecycle candidate =
        new WorkbenchExtensionLifecycle(
            resolveCredentialsPath(),
            properties.isSslVerify(),
            resolveEnvPath(),
            null,
            this::onNotification,
            null,
            properties.isTaskCleanupEnabled(),
            properties.isTaskCleanupFailFast(),
            properties.getTaskCleanupPageSize(),
            properties.getTaskCleanupMaxTasks(),
            localAgentName,
            properties.getNotificationAckTimeoutSeconds());
    try {
      candidate.start();
      lifecycle = candidate;
    } catch (WorkbenchExtensionLifecycle.TaskCleanupException e) {
      candidate.close();
      lifecycle = null;
      throw e;
    } catch (RuntimeException e) {
      candidate.close();
      lifecycle = null;
      log.warn(
          "[ExtensionLifecycle] PREPOSITION_FAILED errorType={}, message={}, "
              + "action=continue-workflow",
          e.getClass().getSimpleName(),
          e.getMessage(),
          e);
    }
  }

  @PreDestroy
  public synchronized void close() {
    if (lifecycle == null) {
      return;
    }
    lifecycle.close();
    lifecycle = null;
  }

  /**
   * Bounded observation for the local demo before host shutdown, never a workflow prerequisite.
   * Only the first recovery is expected: a healthy city may never publish a recovery result.
   */
  public boolean awaitFirstRecovery(java.time.Duration timeout) {
    if (timeout.isNegative()) throw new IllegalArgumentException("timeout must not be negative");
    java.util.concurrent.CompletableFuture<Void> observed;
    synchronized (this) {
      if (lifecycle == null) return false;
      observed = firstRecovery;
    }
    try {
      observed.get(timeout.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS);
      return true;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return false;
    } catch (java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException e) {
      return false;
    }
  }

  private void onNotification(
      dev.openan.workflow.engine.client.NotificationSubscription handle,
      dev.openan.workflow.engine.model.ReceivedMessage received) {
    if (dev.openan.workflow.engine.examples.extension.RecoveryNotification.hasCompletedResult(
        received)) firstRecovery.complete(null);
    if (Boolean.TRUE.equals(received.taskMetadata().get("notificationHeartbeat"))) return;
    log.info(
        "[Notification] EVENT scope=workbench, agent={}, artifacts={}",
        handle.agentName(),
        received.artifacts().size());
    log.debug("[Notification] Content from {}: {}", handle.agentName(), received);
  }

  private String resolveEnvPath() {
    return properties.getA2atEnvPath() != null && !properties.getA2atEnvPath().isBlank()
        ? properties.getA2atEnvPath()
        : EnvResolver.resolveEnvPath();
  }

  private String resolveCredentialsPath() {
    if (properties.getCredentialsPath() != null && !properties.getCredentialsPath().isBlank()) {
      return properties.getCredentialsPath();
    }
    var resource = getClass().getClassLoader().getResource("spn_agent_credentials.json");
    return resource != null ? "classpath:spn_agent_credentials.json" : null;
  }
}
