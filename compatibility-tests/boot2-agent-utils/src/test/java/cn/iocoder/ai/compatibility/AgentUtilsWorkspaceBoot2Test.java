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
package cn.iocoder.ai.compatibility;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import org.springaicommunity.agent.common.exec.ExecBackend;
import org.springaicommunity.agent.common.workspace.Workspace;
import org.springaicommunity.agent.exec.LocalExecBackend;
import org.springaicommunity.agent.exec.docker.DockerCliExecBackend;
import org.springaicommunity.agent.tools.FileSystemTools;
import org.springaicommunity.agent.tools.ShellTools;

import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.boot.SpringBootVersion;
import org.springframework.core.SpringVersion;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the installed artifacts through Spring AI's tool callback API on Boot 2.
 *
 * @author 芋道源码
 */
@Timeout(60)
class AgentUtilsWorkspaceBoot2Test {

	@TempDir
	Path temporaryDirectory;

	@Test
	void fileAndShellCallbacksUseTheLocalWorkspace() throws Exception {
		assertRuntime();
		Path root = Files.createDirectory(this.temporaryDirectory.resolve("workspace"));
		Workspace workspace = Workspace.local(root);
		ExecBackend backend = LocalExecBackend.builder().workingDirectory(workspace.root()).build();
		verifyCallbacks(workspace, backend);
	}

	@Test
	@EnabledIfSystemProperty(named = "compat.agent-utils.docker.tests", matches = "true")
	void fileAndShellCallbacksShareTheDockerMountAndCloseRemovesTheContainer() throws Exception {
		assertRuntime();
		Path root = Files.createDirectory(this.temporaryDirectory.resolve("docker workspace")).toRealPath();
		String containerId;
		try (DockerCliExecBackend backend = DockerCliExecBackend.builder()
			.image("alpine:3.20")
			.mount(root, "/workspace")
			.build()) {
			containerId = backend.containerId();
			Workspace workspace = backend.workspace();
			assertEquals("/workspace/note.txt", workspace.display(root.resolve("note.txt")));
			verifyCallbacks(workspace, backend);
			String output = call(callbacks(workspace, backend).get("Bash"),
					"command", "pwd; . /etc/os-release; printf '%s' \"$ID\"");
			assertTrue(output.contains("/workspace"), output);
			assertTrue(output.contains("alpine"), output);
		}
		Process list = new ProcessBuilder("docker", "ps", "-aq", "--filter", "id=" + containerId)
			.redirectErrorStream(true)
			.start();
		try (BufferedReader output = new BufferedReader(
				new InputStreamReader(list.getInputStream(), StandardCharsets.UTF_8))) {
			assertTrue(list.waitFor(10, TimeUnit.SECONDS), "Docker container lookup timed out");
			assertEquals(0, list.exitValue());
			assertNull(output.readLine(), "Managed container still exists");
		}
		finally {
			list.destroy();
		}
	}

	private void verifyCallbacks(Workspace workspace, ExecBackend backend) throws IOException {
		Map<String, ToolCallback> tools = callbacks(workspace, backend);
		Path file = workspace.root().resolve("note.txt");
		ToolCallback write = tools.get("Write");
		assertTrue(write.getToolDefinition().inputSchema().contains("\"filePath\""));
		String output = call(write, "filePath", "note.txt", "content", "first 你好");
		assertFalse(output.contains("Error:"), output);
		assertEquals("first 你好", new String(Files.readAllBytes(file), StandardCharsets.UTF_8));

		output = call(tools.get("Read"), "filePath", "note.txt");
		assertTrue(output.contains("first 你好"), output);
		output = call(tools.get("Edit"), "filePath", "note.txt", "old_string", "first", "new_string", "second");
		assertFalse(output.contains("Error:"), output);
		output = call(tools.get("Bash"), "command", "cat note.txt");
		assertTrue(output.contains("second 你好"), output);

		output = call(tools.get("Bash"), "command", "printf 'from shell' > note.txt");
		assertFalse(output.contains("Error:"), output);
		output = call(tools.get("Read"), "filePath", "note.txt");
		assertTrue(output.contains("from shell"), output);

		output = call(write, "filePath", "../outside.txt", "content", "must not escape");
		assertTrue(output.contains("Error:"), output);
		assertFalse(Files.exists(this.temporaryDirectory.resolve("outside.txt")));
	}

	private Map<String, ToolCallback> callbacks(Workspace workspace, ExecBackend backend) {
		return Arrays.stream(ToolCallbacks.from(FileSystemTools.builder().workspace(workspace).build(),
				ShellTools.builder().execBackend(backend).build()))
			.collect(Collectors.toMap(tool -> tool.getToolDefinition().name(), Function.identity()));
	}

	private String call(ToolCallback tool, String... arguments) throws IOException {
		assertNotNull(tool);
		ObjectMapper mapper = new ObjectMapper();
		ObjectNode input = mapper.createObjectNode();
		for (int index = 0; index < arguments.length; index += 2) {
			input.put(arguments[index], arguments[index + 1]);
		}
		return tool.call(mapper.writeValueAsString(input));
	}

	private void assertRuntime() {
		assertEquals("1.8", System.getProperty("java.specification.version"));
		assertEquals("2.7.18", SpringBootVersion.getVersion());
		assertTrue(SpringVersion.getVersion().startsWith("5.3."));
	}

}
