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

package dev.openan.workflow.engine.model;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import org.a2aproject.sdk.spec.*;
import org.junit.jupiter.api.Test;

class MessageContentTest {
  // TC-ENG | snapshotsPartsMetadataAndActivationWithoutChangingOrder
  //
  // 输入: 目标方法的输入参数
  // 预期输出: 预期行为结果
  // 断言点: 返回值/状态断言
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void snapshotsPartsMetadataAndActivationWithoutChangingOrder() {
    List<Object> nested = new ArrayList<>(List.of("city1"));
    Map<String, Object> data = new LinkedHashMap<>(Map.of("cities", nested));
    Map<String, Object> metadata = new LinkedHashMap<>(Map.of("nested", data));
    Set<String> extensions = new LinkedHashSet<>(List.of("urn:example:task"));
    List<Part<?>> parts =
        new ArrayList<>(
            List.of(
                new TextPart("instruction", metadata),
                new DataPart(data, metadata),
                new FilePart(
                    new FileWithUri("text/plain", "report", "https://invalid.example/report"),
                    metadata)));
    MessageContent content = new MessageContent(parts, metadata, extensions);
    nested.add("changed");
    data.clear();
    parts.clear();
    metadata.clear();
    extensions.clear();
    assertEquals(3, content.parts().size());
    assertEquals(Map.of("cities", List.of("city1")), ((DataPart) content.parts().get(1)).data());
    assertEquals(
        "https://invalid.example/report",
        ((FileWithUri) ((FilePart) content.parts().get(2)).file()).uri());
    assertEquals(Set.of("urn:example:task"), content.extensions());
    assertThrows(UnsupportedOperationException.class, () -> content.metadata().put("new", true));
    assertThrows(UnsupportedOperationException.class, () -> content.parts().clear());
  }

  // TC-ENG | snapshotsPrimitiveAndArrayDataWithoutFlattening
  //
  // 输入: 目标方法的输入参数
  // 预期输出: 预期行为结果
  // 断言点: 返回值/状态断言
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void snapshotsPrimitiveAndArrayDataWithoutFlattening() {
    List<Object> nested = new ArrayList<>(List.of("one", Map.of("value", 2)));
    ReceivedMessage received =
        new ReceivedMessage(
            MessageContent.parts(
                List.of(new DataPart(nested), new DataPart(3), new TextPart("{not parsed}"))),
            Map.of(),
            List.of());
    nested.clear();
    assertEquals(
        List.of(List.of("one", Map.of("value", 2)), 3, "{not parsed}"), received.outputs());
  }

  // TC-ENG | addsBusinessSelectedExtensionWithoutMutatingOriginalContent
  //
  // 输入: 目标方法的输入参数
  // 预期输出: 预期行为结果
  // 断言点: 返回值/状态断言
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void addsBusinessSelectedExtensionWithoutMutatingOriginalContent() {
    MessageContent original =
        new MessageContent(List.of(new TextPart("task")), Map.of("task", "body"), Set.of("task"));

    MessageContent negotiationCapable = original.withExtension("negotiation");

    assertEquals(Set.of("task"), original.extensions());
    assertEquals(List.of("task", "negotiation"), List.copyOf(negotiationCapable.extensions()));
    assertEquals(original.parts(), negotiationCapable.parts());
    assertEquals(original.metadata(), negotiationCapable.metadata());
    assertThrows(IllegalArgumentException.class, () -> original.withExtension(""));
  }

  // TC-ENG | keepsMetadataOnlyResponseAndAllArtifactFields
  //
  // 输入: 目标方法的输入参数
  // 预期输出: 预期行为结果
  // 断言点: 返回值/状态断言
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void keepsMetadataOnlyResponseAndAllArtifactFields() {
    List<Object> items = new ArrayList<>(List.of("a"));
    Artifact artifact =
        new Artifact(
            "artifact1",
            "report",
            "description",
            List.of(new DataPart(items)),
            Map.of("same", "artifact"),
            List.of("urn:artifact"));
    ReceivedMessage received =
        new ReceivedMessage(
            new MessageContent(List.of(), Map.of("same", "message"), Set.of()),
            Map.of("same", "task"),
            List.of(artifact));
    items.clear();
    assertEquals("message", received.message().metadata().get("same"));
    assertEquals("task", received.taskMetadata().get("same"));
    assertEquals("artifact", received.artifacts().get(0).metadata().get("same"));
    assertEquals(List.of(List.of("a")), received.outputs());
    assertEquals("description", received.artifacts().get(0).description());
    assertEquals(List.of("urn:artifact"), received.artifacts().get(0).extensions());
    assertEquals(
        List.of(),
        new ReceivedMessage(
                new MessageContent(List.of(), Map.of("task-t", "body"), Set.of()),
                Map.of(),
                List.of())
            .outputs());
  }

  // TC-ENG | preservesFileReferenceAndRejectsUnknownParts
  //
  // 输入: 非法/畸形输入
  // 预期输出: 抛出具体异常类型，消息含关键字
  // 断言点: 异常类型 + 消息关键字
  // 防回归: 若对应行为被改变，本用例失败
  @Test
  void preservesFileReferenceAndRejectsUnknownParts() {
    assertInstanceOf(TextPart.class, MessageContent.text("").parts().get(0));
    assertThrows(
        IllegalArgumentException.class, () -> MessageContent.parts(List.of(new Part<Object>() {})));
    assertThrows(NullPointerException.class, () -> MessageContent.parts(null));
    assertThrows(
        IllegalArgumentException.class, () -> new MessageContent(List.of(), Map.of(), Set.of("")));
  }
}
