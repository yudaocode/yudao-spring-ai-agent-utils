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

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.session.Session;
import org.springframework.ai.session.SessionAttributes;
import org.springframework.ai.session.SessionService;
import org.springframework.ai.session.tool.SessionRecallTools;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.util.Assert;
import org.springframework.util.StringUtils;

/**
 * Adapts Dream recall to the current session metadata and read-only search API. The
 * principal is fixed by the application, never supplied by the model.
 *
 * @author 芋道源码
 * @since 2.1.0
 */
final class DreamSessionRecallTools {

	private static final int SESSIONS_PER_PAGE = 20;

	private final SessionService service;

	private final String principal;

	private final SessionRecallTools recall;

	private final ObjectMapper json = new ObjectMapper();

	DreamSessionRecallTools(SessionService service, String principal) {
		Assert.notNull(service, "sessionService must not be null");
		Assert.hasText(principal, "principal must not be empty");
		this.service = service;
		this.principal = principal;
		this.recall = SessionRecallTools.builder(service).build();
	}

	/**
	 * Searches a bounded page of the configured principal's sessions without writing.
	 * @since 2.1.0
	 */
	@Tool(name = "cross_session_search", description = "Read-only keyword search across the current user's sessions. "
			+ "The application fixes the user. Results are grouped by session, with per-session nextPage when more "
			+ "matches exist. At most 20 sessions are searched per call; nextSessionsPage continues the session list.")
	public String search(@ToolParam(description = "Keywords separated by commas") String query,
			@ToolParam(description = "Match any or all keywords; defaults to any", required = false) String match,
			@ToolParam(description = "Result page within each session, starting at zero",
					required = false) Integer page,
			@ToolParam(description = "Page of the user's sessions, starting at zero",
					required = false) Integer sessionsPage) {
		if (!StringUtils.hasText(query)) {
			return "Error: cross_session_search needs at least one keyword.";
		}
		if (StringUtils.hasText(match) && !"any".equalsIgnoreCase(match.trim())
				&& !"all".equalsIgnoreCase(match.trim())) {
			return "Error: match must be 'any' or 'all'.";
		}
		List<Session> sessions = this.service.findByMetadata(SessionAttributes.PRINCIPAL_NAME, this.principal)
			.stream()
			.filter(session -> this.principal.equals(SessionAttributes.principalName(session)))
			.sorted(Comparator.comparing(Session::id))
			.collect(Collectors.toList());
		int selectedPage = sessionsPage == null ? 0 : Math.max(0, sessionsPage);
		long start = (long) selectedPage * SESSIONS_PER_PAGE;
		int from = (int) Math.min(start, sessions.size());
		int to = (int) Math.min(start + SESSIONS_PER_PAGE, sessions.size());
		List<Map<String, Object>> results = new ArrayList<>();
		try {
			for (Session session : sessions.subList(from, to)) {
				String found = this.recall.conversationSearch(query, match, page, new ToolContext(
						Collections.singletonMap(SessionRecallTools.SESSION_ID_CONTEXT_KEY, session.id())));
				if (found.startsWith("Error:")) {
					return found;
				}
				if (found.startsWith("{")) {
					JsonNode search = this.json.readTree(found);
					Map<String, Object> result = new LinkedHashMap<>();
					result.put("sessionId", session.id());
					result.put("search", search);
					results.add(result);
				}
			}
			Map<String, Object> response = new LinkedHashMap<>();
			response.put("sessions", results);
			response.put("searchedSessions", to - from);
			if (to < sessions.size()) {
				response.put("nextSessionsPage", selectedPage + 1);
			}
			return this.json.writeValueAsString(response);
		}
		catch (IOException error) {
			throw new UncheckedIOException("Cannot encode session recall results", error);
		}
	}

}
