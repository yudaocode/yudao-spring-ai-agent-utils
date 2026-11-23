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
package org.springaicommunity.agent.tools;

import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.nio.charset.Charset;
import java.util.concurrent.atomic.AtomicInteger;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.compat.jdkhttp.HttpResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * Real HTTP byte decoding through the Java8 transport, without model or domain calls.
 *
 * @author 芋道源码
 */
class SmartWebFetchJava8Tests {

    @ParameterizedTest
    @ValueSource(strings = {"UTF-8", "ISO-8859-1"})
    void nativeTransportPreservesDeclaredCharset(String charset) throws Exception {
        assertThat(System.getProperty("java.specification.version")).isEqualTo("1.8");
        String expected = charset.equals("UTF-8") ? "汉字 café" : "café";
        byte[] bytes = expected.getBytes(Charset.forName(charset));
        AtomicInteger requests = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/page", exchange -> {
            requests.incrementAndGet();
            exchange.getResponseHeaders().set("Content-Type", "text/html; charset=" + charset);
            exchange.sendResponseHeaders(200, bytes.length);
            try (java.io.OutputStream output = exchange.getResponseBody()) {
                for (byte value : bytes) {
                    output.write(value);
                    output.flush();
                }
            }
            finally {
                exchange.close();
            }
        });
        server.start();
        ChatClient model = mock(ChatClient.class);
        try (SmartWebFetchTool tool = SmartWebFetchTool.builder(model).domainSafetyCheck(false).maxRetries(0).build()) {
            // The public API upgrades HTTP to HTTPS; directly exercise its real fetch boundary on loopback.
            Method fetch = SmartWebFetchTool.class.getDeclaredMethod("fetchHtml", String.class);
            fetch.setAccessible(true);
            @SuppressWarnings("unchecked")
            HttpResponse<String> response = (HttpResponse<String>) fetch.invoke(tool,
                    "http://127.0.0.1:" + server.getAddress().getPort() + "/page");
            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(response.body()).isEqualTo(expected);
            assertThat(response.headers().firstValue("Content-Type")).hasValue("text/html; charset=" + charset);
            assertThat(requests.get()).isEqualTo(1);
            verifyNoInteractions(model);
        }
        finally {
            server.stop(0);
        }
    }
}
