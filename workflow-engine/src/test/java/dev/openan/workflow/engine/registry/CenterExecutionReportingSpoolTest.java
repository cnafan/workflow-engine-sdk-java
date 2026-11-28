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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.openan.workflow.engine.model.Workflow;
import dev.openan.workflow.engine.model.WorkflowStep;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Where a host's durable queue lives when the host configures nothing.
 *
 * <p>Reporting is opted into by the center, not by the host, so a host must not have to name a
 * directory. Two host processes running as the same center account still share one reporting
 * identity, so an instance name has to separate their queues without breaking the replay that a
 * restart of that instance depends on.
 */
class CenterExecutionReportingSpoolTest {
  private static final String SOURCE = "urn:openan:host:identity-" + "f".repeat(64);

  @Test
  void theInstanceNameSeparatesQueuesUnderTheConfiguredRoot() throws IOException {
    String previousSpool = System.getProperty(CenterExecutionReporting.SPOOL_VARIABLE);
    String previousInstance = System.getProperty(CenterExecutionReporting.INSTANCE_VARIABLE);
    Path root = Files.createTempDirectory("openan-spool-root");
    try {
      System.setProperty(CenterExecutionReporting.SPOOL_VARIABLE, root.toString());
      System.clearProperty(CenterExecutionReporting.INSTANCE_VARIABLE);

      reportOnce();
      assertTrue(
          hasQueueUnder(root), "a host that configures nothing reports into the default root");

      System.setProperty(CenterExecutionReporting.INSTANCE_VARIABLE, "host-a");
      Path instance = root.resolve("host-a");
      reportOnce();
      assertTrue(
          hasQueueUnder(instance), "an instance name gives this process its own queue on its own");
    } finally {
      restore(CenterExecutionReporting.SPOOL_VARIABLE, previousSpool);
      restore(CenterExecutionReporting.INSTANCE_VARIABLE, previousInstance);
      deleteRecursively(root);
    }
  }

  @Test
  void twoInstancesNeverShareOneQueue() throws IOException {
    String previousSpool = System.getProperty(CenterExecutionReporting.SPOOL_VARIABLE);
    String previousInstance = System.getProperty(CenterExecutionReporting.INSTANCE_VARIABLE);
    Path root = Files.createTempDirectory("openan-spool-root");
    try {
      System.setProperty(CenterExecutionReporting.SPOOL_VARIABLE, root.toString());
      System.setProperty(CenterExecutionReporting.INSTANCE_VARIABLE, "host-a");
      reportOnce();
      System.setProperty(CenterExecutionReporting.INSTANCE_VARIABLE, "host-b");

      // A second process with the same account identity must still start, not fail on the lock.
      reportOnce();

      assertTrue(hasQueueUnder(root.resolve("host-a")));
      assertTrue(hasQueueUnder(root.resolve("host-b")));
      assertFalse(root.resolve("reporter-v1").toFile().exists(), "no queue fell back to the root");
    } finally {
      restore(CenterExecutionReporting.SPOOL_VARIABLE, previousSpool);
      restore(CenterExecutionReporting.INSTANCE_VARIABLE, previousInstance);
      deleteRecursively(root);
    }
  }

  /** Builds and closes one host's reporting session, which is what creates its queue. */
  private static void reportOnce() {
    var reporting =
        new CenterExecutionReporting(
            "http://127.0.0.1:15001", HttpAuthProvider.none(), TlsPolicy.insecure());
    try {
      reporting.forPsop(new LoadPsop.LoadedPsop(workflow(), SOURCE));
    } finally {
      reporting.close();
    }
  }

  /** Whether a queue was created under this spool root. */
  private static boolean hasQueueUnder(Path root) {
    Path queues = root.resolve("reporter-v1");
    if (!Files.isDirectory(queues)) return false;
    try (Stream<Path> namespaces = Files.list(queues)) {
      return namespaces.findFirst().isPresent();
    } catch (IOException error) {
      throw new UncheckedIOException(error);
    }
  }

  private static Workflow workflow() {
    Workflow workflow =
        Workflow.builder()
            .name("reported")
            .steps(List.of(WorkflowStep.builder().name("diagnose").build()))
            .build();
    workflow.setRevision("a".repeat(64));
    return workflow;
  }

  private static void restore(String key, String value) {
    if (value == null) {
      System.clearProperty(key);
    } else {
      System.setProperty(key, value);
    }
  }

  private static void deleteRecursively(Path root) {
    if (!Files.exists(root)) return;
    try (Stream<Path> paths = Files.walk(root)) {
      List<Path> all = new ArrayList<>(paths.toList());
      all.sort(Comparator.reverseOrder());
      for (Path path : all) {
        Files.deleteIfExists(path);
      }
    } catch (IOException error) {
      throw new UncheckedIOException(error);
    }
  }
}
