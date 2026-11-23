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

import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.springaicommunity.agent.dream.AutoDreamService;
import org.springaicommunity.agent.dream.DreamResult;
import org.springaicommunity.agent.dream.DreamState;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.session.CreateSessionRequest;
import org.springframework.ai.session.DefaultSessionService;
import org.springframework.ai.session.InMemorySessionRepository;
import org.springframework.ai.session.SessionAttributes;
import org.springframework.ai.session.SessionService;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Runs Dream through a real Boot 2 context, session store and HTTP tool-call protocol.
 *
 * @author 芋道源码
 */
@Timeout(30)
class AgentUtilsBoot2Test {

	@TempDir
	Path directory;

	@Test
	void dreamRecallsOnlyTheConfiguredPrincipalAndPreservesHistory() throws Exception {
		assertEquals("1.8", System.getProperty("java.specification.version"));
		try (MockWebServer server = new MockWebServer()) {
			server.start();
			server.enqueue(response(
					"{\"role\":\"assistant\",\"content\":null,\"tool_calls\":[{\"id\":\"recall-1\",\"type\":\"function\",\"function\":{\"name\":\"cross_session_search\",\"arguments\":\"{\\\"query\\\":\\\"budget\\\"}\"}}]}",
					"tool_calls"));
			server.enqueue(response("{\"role\":\"assistant\",\"content\":\"已整理\"}", "stop"));
			try (ConfigurableApplicationContext context = new SpringApplicationBuilder(App.class)
				.web(WebApplicationType.NONE)
				.properties("spring.main.banner-mode=off", "logging.level.root=WARN",
						"compat.provider.url=" + server.url("/"))
				.run()) {
				SessionService sessions = context.getBean(SessionService.class);
				sessions.create(CreateSessionRequest.builder()
					.id("alice-session")
					.metadata(SessionAttributes.PRINCIPAL_NAME, "alice")
					.build());
				sessions.create(CreateSessionRequest.builder()
					.id("bob-session")
					.metadata(SessionAttributes.PRINCIPAL_NAME, "bob")
					.build());
				sessions.appendMessage("alice-session", new UserMessage("budget 你好 alice-only"));
				sessions.appendMessage("bob-session", new UserMessage("budget bob-secret"));
				long aliceVersion = sessions.getEventVersion("alice-session");
				long bobVersion = sessions.getEventVersion("bob-session");
				DreamResult result = context.getBean(AutoDreamService.class)
					.runDreamCycle(directory.toString(), "alice");
				assertEquals("completed", result.status());
				assertEquals("已整理", result.summary());
				assertEquals("completed", DreamState.load(directory).lastDreamStatus());
				assertEquals(aliceVersion, sessions.getEventVersion("alice-session"));
				assertEquals(bobVersion, sessions.getEventVersion("bob-session"));
				assertNotNull(server.takeRequest(5, TimeUnit.SECONDS));
				okhttp3.mockwebserver.RecordedRequest followup = server.takeRequest(5, TimeUnit.SECONDS);
				assertNotNull(followup);
				JsonNode messages = new ObjectMapper().readTree(followup.getBody().readUtf8()).path("messages");
				String recall = "";
				for (JsonNode message : messages) {
					if ("tool".equals(message.path("role").asText())) {
						recall = message.path("content").asText();
					}
				}
				assertTrue(recall.contains("alice-only"), recall);
				assertTrue(recall.contains("你好"), recall);
				assertFalse(recall.contains("bob-secret"), recall);
				assertEquals(2, server.getRequestCount());
			}
		}
	}

	private MockResponse response(String message, String reason) {
		return new MockResponse().setHeader("Content-Type", "application/json; charset=UTF-8")
			.setBody("{\"id\":\"local\",\"object\":\"chat.completion\",\"created\":1,\"model\":\"fixture\","
					+ "\"choices\":[{\"index\":0,\"message\":" + message + ",\"finish_reason\":\"" + reason
					+ "\"}],\"usage\":{\"prompt_tokens\":2,\"completion_tokens\":1,\"total_tokens\":3}}");
	}

	/**
	 * @author 芋道源码
	 */
	@SpringBootConfiguration
	@EnableAutoConfiguration
	static class App {

		@Bean
		ChatModel model(Environment environment) {
			return OpenAiChatModel.builder()
				.options(OpenAiChatOptions.builder()
					.baseUrl(environment.getRequiredProperty("compat.provider.url") + "v1")
					.apiKey("local-fixture")
					.model("fixture")
					.maxRetries(0)
					.timeout(Duration.ofSeconds(5))
					.build())
				.build();
		}

		@Bean
		SessionService sessions() {
			return DefaultSessionService.builder()
				.sessionRepository(InMemorySessionRepository.builder().build())
				.build();
		}

		@Bean
		AutoDreamService dreamer(ChatModel model, SessionService sessions) {
			return AutoDreamService.builder(ChatClient.builder(model)).sessionService(sessions).build();
		}

	}

}
