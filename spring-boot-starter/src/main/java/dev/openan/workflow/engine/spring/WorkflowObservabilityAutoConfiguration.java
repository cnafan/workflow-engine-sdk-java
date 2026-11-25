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

package dev.openan.workflow.engine.spring;

import dev.openan.workflow.engine.runner.WorkflowExecutionFactory;
import net.openan.observability.Observability;
import net.openan.observability.ObservabilitySdk;
import net.openan.observability.SdkConfig;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/** Auto-configures one host-owned Observability SDK for workflow execution. */
@AutoConfiguration
@ConditionalOnClass(ObservabilitySdk.class)
@ConditionalOnProperty(name = "a2at.observability.enabled", havingValue = "true")
@EnableConfigurationProperties(WorkflowObservabilityProperties.class)
public class WorkflowObservabilityAutoConfiguration {

  @Bean(destroyMethod = "close")
  @ConditionalOnMissingBean
  public ObservabilitySdk observabilitySdk(WorkflowObservabilityProperties properties) {
    return ObservabilitySdk.start(
        SdkConfig.builder().serviceName(properties.getServiceName()).build());
  }

  @Bean
  @ConditionalOnMissingBean
  public Observability observability(ObservabilitySdk sdk) {
    return sdk.observe();
  }

  @Bean
  @ConditionalOnMissingBean
  public WorkflowExecutionFactory workflowExecutionFactory(Observability observability) {
    return new WorkflowExecutionFactory(observability);
  }
}
