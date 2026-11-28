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
import java.util.Map;

/**
 * What one execution asks of execution reporting.
 *
 * <p>Reporting is a cross-cutting concern: it must never decide whether, or how, a workflow runs.
 * The execution therefore reaches a reporter only through this seam, swallows every failure it can
 * raise, and keeps going. {@link ExecutionReporter} is the production implementation; a test
 * substitutes a failing one to prove the isolation holds.
 */
interface ObservationSink {

  /**
   * Starts reporting one run.
   *
   * @return the run's handle, or {@code null} when this run is not reported
   */
  Observation begin(
      Workflow workflow,
      String executionId,
      String businessRef,
      String traceparent,
      String tracestate);

  /** One run's reporting handle: the calls an execution makes while it runs. */
  interface Observation {

    /** Reports one engine event. */
    void observe(String eventType, Map<String, Object> data);

    /** Reports the run's terminal outcome. */
    void finish(String outcome);
  }
}
