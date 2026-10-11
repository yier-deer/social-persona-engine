package com.socialpersona.config.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.socialpersona.event.scheduler.EventTriggerScheduler;
import com.socialpersona.persona.service.PersonaService;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * SystemConfigController 单元测试 —— 图片生成提供商配置端点
 *
 * 配置保存测试使用独立临时目录。
 */
public class SystemConfigControllerTest {

    private static final ObjectMapper objectMapper = new ObjectMapper();
    @TempDir Path configDir;
    private Path configPath;

    private MockMvc mockMvc;
    private SystemConfigController controller;
    private PersonaService personaService;

    @BeforeEach
    public void setUp() throws Exception {
        configPath = configDir.resolve("system_config.json");
        Map<String, Object> empty = new java.util.LinkedHashMap<>();
        Files.write(configPath, objectMapper.writeValueAsBytes(empty));

        personaService = org.mockito.Mockito.mock(PersonaService.class);
        EventTriggerScheduler eventScheduler = org.mockito.Mockito.mock(EventTriggerScheduler.class);
        controller = new SystemConfigController(personaService, eventScheduler);
        ReflectionTestUtils.setField(controller, "configFilePath", configPath.toString());
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
    public void testGetImageProviderNotConfigured() throws Exception {
        mockMvc.perform(get("/api/config/image-provider"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.hasImageKey").value(false))
                .andExpect(jsonPath("$.imageProvider").value(""));
    }

    @Test
    public void testSaveAndGetImageProvider() throws Exception {
        Map<String, String> body = Map.of(
                "imageProvider", "openai",
                "imageKey", "sk-test-key-12345",
                "imageBaseUrl", "https://api.openai.com/v1",
                "imageModel", "dall-e-3"
        );

        mockMvc.perform(post("/api/config/image-provider")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ok"))
                .andExpect(jsonPath("$.imageProvider").value("openai"));

        mockMvc.perform(get("/api/config/image-provider"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.hasImageKey").value(true))
                .andExpect(jsonPath("$.imageProvider").value("openai"))
                .andExpect(jsonPath("$.imageModel").value("dall-e-3"))
                .andExpect(jsonPath("$.imageBaseUrl").value("https://api.openai.com/v1"));
    }

    @Test
    public void testImageKeyNotStoredInPlaintext() throws Exception {
        Map<String, String> body = Map.of(
                "imageProvider", "openai",
                "imageKey", "my-secret-key-123"
        );

        mockMvc.perform(post("/api/config/image-provider")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isOk());

        String fileContent = Files.readString(configPath);
        org.junit.jupiter.api.Assertions.assertFalse(
                fileContent.contains("my-secret-key-123"),
                "明文 Key 不应出现在配置文件中"
        );
    }

    @Test
    public void testSaveImageProviderEmptyKey() throws Exception {
        Map<String, String> body = Map.of(
                "imageProvider", "openai",
                "imageKey", "",
                "imageBaseUrl", "",
                "imageModel", ""
        );

        mockMvc.perform(post("/api/config/image-provider")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/config/image-provider"))
                .andExpect(jsonPath("$.hasImageKey").value(false))
                .andExpect(jsonPath("$.imageProvider").value("openai"));
    }

    @Test
    public void testSaveImageProviderWithStandardFields() throws Exception {
        // ProviderSelector 发送的是标准字段名 (provider, apiKey, baseUrl, model)
        Map<String, String> body = Map.of(
                "provider", "openai",
                "apiKey", "sk-test-key",
                "baseUrl", "https://api.openai.com/v1",
                "model", "dall-e-3"
        );

        mockMvc.perform(post("/api/config/image-provider")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/config/image-provider"))
                .andExpect(jsonPath("$.hasImageKey").value(true))
                .andExpect(jsonPath("$.imageProvider").value("openai"));
    }

    @Test
    public void metadataOnlySavesPreserveBothApiKeys() throws Exception {
        controller.saveKey(Map.of("provider", "custom", "apiKey", "test-model-key"));
        controller.saveImageProvider(Map.of("provider", "custom_image", "apiKey", "test-image-key"));
        Map<?, ?> before = objectMapper.readValue(Files.readString(configPath), Map.class);

        controller.saveKey(Map.of("provider", "custom", "model", "new-model"));
        controller.saveImageProvider(Map.of("provider", "custom_image", "model", "new-image"));
        controller.saveKey(Map.of("provider", "custom", "apiKey", "  "));
        controller.saveImageProvider(Map.of("provider", "custom_image", "apiKey", ""));

        Map<?, ?> after = objectMapper.readValue(Files.readString(configPath), Map.class);
        org.junit.jupiter.api.Assertions.assertEquals(before.get("apiKeyEncrypted"), after.get("apiKeyEncrypted"));
        org.junit.jupiter.api.Assertions.assertEquals(before.get("imageApiKeyEncrypted"), after.get("imageApiKeyEncrypted"));
    }

    @Test
    public void writeFailureReturnsServerError() throws Exception {
        Path notDirectory = configDir.resolve("blocked-parent");
        Files.writeString(notDirectory, "occupied");
        ReflectionTestUtils.setField(controller, "configFilePath", notDirectory.resolve("system_config.json").toString());

        mockMvc.perform(post("/api/config/key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"provider\":\"custom\",\"apiKey\":\"test-key\"}"))
                .andExpect(status().isInternalServerError());
    }

    @Test
    public void malformedConfigIsNotSilentlyOverwritten() throws Exception {
        Files.writeString(configPath, "broken JSON");
        mockMvc.perform(post("/api/config/channels")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"qq\",\"account\":\"12345\"}"))
                .andExpect(status().isInternalServerError());
        org.junit.jupiter.api.Assertions.assertEquals("broken JSON", Files.readString(configPath));
    }

    @Test
    public void channelTypeCannotOverwriteCredentials() throws Exception {
        mockMvc.perform(post("/api/config/channels")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"apiKeyEncrypted\",\"account\":\"replacement\"}"))
                .andExpect(status().isBadRequest());
    }
}
