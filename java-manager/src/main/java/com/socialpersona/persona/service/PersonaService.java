package com.socialpersona.persona.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.socialpersona.crypto.AESGCMUtil;
import com.socialpersona.persona.dto.ApiConfigDTO;
import com.socialpersona.persona.dto.PersonaConfigDTO;
import com.socialpersona.persona.entity.CharacterLifeArchive;
import com.socialpersona.persona.entity.Persona;
import com.socialpersona.persona.repository.CharacterLifeArchiveMapper;
import com.socialpersona.persona.repository.PersonaMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.CacheEvict;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Value;

import jakarta.annotation.PostConstruct;

@Service
public class PersonaService {

    private static final Logger log = LoggerFactory.getLogger(PersonaService.class);

    @Autowired
    private PersonaMapper personaMapper;

    @Autowired
    private CharacterLifeArchiveMapper lifeArchiveMapper;

    @Autowired
    private CacheManager cacheManager;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${app.image-dir:data/generated_images}")
    private String imageBaseDir;

    @Value("${app.config-file:./data/system_config.json}")
    private String configFilePath = "./data/system_config.json";

    /**
     * 启动时清理孤儿图片目录（DB中已不存在的persona对应的目录）
     * 目录名为 UUID 前8位，匹配 DB 中 persona.id 的前8位
     */
    @PostConstruct
    public void cleanOrphanImageDirs() {
        try {
            Path imgDir = Path.of(imageBaseDir);
            if (!Files.isDirectory(imgDir)) return;

            java.util.Set<String> validPrefixes = personaMapper.selectList(null)
                    .stream().map(p -> p.getId().substring(0, 8)).collect(Collectors.toSet());

            try (var dirs = Files.list(imgDir)) {
                dirs.filter(Files::isDirectory).forEach(dir -> {
                    String dirName = dir.getFileName().toString();
                    if (!validPrefixes.contains(dirName)) {
                        try {
                            deleteRecursively(dir);
                            log.info("清理孤儿图片目录: {}", dirName);
                        } catch (Exception e) {
                            log.warn("清理孤儿目录失败: {}, error={}", dirName, e.getMessage());
                        }
                    }
                });
            }
        } catch (Exception e) {
            log.warn("孤儿目录清理跳过(可能DB未就绪): {}", e.getMessage());
        }
    }

    private void deleteRecursively(Path dir) throws IOException {
        if (Files.isDirectory(dir)) {
            try (var files = Files.list(dir)) {
                for (Path f : files.toList()) {
                    deleteRecursively(f);
                }
            }
        }
        Files.delete(dir);
    }

    // ==================== 查询 ====================

    public Persona getById(String id) {
        return personaMapper.selectById(id);
    }

    /**
     * 更新用户最后一次发消息的时间
     * ★ 用于时间感知机制：每次用户发消息后更新此时间戳
     *
     * @param personaId 人格 ID
     * @param timestamp  ISO 8601 格式时间字符串
     */
    @Transactional
    public void updateLastMessageTime(String personaId, String timestamp) {
        Persona persona = new Persona();
        persona.setId(personaId);
        persona.setLastUserMessageTime(timestamp);
        personaMapper.updateById(persona);
    }

    public Persona findByAiQQ(String aiQq) {
        LambdaQueryWrapper<Persona> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(Persona::getAiQq, aiQq).ne(Persona::getStatus, "archived");
        return personaMapper.selectOne(wrapper);
    }

    public List<Persona> listAll() {
        LambdaQueryWrapper<Persona> wrapper = new LambdaQueryWrapper<>();
        wrapper.ne(Persona::getStatus, "archived");
        return personaMapper.selectList(wrapper);
    }

    public List<Persona> listActive() {
        LambdaQueryWrapper<Persona> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(Persona::getStatus, "active");
        return personaMapper.selectList(wrapper);
    }

    public List<Persona> listEnabled() {
        return personaMapper.selectList(new LambdaQueryWrapper<Persona>()
                .in(Persona::getStatus, List.of("active", "sleeping")));
    }

    // ==================== 创建 ====================

    @Transactional
    @CacheEvict(value = "persona", allEntries = true)
    public Persona createPersona(PersonaConfigDTO config, String rawApiKey,
                                 String masterKey, String lifeArchiveJson) {
        String personaId = UUID.randomUUID().toString();

        Persona persona = new Persona();
        persona.setId(personaId);
        persona.setName(config.getName());
        persona.setBigFiveJson(config.getBigFiveJson() != null && !config.getBigFiveJson().isEmpty()
                ? config.getBigFiveJson() : "{}");
        persona.setAttachmentAnxiety(config.getAttachmentAnxiety());
        persona.setAttachmentAvoidance(config.getAttachmentAvoidance());
        persona.setSelfEsteemStability(config.getSelfEsteemStability());
        persona.setSocialRhythm(config.getSocialRhythm());
        persona.setConflictStyle(config.getConflictStyle());
        persona.setInitiativeTendency(config.getInitiativeTendency());
        persona.setInputMethod(config.getInputMethod());
        persona.setTypingStyleJson(config.getTypingStyleJson());
        persona.setTypingSpeed(config.getTypingSpeed());
        persona.setImageStylePrompt(config.getImageStylePrompt());
        persona.setCharacterAppearance(config.getCharacterAppearance());
        persona.setImageEnabled(config.getImageEnabled() != null ? config.getImageEnabled() : 1);

        try {
            String encrypted = rawApiKey != null && !rawApiKey.isEmpty() && masterKey != null && !masterKey.isBlank()
                    ? AESGCMUtil.encrypt(rawApiKey, ensureValidKey(masterKey))
                    : "";
            persona.setApiKeyEncrypted(encrypted);
        } catch (Exception e) {
            log.warn("API Key 加密失败，将存空值: {}", e.getMessage());
            persona.setApiKeyEncrypted("");
        }

        persona.setSampleChatsJson(config.getSampleChatsJson());
        persona.setCharacterInitialWorldTime(config.getCharacterInitialWorldTime());
        persona.setBirthday(config.getBirthday());
        persona.setCharacterCurrentContext(
            config.getCharacterCurrentContext() != null && !config.getCharacterCurrentContext().isEmpty()
                ? config.getCharacterCurrentContext()
                : buildPhaseContext(config.getName(), config.getRelationshipPhase())
        );
        persona.setAiQq(trimAccount(config.getAiQq()));
        persona.setOwnerQq(resolveOwnerQq(config.getOwnerQq()));
        persona.setRelationshipPhase(config.getRelationshipPhase());
        persona.setMatchmakerRawData(config.getMatchmakerRawData());
        persona.setStatus("active");

        persona.setLifeStage(config.getLifeStage());
        persona.setLifeStageDetail(config.getLifeStageDetail());
        persona.setCurrentLocation(config.getCurrentLocation());
        if (persona.getLifeStage() == null || persona.getLifeStage().isEmpty()) {
            persona.setLifeStage(inferLifeStage(persona.getCharacterCurrentContext()));
        }

        clearPreviousBotBinding(personaId, persona.getAiQq());
        personaMapper.insert(persona);

        ensureImageDir(persona);

        if (lifeArchiveJson != null && !lifeArchiveJson.isEmpty()) {
            CharacterLifeArchive archive = new CharacterLifeArchive();
            archive.setPersonaId(personaId);
            archive.setArchiveJson(lifeArchiveJson);
            lifeArchiveMapper.insert(archive);
        }

        return persona;
    }

    // ==================== 更新 ====================

    @Transactional
    @CacheEvict(value = "persona", allEntries = true)
    public void update(String id, PersonaConfigDTO config) {
        if (getById(id) == null) throw new IllegalArgumentException("Persona 不存在: " + id);
        if (config.getAiQq() != null) clearPreviousBotBinding(id, trimAccount(config.getAiQq()));
        LambdaUpdateWrapper<Persona> wrapper = new LambdaUpdateWrapper<>();
        wrapper.eq(Persona::getId, id)
               .set(config.getName() != null, Persona::getName, config.getName())
               .set(config.getBigFiveJson() != null, Persona::getBigFiveJson, config.getBigFiveJson())
               .set(config.getAttachmentAnxiety() != null, Persona::getAttachmentAnxiety, config.getAttachmentAnxiety())
               .set(config.getAttachmentAvoidance() != null, Persona::getAttachmentAvoidance, config.getAttachmentAvoidance())
               .set(config.getSelfEsteemStability() != null, Persona::getSelfEsteemStability, config.getSelfEsteemStability())
               .set(config.getSocialRhythm() != null, Persona::getSocialRhythm, config.getSocialRhythm())
               .set(config.getConflictStyle() != null, Persona::getConflictStyle, config.getConflictStyle())
               .set(config.getInitiativeTendency() != null, Persona::getInitiativeTendency, config.getInitiativeTendency())
               .set(config.getInputMethod() != null, Persona::getInputMethod, config.getInputMethod())
               .set(config.getTypingStyleJson() != null, Persona::getTypingStyleJson, config.getTypingStyleJson())
               .set(config.getTypingSpeed() != null, Persona::getTypingSpeed, config.getTypingSpeed())
               .set(config.getImageStylePrompt() != null, Persona::getImageStylePrompt, config.getImageStylePrompt())
               .set(config.getCharacterAppearance() != null, Persona::getCharacterAppearance, config.getCharacterAppearance())
               .set(config.getImageEnabled() != null, Persona::getImageEnabled, config.getImageEnabled())
               .set(config.getSampleChatsJson() != null, Persona::getSampleChatsJson, config.getSampleChatsJson())
               .set(config.getCharacterInitialWorldTime() != null, Persona::getCharacterInitialWorldTime, config.getCharacterInitialWorldTime())
               .set(config.getBirthday() != null, Persona::getBirthday, config.getBirthday())
               .set(config.getCharacterCurrentContext() != null, Persona::getCharacterCurrentContext, config.getCharacterCurrentContext())
               .set(config.getRelationshipPhase() != null, Persona::getRelationshipPhase, config.getRelationshipPhase())
               .set(config.getMatchmakerRawData() != null, Persona::getMatchmakerRawData, config.getMatchmakerRawData())
               .set(config.getAiQq() != null, Persona::getAiQq, trimAccount(config.getAiQq()))
               .set(config.getOwnerQq() != null, Persona::getOwnerQq, resolveOwnerQq(config.getOwnerQq()))
               .set(config.getLifeStage() != null, Persona::getLifeStage, config.getLifeStage())
               .set(config.getLifeStageDetail() != null, Persona::getLifeStageDetail, config.getLifeStageDetail())
               .set(config.getCurrentLocation() != null, Persona::getCurrentLocation, config.getCurrentLocation());
        personaMapper.update(wrapper);
    }

    @CacheEvict(value = "persona", allEntries = true)
    public void archive(String id) {
        LambdaUpdateWrapper<Persona> wrapper = new LambdaUpdateWrapper<>();
        wrapper.eq(Persona::getId, id).set(Persona::getStatus, "archived");
        personaMapper.update(wrapper);
    }

    @CacheEvict(value = "persona", allEntries = true)
    public String toggle(String id) {
        Persona persona = getById(id);
        if (persona == null) throw new RuntimeException("Persona 不存在: " + id);
        if ("archived".equals(persona.getStatus())) throw new IllegalStateException("已归档的角色无法切换状态");
        String newStatus = "paused".equals(persona.getStatus()) ? "active" : "paused";
        LambdaUpdateWrapper<Persona> wrapper = new LambdaUpdateWrapper<>();
        wrapper.eq(Persona::getId, id).set(Persona::getStatus, newStatus);
        personaMapper.update(wrapper);
        return newStatus;
    }

    @Transactional
    @CacheEvict(value = "persona", allEntries = true)
    public void bindChannel(String id, String type, String account) {
        Persona persona = getById(id);
        if (persona == null) throw new IllegalArgumentException("Persona 不存在: " + id);
        if (!"qq".equals(type) && !"owner-qq".equals(type)) throw new IllegalArgumentException("不支持的聊天渠道");
        account = trimAccount(account);

        String oldAiQq = persona.getAiQq();

        LambdaUpdateWrapper<Persona> wrapper = new LambdaUpdateWrapper<>();
        wrapper.eq(Persona::getId, id);
        if ("qq".equals(type)) {
            clearPreviousBotBinding(id, account);
            wrapper.set(Persona::getAiQq, account);
            // 自动设置 owner_qq：如果当前Persona没有owner_qq，从其他活跃Persona继承
            if (persona.getOwnerQq() == null || persona.getOwnerQq().isEmpty()) {
                String inheritedOwner = resolveOwnerQq(null);
                if (inheritedOwner != null && !inheritedOwner.isEmpty()) {
                    wrapper.set(Persona::getOwnerQq, inheritedOwner);
                    log.info("自动设置 owner_qq: persona={}, owner={}", id, inheritedOwner);
                }
            }
        } else if ("owner-qq".equals(type)) {
            wrapper.set(Persona::getOwnerQq, account);
        }
        personaMapper.update(wrapper);

        // 清除旧的 findByAiQQ 缓存键（核心修复）
        if (oldAiQq != null && !oldAiQq.isEmpty()) {
            evictCache("qq:" + oldAiQq);
        }
        if (account != null && !account.isEmpty()) {
            evictCache("qq:" + account);
        }
        evictCache(id);
    }

    private void clearPreviousBotBinding(String personaId, String aiQq) {
        if (aiQq == null || aiQq.isBlank()) return;
        personaMapper.update(new LambdaUpdateWrapper<Persona>()
                .eq(Persona::getAiQq, aiQq).ne(Persona::getId, personaId)
                .set(Persona::getAiQq, null));
    }

    private String trimAccount(String account) {
        return account == null || account.isBlank() ? null : account.trim();
    }

    private String resolveOwnerQq(String explicitOwner) {
        String owner = trimAccount(explicitOwner);
        if (owner != null) return owner;
        Object globalOwner = loadSystemConfig().get("qq");
        return trimAccount(globalOwner == null ? null : globalOwner.toString());
    }

    /** 手动清除 Redis 缓存中的指定键 */
    private void evictCache(String cacheKey) {
        try {
            if (cacheManager != null) {
                var cache = cacheManager.getCache("persona");
                if (cache != null) {
                    cache.evict(cacheKey);
                }
            }
        } catch (Exception e) {
            log.debug("缓存清除跳过: key={}, error={}", cacheKey, e.getMessage());
        }
    }

    /** 查找任意已配置 owner_qq 的活跃 Persona 的 owner_qq，用于新 Persona 自动继承 */
    private String findAnyOwnerQq(String excludeId) {
        List<Persona> all = personaMapper.selectList(
            new LambdaQueryWrapper<Persona>()
                .ne(Persona::getId, excludeId)
                .isNotNull(Persona::getOwnerQq)
                .ne(Persona::getOwnerQq, "")
                .eq(Persona::getStatus, "active")
        );
        for (Persona p : all) {
            if (p.getOwnerQq() != null && !p.getOwnerQq().isEmpty()) {
                return p.getOwnerQq();
            }
        }
        return null;
    }

    @CacheEvict(value = "persona", allEntries = true)
    public int setAllOwnerQq(String ownerQq) {
        LambdaUpdateWrapper<Persona> wrapper = new LambdaUpdateWrapper<>();
        wrapper.set(Persona::getOwnerQq, ownerQq);
        return personaMapper.update(wrapper);
    }

    // ==================== API 配置 ====================

    /**
     * 将 masterKey 的 SHA-256 哈希存入 system_config.json，
     * 确保后续解密使用相同的密钥。
     */
    public void saveMasterKeyHash(String masterKey) {
        try {
            String safeKey = ensureValidKey(masterKey);
            Path path = Path.of(configFilePath);
            Map<String, Object> cfg;
            if (Files.exists(path)) {
                cfg = objectMapper.readValue(Files.readString(path),
                        new TypeReference<Map<String, Object>>() {});
            } else {
                cfg = new LinkedHashMap<>();
            }
            cfg.put("masterKeyHash", safeKey);
            Files.writeString(path, objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(cfg));
        } catch (Exception e) {
            log.warn("保存 masterKeyHash 失败: {}", e.getMessage());
        }
    }

    public ApiConfigDTO decryptApiConfig(String personaId) {
        if (getById(personaId) == null) return null;
        Map<String, Object> config = loadGlobalApiConfig();
        String key = Objects.toString(config.get("api_key"), "");
        if (key.isBlank()) return null;
        return new ApiConfigDTO(Objects.toString(config.get("provider"), ""), key,
                Objects.toString(config.get("base_url"), ""), Objects.toString(config.get("model"), ""));
    }

    private String loadMasterKeyFromConfig() {
        try {
            Path path = Path.of(configFilePath);
            if (!Files.exists(path)) return null;
            Map<String, Object> cfg = objectMapper.readValue(Files.readString(path),
                    new TypeReference<Map<String, Object>>() {});
            return (String) cfg.get("masterKeyHash");
        } catch (Exception e) {
            return null;
        }
    }

    /** 从 system_config.json 读取 Base64 编码的 apiKeyEncrypted 并解码为明文 */
    private String loadRawApiKeyFromConfig() {
        try {
            Path path = Path.of(configFilePath);
            if (!Files.exists(path)) return null;
            Map<String, Object> cfg = objectMapper.readValue(Files.readString(path),
                    new TypeReference<Map<String, Object>>() {});
            String encoded = (String) cfg.get("apiKeyEncrypted");
            if (encoded == null || encoded.isEmpty()) return null;
            return new String(java.util.Base64.getDecoder().decode(encoded));
        } catch (Exception e) {
            return null;
        }
    }

    private Map<String, Object> loadSystemConfig() {
        try {
            String json = Files.readString(Path.of(configFilePath));
            return objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            Map<String, Object> defaults = new LinkedHashMap<>();
            defaults.put("provider", "deepseek");
            defaults.put("baseUrl", "https://api.deepseek.com/v1");
            defaults.put("model", "deepseek-chat");
            return defaults;
        }
    }

    /** 从 system_config.json 加载图片 API 配置 */
    @SuppressWarnings("unchecked")
    public Map<String, Object> loadImageApiConfig() {
        try {
            Path path = Path.of(configFilePath);
            if (!Files.exists(path)) return null;
            Map<String, Object> cfg = loadSystemConfig();
            String imageProvider = (String) cfg.getOrDefault("imageProvider", "");
            if (imageProvider == null || imageProvider.isEmpty()) return null;
            Map<String, Object> image = new LinkedHashMap<>();
            image.put("provider", imageProvider);
            String encoded = (String) cfg.getOrDefault("imageApiKeyEncrypted", "");
            String decoded = "";
            if (encoded != null && !encoded.isEmpty()) {
                try { decoded = new String(java.util.Base64.getDecoder().decode(encoded)); } catch (Exception e) { log.debug("imageApiKeyEncrypted Base64解码失败", e); }
            }
            image.put("api_key", decoded);
            image.put("base_url", cfg.getOrDefault("imageBaseUrl", ""));
            image.put("model", cfg.getOrDefault("imageModel", ""));
            return image;
        } catch (Exception e) {
            return null;
        }
    }

    /** 从 system_config.json 加载全局 LLM API 配置（含 Base64 解码） */
    public Map<String, Object> loadGlobalApiConfig() {
        try {
            Map<String, Object> cfg = loadSystemConfig();
            Map<String, Object> api = new LinkedHashMap<>();
            api.put("provider", cfg.getOrDefault("provider", "deepseek"));
            String encoded = cfg.getOrDefault("apiKeyEncrypted", "").toString();
            String decoded = "";
            if (encoded != null && !encoded.isEmpty()) {
                try { decoded = new String(java.util.Base64.getDecoder().decode(encoded)); } catch (Exception e) { log.debug("apiKeyEncrypted Base64解码失败", e); }
            }
            api.put("api_key", decoded);
            api.put("base_url", cfg.getOrDefault("baseUrl", "https://api.deepseek.com/v1"));
            api.put("model", cfg.getOrDefault("model", "deepseek-chat"));
            Object ebUrl = cfg.get("embedderBaseUrl");
            if (ebUrl != null) api.put("embedder_base_url", ebUrl.toString());
            String ebEncoded = cfg.getOrDefault("embedderApiKeyEncrypted", "").toString();
            if (ebEncoded != null && !ebEncoded.isEmpty()) {
                try { api.put("embedder_api_key", new String(java.util.Base64.getDecoder().decode(ebEncoded))); }
                catch (Exception e) { log.debug("embedderApiKeyEncrypted Base64解码失败", e); }
            }
            return api;
        } catch (Exception e) {
            return defaultApiConfig();
        }
    }

    /** 从 system_config.json 加载 API Key 明文（Base64 解码后） */
    public String loadApiKeyPlain() {
        try {
            Map<String, Object> cfg = loadSystemConfig();
            String encoded = cfg.getOrDefault("apiKeyEncrypted", "").toString();
            if (encoded == null || encoded.isEmpty()) return "";
            return new String(java.util.Base64.getDecoder().decode(encoded));
        } catch (Exception e) {
            return "";
        }
    }

    /** 默认 API 配置（DeepSeek 占位） */
    private Map<String, Object> defaultApiConfig() {
        Map<String, Object> api = new LinkedHashMap<>();
        api.put("provider", "deepseek");
        api.put("api_key", "");
        api.put("base_url", "https://api.deepseek.com/v1");
        api.put("model", "deepseek-chat");
        return api;
    }

    // ==================== 导入导出 ====================

    public String exportConfig(String id) {
        Persona persona = getById(id);
        if (persona == null) return null;
        try {
            Map<String, Object> export = new LinkedHashMap<>();
            export.put("name", persona.getName());
            export.put("bigFiveJson", persona.getBigFiveJson());
            export.put("attachmentAnxiety", persona.getAttachmentAnxiety());
            export.put("attachmentAvoidance", persona.getAttachmentAvoidance());
            export.put("selfEsteemStability", persona.getSelfEsteemStability());
            export.put("socialRhythm", persona.getSocialRhythm());
            export.put("conflictStyle", persona.getConflictStyle());
            export.put("initiativeTendency", persona.getInitiativeTendency());
            export.put("inputMethod", persona.getInputMethod());
            export.put("typingStyleJson", persona.getTypingStyleJson());
            export.put("typingSpeed", persona.getTypingSpeed());
            export.put("imageStylePrompt", persona.getImageStylePrompt());
            export.put("characterInitialWorldTime", persona.getCharacterInitialWorldTime());
            export.put("birthday", persona.getBirthday());
            export.put("characterCurrentContext", persona.getCharacterCurrentContext());
            export.put("sampleChatsJson", persona.getSampleChatsJson());
            export.put("lifeStage", persona.getLifeStage());
            export.put("lifeStageDetail", persona.getLifeStageDetail());
            export.put("currentLocation", persona.getCurrentLocation());
            export.put("aiQq", persona.getAiQq());
            export.put("ownerQq", persona.getOwnerQq());
            export.put("relationshipPhase", persona.getRelationshipPhase());
            export.put("status", persona.getStatus());

            CharacterLifeArchive lifeArchive = lifeArchiveMapper.selectById(id);
            if (lifeArchive != null && lifeArchive.getArchiveJson() != null) {
                export.put("life_archive", lifeArchive.getArchiveJson());
            }
            return objectMapper.writeValueAsString(export);
        } catch (Exception e) {
            return "{}";
        }
    }

    @Transactional
    @CacheEvict(value = "persona", allEntries = true)
    public String createFromJson(String json) {
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> data = objectMapper.readValue(json, Map.class);

            Persona persona = new Persona();
            String personaId = UUID.randomUUID().toString();
            persona.setId(personaId);

            persona.setName(strOrNull(data, "name"));
            persona.setBigFiveJson(strOrNull(data, "bigFiveJson"));
            persona.setAttachmentAnxiety(dblOrNull(data, "attachmentAnxiety"));
            persona.setAttachmentAvoidance(dblOrNull(data, "attachmentAvoidance"));
            persona.setSelfEsteemStability(dblOrNull(data, "selfEsteemStability"));
            persona.setSocialRhythm(strOrNull(data, "socialRhythm"));
            persona.setConflictStyle(strOrNull(data, "conflictStyle"));
            persona.setInitiativeTendency(dblOrNull(data, "initiativeTendency"));
            persona.setInputMethod(strOrNull(data, "inputMethod"));
            persona.setTypingStyleJson(strOrNull(data, "typingStyleJson"));
            persona.setTypingSpeed(dblOrNull(data, "typingSpeed"));
            persona.setImageStylePrompt(strOrNull(data, "imageStylePrompt"));
            persona.setCharacterAppearance(strOrNull(data, "characterAppearance"));
            persona.setImageEnabled(intOrNull(data, "imageEnabled"));
            persona.setCharacterInitialWorldTime(strOrNull(data, "characterInitialWorldTime"));
            persona.setBirthday(strOrNull(data, "birthday"));
            persona.setCharacterCurrentContext(strOrNull(data, "characterCurrentContext"));
            persona.setSampleChatsJson(strOrNull(data, "sampleChatsJson"));
            persona.setAiQq(trimAccount(strOrNull(data, "aiQq")));
            persona.setOwnerQq(resolveOwnerQq(strOrNull(data, "ownerQq")));
            persona.setRelationshipPhase(strOrNull(data, "relationshipPhase"));
            persona.setMatchmakerRawData(strOrNull(data, "matchmakerRawData"));
            persona.setStatus("active");
            persona.setApiKeyEncrypted("");

            persona.setLifeStage(strOrNull(data, "lifeStage"));
            persona.setLifeStageDetail(strOrNull(data, "lifeStageDetail"));
            persona.setCurrentLocation(strOrNull(data, "currentLocation"));
            if (persona.getLifeStage() == null || persona.getLifeStage().isEmpty()) {
                persona.setLifeStage(inferLifeStage(persona.getCharacterCurrentContext()));
            }

            clearPreviousBotBinding(personaId, persona.getAiQq());
            personaMapper.insert(persona);
            ensureImageDir(persona);

            String lifeArchiveJson = strOrNull(data, "lifeArchive");
            if (lifeArchiveJson != null && !lifeArchiveJson.isEmpty()) {
                CharacterLifeArchive archive = new CharacterLifeArchive();
                archive.setPersonaId(personaId);
                archive.setArchiveJson(lifeArchiveJson);
                lifeArchiveMapper.insert(archive);
            }

            return personaId;
        } catch (Exception e) {
            throw new RuntimeException("创建失败: " + e.getMessage());
        }
    }

    @Transactional
    @CacheEvict(value = "persona", allEntries = true)
    public String importFromJson(String json) {
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> data = objectMapper.readValue(json, Map.class);

            Persona persona = new Persona();
            String personaId = UUID.randomUUID().toString();
            persona.setId(personaId);

            persona.setName(strOrNull(data, "name"));
            persona.setBigFiveJson(strOrNull(data, "bigFiveJson"));
            persona.setAttachmentAnxiety(dblOrNull(data, "attachmentAnxiety"));
            persona.setAttachmentAvoidance(dblOrNull(data, "attachmentAvoidance"));
            persona.setSelfEsteemStability(dblOrNull(data, "selfEsteemStability"));
            persona.setSocialRhythm(strOrNull(data, "socialRhythm"));
            persona.setConflictStyle(strOrNull(data, "conflictStyle"));
            persona.setInitiativeTendency(dblOrNull(data, "initiativeTendency"));
            persona.setInputMethod(strOrNull(data, "inputMethod"));
            persona.setTypingStyleJson(strOrNull(data, "typingStyleJson"));
            persona.setTypingSpeed(dblOrNull(data, "typingSpeed"));
            persona.setImageStylePrompt(strOrNull(data, "imageStylePrompt"));
            persona.setCharacterInitialWorldTime(strOrNull(data, "characterInitialWorldTime"));
            persona.setBirthday(strOrNull(data, "birthday"));
            persona.setCharacterCurrentContext(strOrNull(data, "characterCurrentContext"));
            persona.setSampleChatsJson(strOrNull(data, "sampleChatsJson"));
            persona.setAiQq(trimAccount(strOrNull(data, "aiQq")));
            persona.setOwnerQq(resolveOwnerQq(strOrNull(data, "ownerQq")));
            persona.setRelationshipPhase(strOrNull(data, "relationshipPhase"));
            persona.setStatus("paused");
            persona.setApiKeyEncrypted("");
            clearPreviousBotBinding(personaId, persona.getAiQq());
            personaMapper.insert(persona);
            ensureImageDir(persona);
            return personaId;
        } catch (Exception e) {
            throw new RuntimeException("导入失败: " + e.getMessage());
        }
    }

    private String strOrNull(Map<String, Object> m, String key) {
        Object v = m.get(key);
        return v != null ? v.toString() : null;
    }

    private Double dblOrNull(Map<String, Object> m, String key) {
        Object v = m.get(key);
        if (v instanceof Number) return ((Number) v).doubleValue();
        return null;
    }

    private Integer intOrNull(Map<String, Object> m, String key) {
        Object v = m.get(key);
        if (v instanceof Number) return ((Number) v).intValue();
        return null;
    }

    // ==================== 人生档案 ====================

    public CharacterLifeArchive getLifeArchive(String personaId) {
        return lifeArchiveMapper.selectById(personaId);
    }

    /**
     * 保存或更新人生档案（upsert 语义）
     * @param personaId 角色 ID
     * @param archiveJson 人生档案 JSON 字符串
     */
    public void saveLifeArchive(String personaId, String archiveJson) {
        CharacterLifeArchive existing = lifeArchiveMapper.selectById(personaId);
        if (existing != null) {
            existing.setArchiveJson(archiveJson);
            lifeArchiveMapper.updateById(existing);
        } else {
            CharacterLifeArchive archive = new CharacterLifeArchive();
            archive.setPersonaId(personaId);
            archive.setArchiveJson(archiveJson);
            lifeArchiveMapper.insert(archive);
        }
    }

    @CacheEvict(value = "persona", key = "#personaId")
    public void updateCurrentContext(String personaId, String newContext) {
        LambdaUpdateWrapper<Persona> wrapper = new LambdaUpdateWrapper<>();
        wrapper.eq(Persona::getId, personaId)
               .set(Persona::getCharacterCurrentContext, newContext);
        personaMapper.update(wrapper);
    }

    /** 确保 master key 是有效的 Base64 编码的 256-bit AES Key */
    private static String ensureValidKey(String key) {
        try {
            byte[] decoded = java.util.Base64.getDecoder().decode(key);
            if (decoded.length == 32) return key;
        } catch (Exception e) { log.debug("Base64 key检测失败, 将使用SHA-256兜底", e); }
        // 兜底：用 SHA-256 把任意字符串变成 32 字节的 Base64 key
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(key.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return java.util.Base64.getEncoder().encodeToString(hash);
        } catch (Exception e) {
            throw new IllegalStateException("无法生成 AES 加密主密钥，应用无法启动", e);
        }
    }

    /**
     * 为 Persona 创建图片文件夹：{imageBaseDir}/{uuid前8位}/
     */
    private void ensureImageDir(Persona persona) {
        String dirName = buildImageDirName(persona);
        try {
            Files.createDirectories(Path.of(imageBaseDir, dirName));
        } catch (IOException e) {
            log.warn("创建图片文件夹失败(非关键): persona={}, dir={}, error={}",
                    persona.getId(), dirName, e.getMessage());
        }
    }

    /** 生成图片文件夹名：uuid前8位 */
    public static String buildImageDirName(Persona persona) {
        return persona.getId().substring(0, 8);
    }

    /** 根据关系阶段自动生成当前处境描述 */
    private String buildPhaseContext(String name, String phase) {
        if (name == null || name.isBlank()) name = "AI网友";
        switch (phase != null ? phase : "stranger") {
            case "stranger":
                return name + "今天在社交软件上，突然被一个完全陌生的人加了好友。"
                        + "对方没有任何自我介绍，她不知道对方是谁、为什么加她。她感到困惑和警惕。";
            case "acquaintance":
                return name + "和对方算是认识了，但还不是特别熟。偶尔会想起对方但不会主动频繁联系。";
            case "friend":
                return name + "和对方已经是朋友了，平时会分享生活中的趣事，有烦恼时也会互相吐槽。";
            case "close_friend":
                return name + "和对方是密友，几乎无话不谈。她信任对方，不需要伪装自己。";
            default:
                return name + "目前处于日常状态。";
        }
    }

    /** 从 character_current_context 文本中推断生命阶段 */
    private String inferLifeStage(String context) {
        if (context == null || context.isEmpty()) return null;
        if (context.contains("大学") || context.contains("学生") || context.contains("校园")
                || context.contains("上课") || context.contains("宿舍")) return "student";
        if (context.contains("工作") || context.contains("上班") || context.contains("公司")
                || context.contains("职场") || context.contains("通勤")) return "working";
        if (context.contains("旅行") || context.contains("旅游") || context.contains("景点")
                || context.contains("旅途")) return "traveling";
        if (context.contains("在家") || context.contains("放假") || context.contains("休假")
                || context.contains("回家") || context.contains("家人")) return "at_home";
        if (context.contains("求职") || context.contains("找工作") || context.contains("面试")
                || context.contains("招聘")) return "job_hunting";
        return null;
    }
}
