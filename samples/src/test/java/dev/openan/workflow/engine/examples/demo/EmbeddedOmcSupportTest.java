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
package dev.openan.workflow.engine.examples.demo;

import static org.junit.jupiter.api.Assertions.*;

import dev.openan.workflow.engine.client.AgentCardJacksonModule;
import java.util.List;
import org.a2aproject.sdk.spec.AgentCard;
import org.junit.jupiter.api.Test;

class EmbeddedOmcSupportTest {
  @Test
  void explicitFlagControlsStartupAndRejectsTypos() {
    assertFalse(EmbeddedOmcSupport.enabled(new String[] {"--a2a.embedded-omc-enabled=false"}, true));
    assertTrue(EmbeddedOmcSupport.enabled(new String[] {"--a2a.embedded-omc-enabled=true"}, false));
    assertThrows(IllegalArgumentException.class,
        () -> EmbeddedOmcSupport.enabled(new String[] {"--a2a.embedded-omc-enabled=treu"}, true));
  }

  @Test
  void externalModeDoesNotLoadBundledOrExternalCardsForBinding() {
    String previous = System.getProperty("A2A_AGENT_CARD_LOCATIONS");
    System.setProperty("A2A_AGENT_CARD_LOCATIONS", "file:/missing-card-must-not-be-read.json");
    try {
      assertEquals(List.of(), EmbeddedOmcSupport.prepare(false));
    } finally {
      if (previous == null) System.clearProperty("A2A_AGENT_CARD_LOCATIONS");
      else System.setProperty("A2A_AGENT_CARD_LOCATIONS", previous);
    }
  }

  @Test
  void validatesBothCardsBeforeStartingEitherServer() throws Exception {
    var city1 = card("city1", "127.0.0.1:26335");
    var city2 = card("city2", "192.0.2.17:26336");
    assertThrows(IllegalArgumentException.class,
        () -> EmbeddedOmcSupport.validateTargets(List.of(city1, city2)));
    assertThrows(IllegalArgumentException.class,
        () -> EmbeddedOmcSupport.validateTargets(List.of(city1)));
  }

  @Test
  void usesConfiguredLocalCardsInsteadOfBundledDefaultPorts() throws Exception {
    var city1 = card("city1", "localhost:27335");
    var city2 = card("city2", "127.0.0.1:27336");
    assertEquals(List.of(city1, city2), EmbeddedOmcSupport.validateTargets(List.of(city2, city1)));
  }

  @Test
  void launchesTypedCardWithoutLosingSecurityScheme() throws Exception {
    var card = card("city1", "127.0.0.1:0");
    try (var launcher = new dev.openan.workflow.engine.examples.server.OmcAgentLauncher()) {
      var server = launcher.startFromCard(card,
          new org.a2aproject.sdk.server.agentexecution.AgentExecutor() {
            @Override public void execute(
                org.a2aproject.sdk.server.agentexecution.RequestContext context,
                org.a2aproject.sdk.server.tasks.AgentEmitter emitter) {
              throw new AssertionError("Binding must not dispatch a task");
            }
            @Override public void cancel(
                org.a2aproject.sdk.server.agentexecution.RequestContext context,
                org.a2aproject.sdk.server.tasks.AgentEmitter emitter) {}
          });
      assertEquals(card.name(), server.getAgentName());
      var schemes = (java.util.Map<?, ?>) server.getAgentCard().get("securitySchemes");
      var bearer = (java.util.Map<?, ?>) schemes.values().iterator().next();
      assertTrue(bearer.containsKey("httpAuthSecurityScheme"));
    }
  }

  private AgentCard card(String city, String address) throws Exception {
    try (var input = getClass().getResourceAsStream("/agentcard/spn_domain_agent_" + city + ".json")) {
      var mapper = new com.fasterxml.jackson.databind.ObjectMapper()
          .registerModule(new AgentCardJacksonModule());
      var card = (com.fasterxml.jackson.databind.node.ObjectNode) mapper.readTree(input);
      var iface = (com.fasterxml.jackson.databind.node.ObjectNode) card.path("supportedInterfaces").get(0);
      // The checked-in port is a developer's local choice; only the authority comes from the test.
      var checkedIn = java.net.URI.create(iface.path("url").asText());
      iface.put("url", checkedIn.getScheme() + "://" + address + checkedIn.getPath());
      return mapper.treeToValue(card, AgentCard.class);
    }
  }
}
