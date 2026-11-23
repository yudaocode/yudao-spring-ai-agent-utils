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
package org.springaicommunity.agent.dream;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springaicommunity.agent.tools.CrossSessionRecallTools;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.session.EventQuery;
import org.springframework.ai.session.Session;
import org.springframework.ai.session.SessionAttributes;
import org.springframework.ai.session.SessionEvent;
import org.springframework.ai.session.SessionService;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.core.io.ClassPathResource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/**
 * Exercises Dream state, principal isolation and reflective tool calls on the target VM.
 *
 * @author 芋道源码
 */
class DreamJava8Tests {

	@TempDir
	Path directory;

	private final ObjectMapper json = new ObjectMapper();

	private Session session(String id, String owner) {
		return Session.builder().id(id).metadata(SessionAttributes.PRINCIPAL_NAME, owner).build();
	}

	@Test
	void recallToolIsReadOnlyAndCannotSelectAnotherPrincipal() throws Exception {
		SessionService service = mock(SessionService.class);
		when(service.findByMetadata(SessionAttributes.PRINCIPAL_NAME, "alice"))
			.thenReturn(java.util.Arrays.asList(session("a", "alice"), session("b", "bob")));
		when(service.searchEvents(eq("a"), any(EventQuery.class))).thenReturn(Collections
			.singletonList(SessionEvent.builder().sessionId("a").message(new UserMessage("budget 你好")).build()));
		DreamSessionRecallTools tool = new DreamSessionRecallTools(service, "alice");
		ToolCallback callback = ToolCallbacks.from(tool)[0];
		assertEquals("cross_session_search", callback.getToolDefinition().name());
		JsonNode schema = json.readTree(callback.getToolDefinition().inputSchema());
		assertFalse(schema.path("properties").has("userId"));
		assertFalse(schema.path("properties").has("sessionId"));
		JsonNode result = json.readTree(callback.call("{\"query\":\"budget\"}"));
		assertEquals(1, result.path("sessions").size());
		assertEquals("a", result.path("sessions").get(0).path("sessionId").asText());
		assertTrue(result.toString().contains("你好"));
		verify(service).findByMetadata(SessionAttributes.PRINCIPAL_NAME, "alice");
		verify(service).searchEvents(eq("a"), any(EventQuery.class));
		verifyNoMoreInteractions(service);
	}

	@Test
	void communityRecallChecksOwnershipBeforeSearching() throws Exception {
		SessionService service = mock(SessionService.class);
		when(service.findByMetadata(SessionAttributes.PRINCIPAL_NAME, "alice"))
			.thenReturn(java.util.Arrays.asList(session("owned", "alice"), session("foreign", "bob")));
		when(service.searchEvents(eq("owned"), any(EventQuery.class))).thenReturn(Collections
			.singletonList(SessionEvent.builder().sessionId("owned").message(new UserMessage("budget")).build()));
		ToolCallback callback = ToolCallbacks.from(CrossSessionRecallTools.builder(service, "alice").build())[0];
		JsonNode result = json.readTree(callback.call("{\"query\":\"budget\"}"));
		assertEquals(1, result.path("results").size());
		assertEquals("owned", result.path("results").get(0).path("sessionId").asText());
		verify(service).findByMetadata(SessionAttributes.PRINCIPAL_NAME, "alice");
		verify(service).searchEvents(eq("owned"), any(EventQuery.class));
		verifyNoMoreInteractions(service);
	}

	@Test
	void recallBoundsTheSessionPageAndPreservesPerSessionQuery() throws Exception {
		SessionService service = mock(SessionService.class);
		List<Session> sessions = new ArrayList<>();
		for (int i = 0; i < 21; i++)
			sessions.add(session(String.format("s%02d", i), "alice"));
		when(service.findByMetadata(SessionAttributes.PRINCIPAL_NAME, "alice")).thenReturn(sessions);
		when(service.searchEvents(eq("s20"), any(EventQuery.class))).thenReturn(Collections.emptyList());
		JsonNode result = json.readTree(new DreamSessionRecallTools(service, "alice").search("budget", "all", 1, 1));
		assertEquals(1, result.path("searchedSessions").asInt());
		assertFalse(result.has("nextSessionsPage"));
		verify(service).findByMetadata(SessionAttributes.PRINCIPAL_NAME, "alice");
		ArgumentCaptor<EventQuery> query = ArgumentCaptor.forClass(EventQuery.class);
		verify(service).searchEvents(eq("s20"), query.capture());
		assertEquals(1, query.getValue().page());
		assertEquals(EventQuery.MatchMode.ALL, query.getValue().matchMode());
		verifyNoMoreInteractions(service);
	}

	@Test
	void invalidSearchAndBlankPrincipalDoNotReadHistory() {
		SessionService service = mock(SessionService.class);
		assertThrows(IllegalArgumentException.class, () -> new DreamSessionRecallTools(service, " "));
		DreamSessionRecallTools tool = new DreamSessionRecallTools(service, "alice");
		assertTrue(tool.search(" ", null, null, null).startsWith("Error:"));
		assertTrue(tool.search("budget", "bad", null, null).startsWith("Error:"));
		verifyNoInteractions(service);
	}

	@Test
	void firstPageIsBoundedAndReportsContinuation() throws Exception {
		SessionService service = mock(SessionService.class);
		List<Session> sessions = new ArrayList<>();
		for (int i = 20; i >= 0; i--) {
			sessions.add(session(String.format("s%02d", i), "alice"));
		}
		when(service.findByMetadata(SessionAttributes.PRINCIPAL_NAME, "alice")).thenReturn(sessions);
		when(service.searchEvents(any(String.class), any(EventQuery.class))).thenReturn(Collections.emptyList());
		JsonNode result = json.readTree(new DreamSessionRecallTools(service, "alice").search("budget", null, null, 0));
		assertEquals(20, result.path("searchedSessions").asInt());
		assertEquals(1, result.path("nextSessionsPage").asInt());
		verify(service).findByMetadata(SessionAttributes.PRINCIPAL_NAME, "alice");
		for (int i = 0; i < 20; i++) {
			verify(service).searchEvents(eq(String.format("s%02d", i)), any(EventQuery.class));
		}
		verifyNoMoreInteractions(service);
	}

	@Test
	void oversizedSessionPageDoesNotOverflowOrSearchHistory() throws Exception {
		SessionService service = mock(SessionService.class);
		when(service.findByMetadata(SessionAttributes.PRINCIPAL_NAME, "alice"))
			.thenReturn(Collections.singletonList(session("a", "alice")));
		JsonNode result = json
			.readTree(new DreamSessionRecallTools(service, "alice").search("budget", null, null, Integer.MAX_VALUE));
		assertEquals(0, result.path("searchedSessions").asInt());
		assertFalse(result.has("nextSessionsPage"));
		verify(service).findByMetadata(SessionAttributes.PRINCIPAL_NAME, "alice");
		verifyNoMoreInteractions(service);
	}

	@Test
	void officialDefaultPromptAndSubagentResourcesArePackaged() throws Exception {
		String[] resources = { "prompt/AUTO_DREAM_SYSTEM_PROMPT.md", "prompt/MAIN_AGENT_SYSTEM_PROMPT_ORIGINAL.md",
				"prompt/AUTO_MEMORY_TOOLS_SYSTEM_PROMPT.md", "prompt/MAIN_AGENT_SYSTEM_PROMPT_V2.md",
				"prompt/AUTO_MEMORY_FILESYSTEM_TOOLS_SYSTEM_PROMPT.md", "agent/EXPLORE_SUBAGENT.md",
				"agent/PLAN_SUBAGENT.md", "agent/BASH_SUBAGENT.md", "agent/GENERAL_PURPOSE_SUBAGENT.md" };
		for (String name : resources) {
			try (java.io.InputStream input = new ClassPathResource(name).getInputStream()) {
				assertTrue(input.read() >= 0, name);
			}
		}
	}

	@Test
	void dreamCyclePersistsStateAndReleasesItsLock() {
		ChatModel model = mock(ChatModel.class);
		when(model.getOptions()).thenReturn(ToolCallingChatOptions.builder().build());
		when(model.call(any(Prompt.class)))
			.thenReturn(new ChatResponse(Collections.singletonList(new Generation(new AssistantMessage("已整理")))));
		AutoDreamService service = AutoDreamService.builder(ChatClient.builder(model)).build();
		DreamResult result = service.runDreamCycle(directory.toString());
		assertEquals("completed", result.status());
		assertEquals("已整理", result.summary());
		assertTrue(Files.exists(directory.resolve(DreamState.STATE_FILE_NAME)));
		try (DreamLock lock = DreamLock.tryAcquire(directory, Duration.ofMinutes(10)).get()) {
			assertFalse(DreamLock.tryAcquire(directory, Duration.ofMinutes(10)).isPresent());
		}
	}

}
