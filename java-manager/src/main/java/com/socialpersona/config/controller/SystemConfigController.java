package com.socialpersona.config.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.socialpersona.event.scheduler.EventTriggerScheduler;
import com.socialpersona.persona.service.PersonaService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.bind.annotation.*;

import java.io.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

@RestController
@RequestMapping("/api/config")
public class SystemConfigController {

    private static final Logger log = LoggerFactory.getLogger(SystemConfigController.class);
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final PersonaService personaService;
    private final EventTriggerScheduler eventTriggerScheduler;

    public SystemConfigController(PersonaService personaService,
                                   EventTriggerScheduler eventTriggerScheduler) {
        this.personaService = personaService;
        this.eventTriggerScheduler = eventTriggerScheduler;
    }

    @Value("${app.config-file:./data/system_config.json}")
    private String configFilePath = "./data/system_config.json";

    @GetMapping("/key")
    public Map<String, Object> getKeyConfig() {
        Map<String, Object> cfg = readConfig();
        Map<String, Object> masked = new LinkedHashMap<>();
        masked.put("provider", cfg.getOrDefault("provider", ""));
        masked.put("hasKey", cfg.containsKey("apiKeyEncrypted") && !cfg.get("apiKeyEncrypted").toString().isEmpty());
        masked.put("baseUrl", cfg.getOrDefault("baseUrl", ""));
        masked.put("model", cfg.getOrDefault("model", "deepseek-chat"));
        return masked;
    }

    @PostMapping("/key")
    public synchronized Map<String, String> saveKey(@RequestBody Map<String, String> body) {
        String provider = body.getOrDefault("provider", "deepseek");
        String apiKey = body.get("apiKey");
        String baseUrl = body.getOrDefault("baseUrl", "https://api.deepseek.com/v1");
        String model = body.getOrDefault("model", "deepseek-chat");

        Map<String, Object> cfg = readConfig();
        cfg.put("provider", provider);
        if (apiKey != null && !apiKey.isBlank()) {
            cfg.put("apiKeyEncrypted", Base64.getEncoder().encodeToString(apiKey.trim().getBytes(StandardCharsets.UTF_8)));
        }
        cfg.put("baseUrl", baseUrl);
        cfg.put("model", model);
        writeConfig(cfg);

        log.info("API Key 已保存: provider={}", provider);
        return Map.of("status", "ok", "provider", provider);
    }

    @GetMapping("/channels")
    public Map<String, Object> getChannels() {
        Map<String, Object> cfg = readConfig();
        Map<String, Object> channels = new LinkedHashMap<>();
        channels.put("qq", cfg.getOrDefault("qq", ""));
        channels.put("wechat", cfg.getOrDefault("wechat", ""));
        return channels;
    }

    @PostMapping("/channels")
    public synchronized Map<String, String> saveChannel(@RequestBody Map<String, String> body) {
        String type = body.get("type");
        String account = body.get("account");
        if (!Set.of("qq", "wechat").contains(type != null ? type : "")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "不支持的通信渠道");
        }

        Map<String, Object> cfg = readConfig();
        cfg.put(type, account != null ? account.trim() : "");
        writeConfig(cfg);

        if ("qq".equals(type) && account != null && !account.isEmpty()) {
            int updated = personaService.setAllOwnerQq(account.trim());
            log.info("设置页 QQ 已更新，同步 {} 个 AI 的 owner_qq={}", updated, account.trim());
        }

        return Map.of("status", "ok", "type", type);
    }

    // ==================== 图片生成提供商 ====================

    @GetMapping("/image-provider")
    public Map<String, Object> getImageProvider() {
        Map<String, Object> cfg = readConfig();
        Map<String, Object> masked = new LinkedHashMap<>();
        masked.put("imageProvider", cfg.getOrDefault("imageProvider", ""));
        masked.put("hasImageKey", cfg.containsKey("imageApiKeyEncrypted")
                && !cfg.get("imageApiKeyEncrypted").toString().isEmpty());
        masked.put("imageBaseUrl", cfg.getOrDefault("imageBaseUrl", ""));
        masked.put("imageModel", cfg.getOrDefault("imageModel", ""));
        return masked;
    }

    @PostMapping("/image-provider")
    public synchronized Map<String, String> saveImageProvider(@RequestBody Map<String, String> body) {
        String imageProvider = body.getOrDefault("imageProvider",
                body.getOrDefault("provider", ""));
        String imageKey = body.getOrDefault("imageKey",
                body.getOrDefault("apiKey", ""));
        String imageBaseUrl = body.getOrDefault("imageBaseUrl",
                body.getOrDefault("baseUrl", ""));
        String imageModel = body.getOrDefault("imageModel",
                body.getOrDefault("model", ""));

        Map<String, Object> cfg = readConfig();
        cfg.put("imageProvider", imageProvider);
        if (imageKey != null && !imageKey.isBlank()) {
            cfg.put("imageApiKeyEncrypted", Base64.getEncoder().encodeToString(imageKey.trim().getBytes(StandardCharsets.UTF_8)));
        }
        cfg.put("imageBaseUrl", imageBaseUrl);
        cfg.put("imageModel", imageModel);
        writeConfig(cfg);

        log.info("图片生成配置已保存: provider={}", imageProvider);
        return Map.of("status", "ok", "imageProvider", imageProvider);
    }

    // ==================== 测试：插入 N 秒后触发的事件 ====================

    @PostMapping("/test-event")
    public Map<String, Object> insertTestEvent(@RequestBody Map<String, String> body) {
        String personaId = body.getOrDefault("personaId", "");
        int delaySeconds = Integer.parseInt(body.getOrDefault("delaySeconds", "10"));

        if (personaId.isEmpty()) {
            return Map.of("success", false, "error", "缺少 personaId");
        }

        var event = eventTriggerScheduler.insertTestEvent(personaId, delaySeconds);
        return Map.of(
                "success", true,
                "eventId", event.getId(),
                "eventTime", event.getEventTime(),
                "eventType", event.getEventType(),
                "description", event.getDescription()
        );
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> readConfig() {
        try {
            Path path = Path.of(configFilePath);
            if (!Files.exists(path)) return new LinkedHashMap<>();
            return objectMapper.readValue(Files.readString(path), Map.class);
        } catch (Exception e) {
            log.error("读取配置失败: {}", e.getClass().getSimpleName());
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "无法读取配置文件");
        }
    }

    private void writeConfig(Map<String, Object> cfg) {
        Path temporary = null;
        try {
            Path path = Path.of(configFilePath).toAbsolutePath();
            Files.createDirectories(path.getParent());
            temporary = Files.createTempFile(path.getParent(), "system_config-", ".tmp");
            Files.writeString(temporary, objectMapper.writeValueAsString(cfg), StandardCharsets.UTF_8);
            try {
                Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            log.error("写入配置失败: {}", e.getClass().getSimpleName());
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "无法保存配置文件");
        } finally {
            if (temporary != null) {
                try { Files.deleteIfExists(temporary); }
                catch (IOException ignored) { log.warn("配置临时文件清理失败"); }
            }
        }
    }
}
