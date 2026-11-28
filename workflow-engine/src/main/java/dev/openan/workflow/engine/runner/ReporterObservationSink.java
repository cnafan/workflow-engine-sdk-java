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

package dev.openan.workflow.engine.runner;

import dev.openan.workflow.engine.model.Workflow;

/**
 * Adapts a real {@link ExecutionReporter} to the seam an execution talks to.
 *
 * <p>Keeping the adapter here leaves the reporter's own API unchanged: the execution never needs a
 * public {@code begin}, and a test substitutes {@link ObservationSink} directly instead.
 */
final class ReporterObservationSink implements ObservationSink {
  private final ExecutionReporter reporter;

  ReporterObservationSink(ExecutionReporter reporter) {
    this.reporter = reporter;
  }

  @Override
  public Observation begin(
      Workflow workflow,
      String executionId,
      String businessRef,
      String traceparent,
      String tracestate) {
    return reporter.begin(workflow, executionId, businessRef, traceparent, tracestate);
  }
}
