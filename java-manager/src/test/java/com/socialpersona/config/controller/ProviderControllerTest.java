package com.socialpersona.config.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.socialpersona.persona.service.PersonaService;
import org.junit.jupiter.api.*;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * ProviderController 单元测试 —— Type 过滤 + refreshModels fallback
 */
public class ProviderControllerTest {

    private static final ObjectMapper objectMapper = new ObjectMapper();
    private MockMvc mockMvc;
    private ProviderController controller;

    @BeforeEach
    public void setUp() {
        controller = new ProviderController();
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
    public void testGetAllProviders() throws Exception {
        mockMvc.perform(get("/api/config/providers"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(7))
                .andExpect(jsonPath("$[0].type").value("chat"))
                .andExpect(jsonPath("$[4].type").value("image"));
    }

    @Test
    public void testGetChatProviders() throws Exception {
        mockMvc.perform(get("/api/config/providers?type=chat"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(4))
                .andExpect(jsonPath("$[0].id").value("deepseek"))
                .andExpect(jsonPath("$[1].id").value("openai"))
                .andExpect(jsonPath("$[2].id").value("anthropic"))
                .andExpect(jsonPath("$[3].id").value("custom"));
    }

    @Test
    public void testGetImageProviders() throws Exception {
        mockMvc.perform(get("/api/config/providers?type=image"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(3))
                .andExpect(jsonPath("$[0].id").value("openai_image"))
                .andExpect(jsonPath("$[1].id").value("volcengine_image"))
                .andExpect(jsonPath("$[2].id").value("custom_image"));
    }

    @Test
    public void testChatListHasNoImageProviders() throws Exception {
        mockMvc.perform(get("/api/config/providers?type=chat"))
                .andExpect(jsonPath("$[?(@.id == 'openai_image')]").doesNotExist())
                .andExpect(jsonPath("$[?(@.id == 'volcengine_image')]").doesNotExist())
                .andExpect(jsonPath("$[?(@.id == 'custom_image')]").doesNotExist());
    }

    @Test
    public void testImageListHasNoChatProviders() throws Exception {
        mockMvc.perform(get("/api/config/providers?type=image"))
                .andExpect(jsonPath("$[?(@.id == 'deepseek')]").doesNotExist())
                .andExpect(jsonPath("$[?(@.id == 'openai')]").doesNotExist())
                .andExpect(jsonPath("$[?(@.id == 'anthropic')]").doesNotExist())
                .andExpect(jsonPath("$[?(@.id == 'custom')]").doesNotExist());
    }

    @Test
    public void testRefreshModelsOnCustomFallbackToBaseUrl() throws Exception {
        com.sun.net.httpserver.HttpServer server = com.sun.net.httpserver.HttpServer.create(
                new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/models", exchange -> {
            byte[] response = "{\"data\":[{\"id\":\"local-test-model\"}]}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);
            try (var output = exchange.getResponseBody()) { output.write(response); }
        });
        server.start();
        Map<String, String> body = Map.of(
                "apiKey", "sk-test",
                "baseUrl", "http://127.0.0.1:" + server.getAddress().getPort() + "/v1"
        );
        try {
            mockMvc.perform(post("/api/config/providers/custom_image/refresh-models")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.models[0].id").value("local-test-model"));
        } finally {
            server.stop(0);
        }
    }
}
