/*
 * Copyright 2023-present the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.springaicommunity.agent.common.task.subagent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import org.springframework.ai.util.json.schema.JsonSchemaGenerator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Native record/schema and interface-default checks.
 *
 * @author 芋道源码
 */
class AgentCommonJava8Tests {

	@Test
	void taskCallRetainsJsonAndToolParameterMetadata() throws Exception {
		ObjectMapper json = new ObjectMapper();
		TaskCall original = new TaskCall("分析", "你好", "research", "local", null, false);
		TaskCall restored = json.readValue(json.writeValueAsString(original), TaskCall.class);
		assertEquals(original, restored);
		assertEquals("你好", restored.prompt());
		JsonNode schema = json.readTree(JsonSchemaGenerator.generateForType(TaskCall.class));
		assertTrue(schema.path("properties").has("subagent_type"));
		assertTrue(schema.path("properties").path("prompt").path("description").asText().contains("task"));
		assertTrue(schema.path("required").toString().contains("prompt"));
		assertFalse(schema.path("required").toString().contains("run_in_background"));
	}

	@Test
	void definitionDefaultMethodFormatsUnicodeOnJava8() {
		SubagentDefinition definition = new SubagentDefinition() {
			public String getName() {
				return "研究";
			}

			public String getDescription() {
				return "文档";
			}

			public String getKind() {
				return "local";
			}

			public SubagentReference getReference() {
				return null;
			}
		};
		assertEquals("-研究: /文档", definition.toSubagentRegistrations());
	}

}
