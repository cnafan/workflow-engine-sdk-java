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

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryNotEmptyException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.locks.ReentrantLock;

/**
 * The durable queue of reported events for one reporting source, kept as files on disk.
 *
 * <p>One directory holds the events the center has not confirmed yet, one file per event under a
 * folder named after the execution. Another directory holds a receipt for each execution the center
 * refused for good. Everything here is about files: what is safe to write, when it may be deleted,
 * and whether anything is left. What the events mean, how they reach the center and how long a host
 * waits for them belong to {@link ExecutionReporter}.
 *
 * <p>All state is guarded by one lock that is held only across file operations, never across a
 * network call. It never waits, so nothing here can hold the lock while blocked.
 */
final class EventSpool implements AutoCloseable {

  /** Receipts of refused executions kept for inspection; the oldest are dropped first. */
  private static final int MAX_REJECTED_RECEIPTS = 2048;

  /** Where events waiting for the center live, under the spool's own directory. */
  private static final String PENDING = "pending";

  /** Where receipts of refused executions live, under the spool's own directory. */
  private static final String REJECTED = "rejected";

  private static final String EVENT_SUFFIX = ".json";

  private final Path pending;
  private final Path rejected;
  private final long maxBytes;
  private final FileChannel ownership;
  private final FileLock ownershipLock;
  private final ReentrantLock guard = new ReentrantLock();

  /** Total size of the pending events; guarded by {@link #guard}. */
  private long bytes;

  /**
   * Opens the spool of one reporting source, creating it if needed.
   *
   * @param root directory holding the spools of every source this host reports as
   * @param namespace identity of the source, keeping its spool apart from the others
   * @param maxBytes ceiling on the pending events' total size
   * @throws IOException if a directory cannot be created, or another process already owns it
   */
  EventSpool(Path root, String namespace, long maxBytes) throws IOException {
    Path home = root.resolve("reporter-v1").resolve(namespace);
    this.pending = home.resolve(PENDING);
    this.rejected = home.resolve(REJECTED);
    this.maxBytes = maxBytes;
    Files.createDirectories(pending);
    ownership =
        FileChannel.open(
            home.resolve("owner.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
    try {
      ownershipLock = ownership.tryLock();
      if (ownershipLock == null) {
        throw new IOException("Execution spool namespace is already owned");
      }
      try (var files = Files.walk(pending, 2)) {
        for (Path file : files.filter(path -> path.toString().endsWith(EVENT_SUFFIX)).toList()) {
          bytes += Files.size(file);
        }
      }
    } catch (IOException | RuntimeException error) {
      ownership.close();
      if (error instanceof OverlappingFileLockException) {
        throw new IOException("Execution spool namespace is already owned", error);
      }
      throw error;
    }
  }

  /**
   * The directory name that keeps one source's events apart from every other source's.
   *
   * @param endpoint the center's execution-events URL
   * @param source the reporting identity
   */
  static String namespace(URI endpoint, String source) {
    try {
      byte[] identity = (endpoint.toASCIIString() + "\n" + source).getBytes(StandardCharsets.UTF_8);
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(identity));
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException(impossible);
    }
  }

  /** The name of the file holding the event with the given sequence number. */
  static String eventFileName(long seq) {
    return String.format(Locale.ROOT, "%016d" + EVENT_SUFFIX, seq);
  }

  /** The folder holding one execution's waiting events. */
  Path executionFolder(String executionId) {
    return pending.resolve(executionId);
  }

  /** The executions that have events waiting, sorted by name so the walk order is stable. */
  List<Path> pendingExecutions() throws IOException {
    try (var folders = Files.list(pending)) {
      return folders.filter(Files::isDirectory).sorted().toList();
    }
  }

  /**
   * The events of one execution that are waiting, in sequence order.
   *
   * <p>Callers that read the bytes must use {@link #readPending}; this listing alone races the
   * writer, and is kept for the paths that only delete what they list under the same lock.
   */
  List<Path> pendingEvents(Path execution) throws IOException {
    try (var files = Files.list(execution)) {
      return files.filter(path -> path.toString().endsWith(EVENT_SUFFIX)).sorted().toList();
    }
  }

  /** One batch of waiting events, read as a consistent snapshot. */
  record PendingBatch(List<Path> paths, List<byte[]> bodies) {}

  /**
   * Reads the oldest waiting events of one execution, up to the given limits.
   *
   * <p>The read happens under the same lock the writer takes, because a batch read while a file is
   * being replaced can observe a half-written file: on Windows that surfaces as an {@code
   * IOException} even though every individual file is published atomically. Holding the lock for
   * the read costs a writer at most one batch of I/O.
   */
  PendingBatch readPending(Path execution, int maxEvents, int maxBytes) throws IOException {
    guard.lock();
    try {
      var paths = new ArrayList<Path>();
      var bodies = new ArrayList<byte[]>();
      int bytes = 0;
      try (var files = Files.list(execution)) {
        for (Path file : files.filter(p -> p.toString().endsWith(EVENT_SUFFIX)).sorted().toList()) {
          if (paths.size() >= maxEvents) break;
          byte[] body = Files.readAllBytes(file);
          if (!paths.isEmpty() && bytes + body.length > maxBytes) break;
          paths.add(file);
          bodies.add(body);
          bytes += body.length;
        }
      }
      return new PendingBatch(paths, bodies);
    } finally {
      guard.unlock();
    }
  }

  /** Whether an event with this sequence number is already waiting for its execution. */
  boolean has(Path execution, long seq) {
    return Files.isRegularFile(execution.resolve(eventFileName(seq)));
  }

  /** Whether the center has refused this execution for good. */
  boolean isRejected(String executionId) {
    return Files.isRegularFile(rejected.resolve(executionId + EVENT_SUFFIX));
  }

  /** Whether anything of an execution is still waiting to be sent. */
  boolean isPending(String executionId) {
    return Files.isDirectory(pending.resolve(executionId));
  }

  /**
   * Deletes whatever is still waiting for an execution already refused. The receipt stays.
   *
   * <p>Events written after the refusal are leftovers of a run still in flight; they are dropped
   * without being sent, exactly like the ones that were refused.
   */
  void discardPendingOf(Path execution) throws IOException {
    guard.lock();
    try {
      deleteCounted(pendingEvents(execution));
      deleteFolderIfEmpty(execution);
    } finally {
      guard.unlock();
    }
  }

  /**
   * Writes one event durably: it is on disk before this returns, or not there at all.
   *
   * @param lifecycle whether the event is one a run cannot be reported without. Such events may use
   *     the whole quota, so that a full disk never loses the start or the end of a run.
   * @throws IOException if the write fails, or the quota leaves no room for the event
   */
  void persist(Path file, byte[] event, boolean lifecycle) throws IOException {
    guard.lock();
    try {
      writeWithinQuota(file, event, lifecycle);
    } finally {
      guard.unlock();
    }
  }

  /**
   * Writes one event unless the center has already refused its execution for good.
   *
   * <p>The check and the write happen under one lock, so an event can never land after its
   * execution's refusal was recorded: once refused, everything else of that execution is dropped
   * rather than sent.
   *
   * @return whether the event was written
   */
  boolean persistUnlessRejected(Path file, String executionId, byte[] event, boolean lifecycle)
      throws IOException {
    guard.lock();
    try {
      if (isRejected(executionId)) return false;
      writeWithinQuota(file, event, lifecycle);
      return true;
    } finally {
      guard.unlock();
    }
  }

  /** The write itself, with the quota check that guards it. The caller holds {@link #guard}. */
  private void writeWithinQuota(Path file, byte[] event, boolean lifecycle) throws IOException {
    long previous = Files.exists(file) ? Files.size(file) : 0;
    long ceiling = lifecycle ? maxBytes : maxBytes - maxBytes / 10;
    if (bytes + event.length - previous > ceiling) {
      throw new IOException("Execution observation spool quota exceeded");
    }
    // Every spool file is "<pending>/<execution>/<seq>.json"; a bare file name is a caller bug.
    Path folder = Objects.requireNonNull(file.getParent(), "spool file without a directory");
    Files.createDirectories(folder);
    Path temporary = Files.createTempFile(folder, "event-", ".tmp");
    try {
      try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.WRITE)) {
        ByteBuffer buffer = ByteBuffer.wrap(event);
        while (buffer.hasRemaining()) channel.write(buffer);
        channel.force(true);
      }
      Files.move(
          temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
      bytes += event.length - previous;
    } finally {
      Files.deleteIfExists(temporary);
    }
  }

  /** Deletes the events the center has taken, and their execution's folder once it is empty. */
  void acknowledge(List<Path> events) throws IOException {
    if (events.isEmpty()) return;
    guard.lock();
    try {
      deleteCounted(events);
      deleteFolderIfEmpty(events.get(0).getParent());
    } finally {
      guard.unlock();
    }
  }

  /**
   * Sets an execution the center will never accept aside, and records why.
   *
   * <p>Its pending events are deleted and a receipt holding {@code reason} is written, so the
   * refusal can be inspected later without the events ever being sent again.
   */
  void reject(Path execution, String reason) throws IOException {
    guard.lock();
    try {
      Files.createDirectories(rejected);
      Path receipt = rejected.resolve(execution.getFileName() + EVENT_SUFFIX);
      if (!Files.exists(receipt)) {
        Files.writeString(receipt, reason, StandardOpenOption.CREATE_NEW);
      }
      deleteCounted(pendingEvents(execution));
      deleteFolderIfEmpty(execution);
      trimReceipts();
    } finally {
      guard.unlock();
    }
  }

  /** Whether no event is waiting on disk right now. A snapshot, not a wait. */
  boolean isEmpty() {
    guard.lock();
    try {
      return bytes == 0;
    } finally {
      guard.unlock();
    }
  }

  /** Releases the spool so that another process of the same host may own it. */
  @Override
  public void close() {
    try {
      ownershipLock.release();
    } catch (IOException ignored) {
      // The channel is closing anyway; the lock goes with it.
    }
    try {
      ownership.close();
    } catch (IOException error) {
      throw new UncheckedIOException(error);
    }
  }

  /** Deletes events and takes their size off the quota. The caller holds {@link #guard}. */
  private void deleteCounted(List<Path> events) throws IOException {
    for (Path event : events) {
      if (!Files.exists(event)) continue;
      long size = Files.size(event);
      Files.delete(event);
      bytes -= size;
    }
  }

  /**
   * Removes an execution's folder once nothing waits in it.
   *
   * <p>A {@code null} folder means a relative single-segment path, which has no folder to remove. A
   * folder that is not empty has a newer event staged, and is left for that event.
   */
  private static void deleteFolderIfEmpty(Path folder) throws IOException {
    if (folder == null) return;
    try {
      Files.deleteIfExists(folder);
    } catch (DirectoryNotEmptyException laterEventAlreadyStaged) {
      // Nothing to do: the execution is still being reported.
    }
  }

  /** Keeps only the newest receipts. The caller holds {@link #guard}. */
  private void trimReceipts() throws IOException {
    try (var receipts = Files.list(rejected)) {
      var oldestFirst =
          receipts
              .filter(path -> path.toString().endsWith(EVENT_SUFFIX))
              .sorted(Comparator.comparingLong(path -> path.toFile().lastModified()))
              .toList();
      for (int i = 0; i < oldestFirst.size() - MAX_REJECTED_RECEIPTS; i++) {
        Files.deleteIfExists(oldestFirst.get(i));
      }
    }
  }
}
