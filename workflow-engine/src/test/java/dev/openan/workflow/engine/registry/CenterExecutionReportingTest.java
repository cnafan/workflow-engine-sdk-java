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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.openan.workflow.engine.model.Workflow;
import dev.openan.workflow.engine.model.WorkflowStep;
import dev.openan.workflow.engine.runner.ExecutionReporter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Reporting must ask the host for nothing but the center address and account. These tests pin that
 * the reporting identity comes from the loaded PSOP, that a host can tell whether its runs are
 * being recorded, and that the durable queue lives where the host said.
 */
class CenterExecutionReportingTest {
  private static final String SOURCE = "urn:openan:host:identity-" + "c".repeat(64);
  private static final String CENTER = "http://127.0.0.1:15001";

  @Test
  void buildsOneReporterFromThePsopIdentityAndKeepsItForTheSession(@TempDir Path spool)
      throws IOException {
    var reporting =
        new CenterExecutionReporting(CENTER, HttpAuthProvider.none(), TlsPolicy.insecure(), spool);
    try {
      Workflow workflow = workflow("a".repeat(64));
      assertNull(reporting.activeReporter(), "nothing is reported before a PSOP authorizes it");
      assertFalse(reporting.isEnabled());

      ExecutionReporter reporter = reporting.forPsop(new LoadPsop.LoadedPsop(workflow, SOURCE));
      assertNotNull(reporter, "a PSOP that names a reporting source must be reported");
      assertTrue(reporting.isEnabled());
      assertSame(
          reporter,
          reporting.forPsop(new LoadPsop.LoadedPsop(workflow, SOURCE)),
          "every run of the session shares one durable queue");
      assertSame(reporter, reporting.activeReporter());

      try (Stream<Path> staged = Files.list(spool)) {
        assertEquals(
            1,
            staged.count(),
            "the durable queue lives where the host said, not in a shared home directory");
      }
    } finally {
      reporting.close();
    }
    assertNull(
        reporting.forPsop(new LoadPsop.LoadedPsop(workflow("a".repeat(64)), SOURCE)),
        "a closed session reports nothing");
  }

  @Test
  void staysOffWhenTheCenterOffersNoReportingIdentity(@TempDir Path spool) {
    var reporting =
        new CenterExecutionReporting(CENTER, HttpAuthProvider.none(), TlsPolicy.insecure(), spool);
    try {
      // Repeated runs must not start threads or fail: they are simply not reported.
      for (int run = 0; run < 3; run++) {
        assertNull(reporting.forPsop(new LoadPsop.LoadedPsop(workflow("a".repeat(64)), null)));
      }
      assertNull(reporting.activeReporter());
      assertFalse(reporting.isEnabled());
    } finally {
      reporting.close();
    }
  }

  @Test
  void refusesAnIdentityTheCenterDidNotDerive(@TempDir Path spool) {
    var reporting =
        new CenterExecutionReporting(CENTER, HttpAuthProvider.none(), TlsPolicy.insecure(), spool);
    try {
      assertNull(
          reporting.forPsop(
              new LoadPsop.LoadedPsop(workflow("a".repeat(64)), "urn:openan:host:anything")));
      assertFalse(reporting.isEnabled(), "an unrecognised identity must never be reported as");
    } finally {
      reporting.close();
    }
  }

  @Test
  void needsAPublishedRevision(@TempDir Path spool) {
    var reporting =
        new CenterExecutionReporting(CENTER, HttpAuthProvider.none(), TlsPolicy.insecure(), spool);
    try {
      assertNull(reporting.forPsop(new LoadPsop.LoadedPsop(workflow(null), SOURCE)));
      assertFalse(reporting.isEnabled(), "an unpublished PSOP has no revision to report under");
    } finally {
      reporting.close();
    }
  }

  @Test
  void rejectsACenterAddressItCannotReportTo(@TempDir Path spool) {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new CenterExecutionReporting(
                "ftp://127.0.0.1:15001", HttpAuthProvider.none(), TlsPolicy.insecure(), spool));
  }

  private static Workflow workflow(String revision) {
    Workflow workflow =
        Workflow.builder()
            .name("reported")
            .steps(List.of(WorkflowStep.builder().name("diagnose").build()))
            .build();
    workflow.setRevision(revision);
    return workflow;
  }
}
