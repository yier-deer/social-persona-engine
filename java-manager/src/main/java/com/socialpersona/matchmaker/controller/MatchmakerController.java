package com.socialpersona.matchmaker.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.socialpersona.event.entity.EventLog;
import com.socialpersona.event.repository.EventLogMapper;
import com.socialpersona.event.scheduler.EventTriggerScheduler;
import com.socialpersona.gateway.PythonClient;
import com.socialpersona.gateway.dto.MatchmakerRequest;
import com.socialpersona.gateway.dto.MatchmakerResponse;
import com.socialpersona.matchmaker.dto.MatchmakerChatRequest;
import com.socialpersona.matchmaker.dto.MatchmakerChatResponse;
import com.socialpersona.matchmaker.entity.MatchmakerSession;
import com.socialpersona.matchmaker.service.MatchmakerSessionService;
import com.socialpersona.persona.dto.PersonaConfigDTO;
import com.socialpersona.persona.entity.Persona;
import com.socialpersona.persona.repository.PersonaMapper;
import com.socialpersona.persona.service.PersonaService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.bind.annotation.*;

import java.nio.file.*;
import java.util.*;

@RestController
@RequestMapping("/api/matchmaker")
public class MatchmakerController {

    private static final Logger log = LoggerFactory.getLogger(MatchmakerController.class);
    private final ObjectMapper objectMapper = new ObjectMapper();

    private static final String DEFAULT_PERSONA_NAME = "未命名";
    private static final double DEFAULT_ATTACHMENT_ANXIETY = 0.5;
    private static final double DEFAULT_ATTACHMENT_AVOIDANCE = 0.3;
    private static final double DEFAULT_SELF_ESTEEM = 0.7;
    private static final double DEFAULT_INITIATIVE = 0.35;
    private static final double DEFAULT_TYPING_SPEED = 2.5;
    private static final String DEFAULT_SOCIAL_RHYTHM = "slow_warm";
    private static final String DEFAULT_CONFLICT_STYLE = "direct_confront";
    private static final String DEFAULT_INPUT_METHOD = "phone_thumb";
    private static final String DEFAULT_RELATIONSHIP_PHASE = "stranger";

    @Autowired
    private MatchmakerSessionService sessionService;

    @Autowired
    private PersonaService personaService;

    @Autowired
    private PersonaMapper personaMapper;

    @Autowired
    private PythonClient pythonClient;

    @Autowired
    private EventLogMapper eventLogMapper;

    @Autowired
    private EventTriggerScheduler eventTriggerScheduler;

    @Value("${app.image-dir:data/generated_images}")
    private String imageBaseDir;

    @PostMapping("/start")
    public MatchmakerChatResponse start(@RequestBody(required = false) Map<String, String> body) {
        MatchmakerSession session = sessionService.createSession();
        String languageHint = body != null ? body.getOrDefault("languageHint", "") : "";
        MatchmakerChatResponse resp = new MatchmakerChatResponse();
        resp.setSessionId(session.getSessionId());
        resp.setCurrentStage("basic_profile");
        if ("en".equals(languageHint)) {
            resp.setReply("Hi, I'm your matchmaker \uD83D\uDC4B I'll help you find the perfect AI companion.\n\nLet's start with the basics \u2014 what kind of person are you looking for?");
        } else {
            resp.setReply("嗨，我是你的牵线人 \uD83D\uDC4B 我会帮你找到你最需要的那种聊天伙伴。\n\n先说说基本的——你希望这位网友是什么样的人？");
        }
        return resp;
    }

    @GetMapping("/status")
    public MatchmakerChatResponse status(@RequestParam String sessionId) {
        MatchmakerSession session = sessionService.getSession(sessionId);
        if (session == null) {
            MatchmakerChatResponse resp = new MatchmakerChatResponse();
            resp.setSessionId(sessionId);
            resp.setReply("会话已过期，请重新开始。");
            return resp;
        }
        MatchmakerChatResponse resp = new MatchmakerChatResponse();
        resp.setSessionId(sessionId);
        resp.setCurrentStage(session.getCurrentStage());
        resp.setComplete("completed".equals(session.getStatus()));
        if (resp.isComplete()) resp.setPersonaId(session.getPersonaId());
        return resp;
    }

    @PostMapping("/chat")
    public MatchmakerChatResponse chat(@RequestBody MatchmakerChatRequest request) {
        String sessionId = request.getSessionId();
        MatchmakerSession session = sessionService.getSession(sessionId);
        if (session == null) {
            MatchmakerChatResponse resp = new MatchmakerChatResponse();
            resp.setSessionId(sessionId);
            resp.setReply("会话已过期，请重新开始。");
            return resp;
        }

        sessionService.appendHistory(sessionId, "user", request.getUserMessage());

        try {
            Map<String, Object> apiCfg = personaService.loadGlobalApiConfig();
            MatchmakerRequest pytReq = new MatchmakerRequest();
            pytReq.setApiConfig(apiCfg);
            pytReq.setSessionId(sessionId);
            pytReq.setCurrentStage(session.getCurrentStage());
            pytReq.setUserMessage(request.getUserMessage());
            pytReq.setPersonaConfig(MATCHMAKER_PERSONA);
            pytReq.setLanguageHint(request.getLanguageHint());

            try {
                pytReq.setHistory(objectMapper.readValue(session.getHistoryJson(), List.class));
                pytReq.setCollectedData(objectMapper.readValue(session.getCollectedDataJson(), Map.class));
            } catch (Exception e) {
                log.debug("matchmaker history/collectedData JSON解析失败，使用空值兜底", e);
                pytReq.setHistory(List.of());
                pytReq.setCollectedData(Map.of());
            }

            MatchmakerResponse result = pythonClient.matchmakerChat(pytReq);

            String reply = result.getReply() != null ? result.getReply() : "……";
            String rawStage = result.getNextStage();
            String nextStage = isAllowedNextStage(session.getCurrentStage(), rawStage)
                    ? rawStage : session.getCurrentStage();
            boolean isComplete = Boolean.TRUE.equals(result.getIsComplete());

            // ★ 阶段安全校验：只有 sample_confirm 阶段才能声明 isComplete=true
            // LLM 可能在早期阶段就因为误解而提前标记完成，必须拦截
            if (isComplete && !"sample_confirm".equals(nextStage) && !"sample_confirm".equals(session.getCurrentStage())) {
                log.warn("Matchmaker LLM 在非 sample_confirm 阶段提前声明 isComplete=true, "
                        + "session={}, currentStage={}, nextStage={} —— 已拦截",
                        sessionId, session.getCurrentStage(), nextStage);
                isComplete = false;
                // 如果 LLM 输出了一段"总结"但阶段不对，追加提醒让用户继续回答
                reply = reply + "\n\n（还需要再确认几个细节，我们继续吧～）";
            }

            @SuppressWarnings("unchecked")
            Map<String, Object> extractedData = result.getExtractedData() instanceof Map
                    ? (Map<String, Object>) result.getExtractedData() : new HashMap<>();

            sessionService.advanceStage(sessionId, nextStage, extractedData);

            if (isComplete) {
                Map<String, Object> allData = new HashMap<>(extractedData);
                if (result.getPersonaConfig() instanceof Map) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> pc = (Map<String, Object>) result.getPersonaConfig();
                    allData.putAll(pc);
                }
                // ★ 人生档案——存入 collected
                if (result.getLifeArchiveJson() != null) {
                    allData.put("life_archive_json", objectMapper.writeValueAsString(result.getLifeArchiveJson()));
                }
                if (result.getSampleChats() != null) {
                    allData.put("sample_chats_json", objectMapper.writeValueAsString(result.getSampleChats()));
                }
                sessionService.advanceStage(sessionId, nextStage, allData);
            }

            sessionService.appendHistory(sessionId, "matchmaker", reply);

            MatchmakerChatResponse resp = new MatchmakerChatResponse();
            resp.setSessionId(sessionId);
            resp.setReply(reply);
            resp.setCurrentStage(nextStage);
            resp.setComplete(isComplete);
            if (!extractedData.isEmpty()) resp.setExtractedData(extractedData);

            log.info("Matchmaker chat: session={}, stage={}→{}, keys={}, complete={}",
                    sessionId, session.getCurrentStage(), nextStage, extractedData.size(), isComplete);
            return resp;

        } catch (Exception e) {
            log.error("Matchmaker chat failed: {}", e.getClass().getSimpleName());
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "牵线人服务暂时不可用，请重试");
        }
    }

    @PostMapping("/confirm")
    public MatchmakerChatResponse confirm(@RequestParam String sessionId,
                                          @RequestParam(required = false) String masterKey,
                                          @RequestParam(required = false) String languageHint,
                                          @RequestBody(required = false) PersonaConfigDTO configOverride) {
        MatchmakerSession session = sessionService.getSession(sessionId);
        if (session == null) {
            MatchmakerChatResponse resp = new MatchmakerChatResponse();
            resp.setReply("会话不存在。");
            return resp;
        }

        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> collected = objectMapper.readValue(
                    session.getCollectedDataJson(), Map.class);

            // ★ 诊断日志：打印 collected 中所有键（不含值，避免敏感数据泄漏）
            log.info("Matchmaker confirm: collected keys({}): {}", collected.size(), collected.keySet());
            log.info("Matchmaker confirm: big_five type={}, attachment type={}",
                    collected.containsKey("big_five") ? collected.get("big_five").getClass().getSimpleName() : "MISSING",
                    collected.containsKey("attachment_anxiety") ? collected.get("attachment_anxiety").getClass().getSimpleName() : "MISSING");

            PersonaConfigDTO config = configOverride != null ? configOverride : buildConfig(collected);

            String lifeArchiveJson = (String) collected.getOrDefault("life_archive_json",
                    collected.containsKey("character_life_outline")
                            ? toJsonString(collected.get("character_life_outline")) : "{}");

            // ★ 从配置读取真实 API Key 明文（Base64 解码后）
            String apiKey = personaService.loadApiKeyPlain();
            if (apiKey == null || apiKey.isEmpty()) apiKey = "";

            // ★ 保存牵线人完整原始数据（包含 intermediate 字段如 speechStyle、attachmentHint 等）
            if (languageHint != null && !languageHint.isEmpty()) {
                collected.put("language_hint", languageHint);
            }
            config.setMatchmakerRawData(toJsonString(collected));

            // ★ 防重复：检查是否已有同名活跃 Persona
            Persona existing = personaMapper.selectOne(
                new LambdaQueryWrapper<Persona>()
                    .eq(Persona::getName, config.getName())
                    .eq(Persona::getStatus, "active")
            );

            Persona persona;
            if (existing != null) {
                log.info("同名Persona已存在，归档旧记录+创建新记录: name={}, old_id={}", config.getName(), existing.getId());
                // ★ 将旧的同名角色归档（archived），不再是复用——因为用户重新创建说明想要一套新设定
                personaService.archive(existing.getId());
                // 清空旧图片文件夹内容
                try {
                    String dirName = buildImageDirNameForPersona(existing);
                    Path imgDir = Path.of(imageBaseDir, dirName);
                    if (Files.isDirectory(imgDir)) {
                        try (var files = Files.list(imgDir)) {
                            for (Path f : files.toList()) Files.delete(f);
                        }
                    }
                } catch (Exception ex) {
                    log.warn("清理旧图片失败(非关键): {}", ex.getMessage());
                }
                // 清除旧的对话记录
                try {
                    eventLogMapper.delete(
                        new LambdaQueryWrapper<EventLog>()
                            .eq(EventLog::getPersonaId, existing.getId())
                            .eq(EventLog::getLogType, "conversation_turn")
                    );
                    log.info("已清除 {} 的旧对话记录", existing.getId());
                } catch (Exception ex) {
                    log.warn("清除旧对话记录失败(非关键): {}", ex.getMessage());
                }
                persona = personaService.createPersona(config, apiKey, null, lifeArchiveJson);
            } else {
                persona = personaService.createPersona(config, apiKey, null, lifeArchiveJson);
            }

            sessionService.markCompleted(sessionId, persona.getId());

            // ★ 创建成功后立即触发今日事件线懒生成，确保新 AI 一出生就有了"今天的一天"
            try {
                eventTriggerScheduler.lazyGenerateTodayEventsIfNeeded(persona.getId());
            } catch (Exception e) {
                log.warn("新角色事件生成失败(非致命): {} - {}", persona.getId(), e.getMessage());
            }

            MatchmakerChatResponse resp = new MatchmakerChatResponse();
            resp.setSessionId(sessionId);
            resp.setReply("太好了！你的 AI 网友已经创建好了。可以在列表中找到她。");
            resp.setComplete(true);
            resp.setPersonaId(persona.getId());
            return resp;

        } catch (Exception e) {
            log.error("Matchmaker confirm failed: {}", e.getClass().getSimpleName());
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "创建过程中出了点问题，请重试");
        }
    }

    // ==================== 辅助 ====================

    private PersonaConfigDTO buildConfig(Map<String, Object> collected) {
        PersonaConfigDTO config = new PersonaConfigDTO();

        // ★ 诊断日志：记录 collected 中是否有各关键字段
        if (log.isInfoEnabled()) {
            StringBuilder sb = new StringBuilder("buildConfig fields check:");
            for (String key : List.of("social_rhythm","conflict_style","typing_speed",
                    "image_style_prompt","character_appearance","image_enabled",
                    "character_initial_world_time","age","relationship_phase",
                    "big_five","typing_style_json","birthday","character_current_context")) {
                sb.append(" ").append(key).append("=").append(collected.containsKey(key) ? "✓" : "✗");
            }
            log.info(sb.toString());
        }

        // ★ 从 character_life_outline 中提取名字（旧路径，sample_confirm 阶段 LLM 生成）
        Object outlineObj = collected.get("character_life_outline");
        if (outlineObj instanceof Map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> outline = (Map<String, Object>) outlineObj;
            Object n = outline.get("name");
            if (n instanceof String && !((String) n).isEmpty()) config.setName((String) n);
        }
        // ★ 从最终 persona_config 中提取名字（新路径，sample_confirm isComplete=true 时写入）
        if (config.getName() == null && collected.containsKey("persona_config")) {
            Object pcObj = collected.get("persona_config");
            if (pcObj instanceof Map) {
                @SuppressWarnings("unchecked")
                Map<String, Object> pc = (Map<String, Object>) pcObj;
                Object n = pc.get("name");
                if (n instanceof String && !((String) n).isEmpty()) config.setName((String) n);
            }
        }
        // ★ 从 basic_profile 阶段提取的 name
        if (config.getName() == null) {
            Object n = collected.get("name");
            if (n instanceof String && !((String) n).isEmpty()) config.setName((String) n);
        }
        // ★ 最后兜底：从 life_archive_json 字符串中 JSON 解析提取 name
        if (config.getName() == null && collected.containsKey("life_archive_json")) {
            try {
                Object laj = collected.get("life_archive_json");
                if (laj instanceof String && !((String) laj).isEmpty()) {
                    Map<?, ?> parsed = objectMapper.readValue((String) laj, Map.class);
                    Object cl = parsed.get("character_life_outline");
                    if (cl instanceof Map) {
                        Object n = ((Map<?, ?>) cl).get("name");
                        if (n instanceof String && !((String) n).isEmpty()) config.setName((String) n);
                    }
                } else if (laj instanceof Map) {
                    Object cl = ((Map<?, ?>) laj).get("character_life_outline");
                    if (cl instanceof Map) {
                        Object n = ((Map<?, ?>) cl).get("name");
                        if (n instanceof String && !((String) n).isEmpty()) config.setName((String) n);
                    }
                }
            } catch (Exception e) { log.debug("config JSON解析失败", e); }
        }
        if (config.getName() == null || config.getName().isEmpty() || DEFAULT_PERSONA_NAME.equals(config.getName())) {
            config.setName(DEFAULT_PERSONA_NAME);
        }

        config.setBigFiveJson(toJsonString(collected.get("big_five")));
        if (config.getBigFiveJson() == null) {
            config.setBigFiveJson(toJsonString(collected.get("big_five_json")));
        }
        if (config.getBigFiveJson() == null) {
            config.setBigFiveJson("{}");
        }
        config.setAttachmentAnxiety(objectToDouble(collected, "attachment_anxiety", DEFAULT_ATTACHMENT_ANXIETY));
        config.setAttachmentAvoidance(objectToDouble(collected, "attachment_avoidance", DEFAULT_ATTACHMENT_AVOIDANCE));
        config.setSelfEsteemStability(objectToDouble(collected, "self_esteem_stability", DEFAULT_SELF_ESTEEM));
        config.setSocialRhythm(objectToString(collected, "social_rhythm", DEFAULT_SOCIAL_RHYTHM));
        config.setConflictStyle(objectToString(collected, "conflict_style", DEFAULT_CONFLICT_STYLE));
        config.setInitiativeTendency(objectToDouble(collected, "initiative_tendency", DEFAULT_INITIATIVE));
        config.setInputMethod(objectToString(collected, "input_method", DEFAULT_INPUT_METHOD));
        config.setTypingStyleJson(toJsonString(collected.get("typing_style_json")));
        config.setTypingSpeed(objectToDouble(collected, "typing_speed", DEFAULT_TYPING_SPEED));
        config.setImageStylePrompt(objectToString(collected, "image_style_prompt"));
        config.setCharacterAppearance(objectToString(collected, "character_appearance"));
        config.setImageEnabled(objectToInt(collected, "image_enabled", 1));
        config.setSampleChatsJson(objectToString(collected, "sample_chats_json"));
        config.setCharacterInitialWorldTime(objectToString(collected, "character_initial_world_time"));
        if (config.getCharacterInitialWorldTime() == null) {
            config.setCharacterInitialWorldTime(java.time.Instant.now().toString());
        }
        String birthday = objectToString(collected, "birthday");
        if (birthday == null || birthday.isEmpty()) {
            Integer age = objectToInt(collected, "age", null);
            if (age != null) {
                int birthYear = java.time.LocalDate.now().getYear() - age;
                birthday = birthYear + "-01-01";
            }
        }
        config.setBirthday(birthday);
        config.setCharacterCurrentContext(objectToString(collected, "character_current_context"));
        if (config.getCharacterCurrentContext() == null) {
            config.setCharacterCurrentContext(objectToString(collected, "current_context"));
        }
        String phase = objectToString(collected, "relationship_phase");
        config.setRelationshipPhase(phase != null ? phase : DEFAULT_RELATIONSHIP_PHASE);

        config.setLifeStage(objectToString(collected, "life_stage"));
        config.setLifeStageDetail(objectToString(collected, "life_stage_detail"));
        config.setCurrentLocation(objectToString(collected, "current_location"));

        // ★ 兜底：LLM 可能未输出 life_stage_detail 和 current_location，
        // 从 character_current_context 和 personality_hint 推断
        if (config.getLifeStageDetail() == null || config.getLifeStageDetail().isEmpty()) {
            String ctx = config.getCharacterCurrentContext();
            if (ctx == null) ctx = objectToString(collected, "personality_hint");
            if (ctx != null && !ctx.isEmpty()) {
                config.setLifeStageDetail(ctx.length() > 50 ? ctx.substring(0, 50) : ctx);
            }
        }
        if (config.getCurrentLocation() == null || config.getCurrentLocation().isEmpty()) {
            // 尝试从 context 提取城市名（匹配 "在杭州"、"在杭州市"、"在杭州生活" 等模式）
            String ctx = config.getCharacterCurrentContext();
            if (ctx != null) {
                java.util.regex.Matcher m = java.util.regex.Pattern.compile("\u5728([\\u4e00-\\u9fa5]{2,4})(?:[\u5e02\u7701]|[\\u4e00-\\u9fa5]{0,2}(?:[\uff0c\u3002\\.\\s]|$))").matcher(ctx);
                if (m.find()) {
                    config.setCurrentLocation(m.group(1));
                }
            }
        }

        return config;
    }

    /** Map/List 对象 → JSON 字符串（ObjectMapper 序列化），纯字符串直接返回 */
    private String toJsonString(Object obj) {
        if (obj == null) return null;
        if (obj instanceof String) return (String) obj;
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (Exception e) {
            log.debug("JSON序列化失败，使用toString兜底", e);
            return obj.toString();
        }
    }

    private static final Set<String> VALID_STAGES = Set.of(
            "basic_profile", "style_anchor", "boundary_probe",
            "attachment_explore", "system_detail", "sample_confirm"
    );

    private static final List<String> STAGE_ORDER = List.of(
            "basic_profile", "style_anchor", "boundary_probe",
            "attachment_explore", "system_detail", "sample_confirm"
    );

    /** 牵线人固定人格设定 —— 注入 System Prompt 防止人格漂移 */
    private static final Map<String, Object> MATCHMAKER_PERSONA = Map.of(
            "name", "小缘",
            "personality", "你是一位专业的 AI 牵线人，名叫'小缘'。你的工作是引导用户通过7个阶段的访谈，帮助用户创建一个理想的 AI 聊天伙伴。"
                    + "你始终保持温暖、专业、耐心的语调。你认真倾听用户的需求，但不做主观评价。"
                    + "你绝不会模仿或代入被创建者的人格特征。即使用户描述了极端或怪异的人格特征，你也不动摇自己的语调——你是专业的引路人，不是被创造者。"
                    + "你使用自然的对话方式，像一位贴心朋友一样引导用户。"
    );

    private String buildImageDirNameForPersona(Persona persona) {
        return persona.getId().substring(0, 8);
    }

    private boolean isValidStage(String stage) {
        return stage != null && VALID_STAGES.contains(stage);
    }

    /** 阶段推进校验：只允许停留在当前阶段或进入下一个阶段，严禁跳阶段 */
    private boolean isAllowedNextStage(String currentStage, String proposedStage) {
        if (proposedStage == null) return false;
        if (!VALID_STAGES.contains(proposedStage)) return false;
        if (proposedStage.equals(currentStage)) return true;
        int curIdx = STAGE_ORDER.indexOf(currentStage);
        int nextIdx = STAGE_ORDER.indexOf(proposedStage);
        if (nextIdx == curIdx + 1) return true;
        // sample_confirm 允许停留在自身（两轮确认）
        if ("sample_confirm".equals(currentStage) && "sample_confirm".equals(proposedStage)) return true;
        log.warn("阶段跳转被拒绝: {} → {} (LLM 试图跳过中间阶段)", currentStage, proposedStage);
        return false;
    }

    private String objectToString(Map<String, Object> m, String key, String defaultVal) {
        Object v = m.get(key);
        return v != null ? v.toString() : defaultVal;
    }
    private String objectToString(Map<String, Object> m, String key) {
        return objectToString(m, key, null);
    }
    private Double objectToDouble(Map<String, Object> m, String key, Double defaultVal) {
        Object v = m.get(key);
        if (v instanceof Number) return ((Number) v).doubleValue();
        return defaultVal;
    }

    private Integer objectToInt(Map<String, Object> m, String key, Integer defaultVal) {
        Object v = m.get(key);
        if (v instanceof Number) return ((Number) v).intValue();
        if (v instanceof String) {
            try { return Integer.parseInt((String) v); } catch (NumberFormatException e) { /* fall through */ }
        }
        return defaultVal;
    }
}
