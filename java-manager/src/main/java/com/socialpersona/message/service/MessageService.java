package com.socialpersona.message.service;

import com.socialpersona.error.SystemErrorHandler;
import com.socialpersona.event.service.EventService;
import com.socialpersona.gateway.PythonClient;
import com.socialpersona.gateway.dto.ImageGenerateRequest;
import com.socialpersona.gateway.dto.ImageGenerateResponse;
import com.socialpersona.gateway.dto.MessageRequest;
import com.socialpersona.gateway.dto.MessageResponse;
import com.socialpersona.message.burst.BurstGroupManager;
import com.socialpersona.message.entity.ScheduledMessage;
import com.socialpersona.message.repository.MessageMapper;
import com.socialpersona.message.scheduler.MessageScanScheduler;
import com.socialpersona.message.websocket.FrontendWebSocketHandler;
import com.socialpersona.message.websocket.QQWebSocketHandler;
import com.socialpersona.middleware.InterruptListener;
import com.socialpersona.middleware.MessageSplitter;
import com.socialpersona.persona.entity.Persona;
import com.socialpersona.persona.service.PersonaService;
import com.socialpersona.event.entity.DailyEvent;
import com.socialpersona.event.entity.EventLog;
import com.socialpersona.event.repository.EventLogMapper;
import com.socialpersona.event.repository.EventMapper;
import com.socialpersona.relationship.engine.RelationshipEngine;
import com.socialpersona.relationship.dto.RelationshipDeltasDTO;
import com.socialpersona.relationship.entity.RelationshipState;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class MessageService {

    private static final Logger log = LoggerFactory.getLogger(MessageService.class);

    /** 快速连发中断：personaId → 当前正在处理的请求 token */
    private final ConcurrentHashMap<String, String> activeRequestTokens = new ConcurrentHashMap<>();

    @Autowired
    private MessageMapper messageMapper;

    @Autowired
    private PersonaService personaService;

    @Autowired
    private PythonClient pythonClient;

    @Autowired
    private MessageScanScheduler scanScheduler;

    @Autowired
    private BurstGroupManager burstGroupManager;

    @Autowired
    private FrontendWebSocketHandler frontendWs;

    @Autowired
    private QQWebSocketHandler qqWs;

    @Autowired
    private EventLogMapper eventLogMapper;

    @Autowired
    private RelationshipEngine relationshipEngine;

    @Autowired
    private InterruptListener interruptListener;

    @Autowired
    private SystemErrorHandler systemErrorHandler;

    @Autowired
    private EventService eventService;

    @Autowired
    private EventMapper eventMapper;

    private final ObjectMapper objectMapper = new ObjectMapper();

    /** async 图片生成线程池 */
    private final ExecutorService asyncImageExecutor = Executors.newCachedThreadPool();

    public void handleUserMessage(String personaId, String userMessage) {
        log.debug("handleUserMessage: persona={}, length={}", personaId, userMessage == null ? 0 : userMessage.length());

        scanScheduler.registerPersona(personaId);
        interruptListener.interrupt(personaId);

        // ★ 快速连发：新消息到达 → 中断上一个正在处理的调用
        String thisToken = UUID.randomUUID().toString();
        String oldToken = activeRequestTokens.put(personaId, thisToken);
        if (oldToken != null) {
            log.info("用户连发消息，中断上一次LLM调用: persona={}", personaId);
        }

        boolean inferenceCompleted = false;
        try {
            Persona persona = personaService.getById(personaId);
            if (persona == null) {
                log.warn("Persona 不存在: {}", personaId);
                return;
            }
            if (!"active".equals(persona.getStatus())) return;

            Map<String, Object> apiConfig = loadApiConfig();

            // ★ 时间感知：读取上次用户发言时间，计算沉默间隔
            String lastUserMessageTimeStr = persona.getLastUserMessageTime();
            String elapsedDesc = null;
            if (lastUserMessageTimeStr != null && !lastUserMessageTimeStr.isEmpty()) {
                try {
                    Instant lastTime = Instant.parse(lastUserMessageTimeStr);
                    long elapsedSeconds = Instant.now().getEpochSecond() - lastTime.getEpochSecond();
                    if (elapsedSeconds >= 120) {
                        elapsedDesc = formatElapsed(elapsedSeconds);
                    }
                } catch (Exception e) {
                    log.debug("解析 lastUserMessageTime 失败: {}", e.getMessage());
                }
            }

            Map<String, Object> personaConfig = buildPersonaConfig(persona);
            if (elapsedDesc != null) {
                personaConfig.put("last_user_message_elapsed", elapsedDesc);
            }
            personaConfig.put("recent_conversations", loadRecentConversationTurns(personaId));

            // ★ 立即保存用户消息到 conversation_turn（不含 AI 回复部分）
            //    因为 QQAsyncMessageHandler 是 @Async，两条消息并发时
            //    第二条在第一条 saveConversationTurn 之前就可能 loadRecentConversationTurns
            String turnId = saveUserMessageAsTurn(personaId, userMessage);

            eventService.postponeSleep(personaId);

            RelationshipState relState = relationshipEngine.getState(personaId);
            Map<String, Object> relMap = new LinkedHashMap<>();
            relMap.put("trust", relState.getTrust() != null ? relState.getTrust() : 50);
            relMap.put("closeness", relState.getCloseness() != null ? relState.getCloseness() : 20);
            relMap.put("tension", relState.getTension() != null ? relState.getTension() : 10);
            relMap.put("emotional_energy", relState.getEmotionalEnergy() != null ? relState.getEmotionalEnergy() : 30);
            relMap.put("tension_pressure", relState.getTensionPressure() != null ? relState.getTensionPressure() : 0);
            relMap.put("contact_urge", relState.getContactUrge() != null ? relState.getContactUrge() : 0);

            MessageRequest request = new MessageRequest();
            request.setApiConfig(apiConfig);
            request.setPersonaConfig(personaConfig);
            request.setRelationshipState(relMap);
            // 从 event_log 获取最近内心独白作为记忆
            List<Object> recentMemories = new ArrayList<>();
            try {
                List<EventLog> thoughts = eventLogMapper.selectList(
                    new LambdaQueryWrapper<EventLog>()
                        .eq(EventLog::getPersonaId, personaId)
                        .eq(EventLog::getLogType, "inner_thought")
                        .orderByDesc(EventLog::getCreatedAt)
                        .last("LIMIT 5")
                );
                for (EventLog log : thoughts) {
                    Map<String, Object> mem = new LinkedHashMap<>();
                    mem.put("content", log.getDetailJson());
                    mem.put("relevance_score", 0.5);
                    recentMemories.add(mem);
                }
            } catch (Exception e) {
                log.warn("获取recentMemories失败: {}", e.getMessage());
            }
            request.setRecentMemories(recentMemories);

            // 从 daily_events 查今天已完成的事件
            List<Object> todayEventsSoFar = new ArrayList<>();
            try {
                String today = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd"));
                String nowTime = LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"));
                List<DailyEvent> todayEvents = eventMapper.selectList(
                    new LambdaQueryWrapper<DailyEvent>()
                        .eq(DailyEvent::getPersonaId, personaId)
                        .eq(DailyEvent::getEventDate, today)
                        .eq(DailyEvent::getIsActive, 1)
                        .lt(DailyEvent::getEventTime, nowTime)
                        .orderByAsc(DailyEvent::getEventTime)
                );
                for (DailyEvent ev : todayEvents) {
                    Map<String, Object> evt = new LinkedHashMap<>();
                    evt.put("time", ev.getEventTime());
                    evt.put("type", ev.getEventType());
                    evt.put("description", ev.getDescription());
                    todayEventsSoFar.add(evt);
                }
            } catch (Exception e) {
                log.warn("获取todayEventsSoFar失败: {}", e.getMessage());
            }
            request.setTodayEventsSoFar(todayEventsSoFar);
            request.setUserMessage(userMessage);
            request.setTimestamp(Instant.now().getEpochSecond());

            Map<String, Object> imageConfig = loadImageApiConfig();
            if (imageConfig != null) {
                request.setImageConfig(imageConfig);
            }

            MessageResponse response = pythonClient.handleMessage(request);
            inferenceCompleted = true;

            // ★ 快速连发：检查是否被新消息取消
            if (!thisToken.equals(activeRequestTokens.get(personaId))) {
                log.info("LLM返回但已被新消息取消: persona={}", personaId);
                return;
            }
            Persona currentPersona = personaService.getById(personaId);
            if (currentPersona == null || !"active".equals(currentPersona.getStatus())) return;

            systemErrorHandler.resetFailCount(personaId);

            if (response == null) {
                log.warn("Python 返回 null: persona={}", personaId);
                recordSystemError(personaId, "invalid_model_response");
                return;
            }

            if (Boolean.TRUE.equals(response.getShouldReply())) {
                Object replyObj = response.getReply();
                String replyText = extractReplyText(replyObj);
                String itemsJson = extractItemsJson(replyObj);
                itemsJson = itemsJson != null ? itemsJson : "[]";
                if (replyText != null && !replyText.isEmpty()) {
                    String innerThought = null;
                    String mood = null;
                    if (response.getInnerThought() != null) {
                        try {
                            innerThought = objectMapper.writeValueAsString(response.getInnerThought());
                        } catch (Exception e) { log.debug("innerThought序列化失败", e); }
                    }
                    dispatchReply(personaId, replyText, itemsJson, mood, innerThought);
                    updateConversationTurnWithReply(personaId, turnId, replyText);

                    try {
                        personaService.updateLastMessageTime(personaId, Instant.now().toString());
                    } catch (Exception e) {
                        log.debug("更新lastUserMessageTime失败(非关键): {}", e.getMessage());
                    }
                }
            } else {
                log.debug("AI 决定不回复: persona={}", personaId);
            }

            // ★ 应用关系增量：LLM 返回的信任/亲密/张力变化（哪怕是负值——用户说了不喜欢的话）
            applyDeltas(personaId, response.getRelationshipDeltas());

        } catch (Exception e) {
            if (!thisToken.equals(activeRequestTokens.get(personaId))) return;
            String errorCode = inferenceCompleted ? "qq_delivery_failed" : "model_request_failed";
            log.warn("消息处理失败: persona={}, code={}, type={}", personaId, errorCode, e.getClass().getSimpleName());
            recordSystemError(personaId, errorCode);
            if (inferenceCompleted) return;

            try {
                Persona persona = personaService.getById(personaId);
                if (persona == null || !"active".equals(persona.getStatus())) return;
                systemErrorHandler.incrementFailCount(personaId);
                String fallback = "[系统提示] 模型服务暂时不可用，请检查配置或稍后重试。";
                dispatchReply(personaId, fallback, "[]", "", null);
            } catch (Exception inner) {
                log.warn("系统提示发送失败: persona={}, type={}", personaId, inner.getClass().getSimpleName());
            }
        } finally {
            activeRequestTokens.remove(personaId, thisToken);
        }
    }

    private void recordSystemError(String personaId, String code) {
        try {
            EventLog entry = new EventLog();
            entry.setId(UUID.randomUUID().toString());
            entry.setPersonaId(personaId);
            entry.setLogType("system_error");
            entry.setDetailJson(objectMapper.writeValueAsString(Map.of("code", code)));
            entry.setOccurredAt(Instant.now().toString());
            eventLogMapper.insert(entry);
        } catch (Exception e) {
            log.warn("系统错误记录失败: persona={}, type={}", personaId, e.getClass().getSimpleName());
        }
    }

    private String extractReplyText(Object replyObj) {
        if (replyObj == null) return null;
        String raw;
        if (replyObj instanceof String) raw = (String) replyObj;
        else if (replyObj instanceof Map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> map = (Map<String, Object>) replyObj;
            Object text = map.getOrDefault("raw_text",
                    map.getOrDefault("text",
                            map.getOrDefault("content", map.get("message"))));
            if (text instanceof String) raw = (String) text;
            else raw = replyObj.toString();
        } else {
            raw = replyObj.toString();
        }
        return cleanActionDescriptions(raw);
    }

    /** 清理 raw_text 中泄露的动作描述括号文本，如 (发一个猫猫叉腰表情) */
    private String cleanActionDescriptions(String text) {
        if (text == null) return null;
        return text.replaceAll("[\\s]*[（(]发[一一个张]*[^)）]*[）)]", "");
    }

    @SuppressWarnings("unchecked")
    private String extractItemsJson(Object replyObj) {
        if (replyObj == null) return null;
        if (replyObj instanceof Map) {
            Map<String, Object> map = (Map<String, Object>) replyObj;
            Object items = map.get("items");
            if (items != null) {
                try {
                    return objectMapper.writeValueAsString(items);
                } catch (Exception e) { log.debug("items序列化失败", e); }
            }
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> loadApiConfig() {
        Map<String, Object> config = personaService.loadGlobalApiConfig();
        return config == null ? defaultApiConfig() : config;
    }

    private Map<String, Object> defaultApiConfig() {
        Map<String, Object> api = new LinkedHashMap<>();
        api.put("provider", "deepseek");
        api.put("api_key", "");
        api.put("base_url", "https://api.deepseek.com/v1");
        api.put("model", "deepseek-chat");
        return api;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> loadImageApiConfig() {
        return personaService.loadImageApiConfig();
    }

    private Map<String, Object> buildPersonaConfig(Persona persona) {
        Map<String, Object> cfg = new LinkedHashMap<>();
        cfg.put("id", persona.getId());
        cfg.put("name", persona.getName());
        cfg.put("big_five_json", persona.getBigFiveJson());
        cfg.put("attachment_anxiety", persona.getAttachmentAnxiety());
        cfg.put("attachment_avoidance", persona.getAttachmentAvoidance());
        cfg.put("self_esteem_stability", persona.getSelfEsteemStability());
        cfg.put("social_rhythm", persona.getSocialRhythm());
        cfg.put("conflict_style", persona.getConflictStyle());
        cfg.put("initiative_tendency", persona.getInitiativeTendency());
        cfg.put("input_method", persona.getInputMethod());
        cfg.put("typing_style_json", persona.getTypingStyleJson());
        cfg.put("typing_speed", persona.getTypingSpeed());
        cfg.put("image_style_prompt", persona.getImageStylePrompt());
        cfg.put("character_appearance", persona.getCharacterAppearance());
        cfg.put("image_enabled", persona.getImageEnabled() != null ? persona.getImageEnabled() : 1);
        // ★ 仅当 sample_chats 非空时才发送（避免空数组占位）
        String sc = persona.getSampleChatsJson();
        if (sc != null && !sc.isEmpty() && !"[]".equals(sc)) {
            cfg.put("sample_chats_json", sc);
        }
        cfg.put("character_current_context", persona.getCharacterCurrentContext());
        cfg.put("relationship_phase", persona.getRelationshipPhase());

        cfg.put("life_stage", persona.getLifeStage());
        cfg.put("life_stage_detail", persona.getLifeStageDetail());
        cfg.put("current_location", persona.getCurrentLocation());

        // ★ 世界时间计算：character_initial_world_time + (今天 - created_at)
        String worldTime = persona.getCharacterInitialWorldTime();
        String createdAt = persona.getCreatedAt();
        if (worldTime != null && !worldTime.isEmpty() && createdAt != null && !createdAt.isEmpty()) {
            try {
                java.time.LocalDate worldStart = java.time.LocalDate.parse(worldTime.trim());
                java.time.LocalDate creationDay = java.time.LocalDate.parse(createdAt.substring(0, 10));
                long daysSinceCreation = java.time.temporal.ChronoUnit.DAYS.between(creationDay, java.time.LocalDate.now());
                if (daysSinceCreation > 0) {
                    worldStart = worldStart.plusDays(daysSinceCreation);
                }
                cfg.put("current_world_date", worldStart.toString());
                cfg.put("current_world_day_of_week", worldStart.getDayOfWeek().getDisplayName(
                    java.time.format.TextStyle.FULL, java.util.Locale.CHINESE));
            } catch (Exception e) {
                log.debug("世界时间计算失败: {}", e.getMessage());
            }
        }

        // 年龄计算：从 birthday 派生
        String birthday = persona.getBirthday();
        if (birthday != null && !birthday.isEmpty()) {
            try {
                java.time.LocalDate birthDate = java.time.LocalDate.parse(birthday);
                int age = java.time.Period.between(birthDate, java.time.LocalDate.now()).getYears();
                cfg.put("age", age);
            } catch (Exception e) {
                log.debug("无法解析 birthday: {}", birthday);
            }
        }

        // ★ 从 matchmakerRawData 提取 language_hint 传给 Python prompt
        String mrd = persona.getMatchmakerRawData();
        if (mrd != null && !mrd.isEmpty()) {
            try {
                Object parsed = objectMapper.readValue(mrd, Object.class);
                if (parsed instanceof Map) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> raw = (Map<String, Object>) parsed;
                    if (raw.containsKey("language_hint")) {
                        cfg.put("language_hint", raw.get("language_hint"));
                    }
                }
            } catch (Exception e) {
                log.debug("matchmakerRawData 解析失败: {}", e.getMessage());
            }
        }

        return cfg;
    }

    /**
     * 事件主动联系 → 从 LLM 的 reply/inner_thought 中提取文本并派发
     * 供 EventTriggerScheduler.scanForPersona 调用
     */
    public void handleEventReply(String personaId, Object replyObj, Object innerThoughtObj) {
        String replyText = extractReplyText(replyObj);
        String itemsJson = extractItemsJson(replyObj);
        String mood = null;
        String innerThoughtJson = null;
        if (replyObj instanceof Map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> map = (Map<String, Object>) replyObj;
            Object m = map.get("mood");
            if (m instanceof String) mood = (String) m;
        }
        try {
            innerThoughtJson = objectMapper.writeValueAsString(innerThoughtObj);
        } catch (Exception e) { log.debug("innerThoughtObj序列化失败", e); }
        if (replyText != null) {
            dispatchReply(personaId, replyText, itemsJson, mood, innerThoughtJson);
        }
    }

    @Transactional
    public void dispatchReply(String personaId, String rawText, String itemsJson,
                              String mood, String innerThoughtJson) {
        String[] textSegments = MessageSplitter.split(rawText);
        String burstGroupId = UUID.randomUUID().toString();
        String now = Instant.now().toString();

        Persona persona = personaService.getById(personaId);
        if (persona == null || !"active".equals(persona.getStatus())) return;
        String ownerQq = persona != null ? persona.getOwnerQq() : null;
        String qqMsg = "";
        boolean delivered = false;

        // 解析 items, 处理图片
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = parseItemsList(itemsJson);
        boolean hasRealItems = !items.isEmpty();

        // Fallback: items 为空但 rawText 有内容 → 用第一段文本，但不生成为 DB items
        if (!hasRealItems && textSegments.length > 0) {
            Map<String, Object> fallbackItem = new LinkedHashMap<>();
            fallbackItem.put("type", "text");
            fallbackItem.put("content", textSegments[0]);
            items = List.of(fallbackItem);
        }
        for (Map<String, Object> item : items) {
            String type = (String) item.getOrDefault("type", "text");
            if ("image".equals(type)) {
                String mode = (String) item.getOrDefault("generation_mode", "sync");
                if ("sync".equals(mode)) {
                    String imgPath = generateAndGetImage(personaId, item);
                    if (imgPath != null) {
                        try {
                            qqWs.sendImage(persona.getAiQq(), ownerQq, imgPath, qqMsg);
                            delivered = true;
                            qqMsg = "";
                        } catch (Exception e) {
                            throw new IllegalStateException("QQ 图片发送失败，请检查 NapCat 连接");
                        }
                    }
                } else if ("async".equals(mode)) {
                    // async: 后台生成图片，不阻塞主流程
                    String prompt = (String) item.getOrDefault("content_prompt", "");
                    if (!prompt.isEmpty()) {
                        interruptListener.register(personaId);
                        Map<String, Object> itemCopy = new LinkedHashMap<>(item);
                        asyncImageExecutor.submit(() -> {
                            try {
                                if (interruptListener.isInterrupted(personaId)) {
                                    log.debug("Async图片生成被中断: persona={}", personaId);
                                    return;
                                }
                                String imgPath = generateAndGetImage(personaId, itemCopy);
                                Persona current = personaService.getById(personaId);
                                if (imgPath != null && current != null && "active".equals(current.getStatus())
                                        && current.getOwnerQq() != null && !current.getOwnerQq().isEmpty()) {
                                    qqWs.sendImage(current.getAiQq(), current.getOwnerQq(), imgPath, null);
                                }
                            } catch (Exception e) {
                                recordSystemError(personaId, "image_delivery_failed");
                                log.warn("Async图片发送失败: persona={}, type={}", personaId, e.getClass().getSimpleName());
                            } finally {
                                interruptListener.clear(personaId);
                            }
                        });
                    }
                } else {
                    log.debug("未知 generation_mode: {}", mode);
                }
            } else {
                String content = (String) item.getOrDefault("content", "");
                if (!content.isEmpty()) qqMsg += content;
            }
        }

        List<ScheduledMessage> savedMsgs = new ArrayList<>();

        // ★ 如果 raw_text 含 [SPLIT]：按分段创建多条记录（无论是否有 items）
        //   否则：items 非空 → 1 条记录；items 为空 → 按 textSegments 逐条
        boolean hasSplit = rawText != null && rawText.contains("[SPLIT]") && textSegments.length > 1;

        if (hasSplit) {
            for (int i = 0; i < textSegments.length; i++) {
                String seg = textSegments[i].trim();
                if (seg.isEmpty()) continue;
                // ★ 为每个分段生成独立的 itemsJson，第1段用原 items（可能含图片），
                //    后续段生成纯文本 item，保证 BurstGroupManager & 前端都能读到文本
                String segItemsJson;
                if (i == 0) {
                    segItemsJson = itemsJson;
                } else {
                    Map<String, Object> segItem = new LinkedHashMap<>();
                    segItem.put("type", "text");
                    segItem.put("content", seg);
                    try {
                        segItemsJson = objectMapper.writeValueAsString(List.of(segItem));
                    } catch (Exception e) {
                        segItemsJson = itemsJson;
                    }
                }
                ScheduledMessage msg = new ScheduledMessage();
                msg.setId(UUID.randomUUID().toString());
                msg.setPersonaId(personaId);
                msg.setScheduledTime(now);
                msg.setBurstGroupId(burstGroupId);
                msg.setBurstOrder(i);
                msg.setItemsJson(segItemsJson);
                msg.setMood(mood);
                msg.setInnerThoughtJson(innerThoughtJson);
                msg.setIsSent(0);
                messageMapper.insert(msg);
                savedMsgs.add(msg);
            }
        } else if (hasRealItems) {
            ScheduledMessage msg = new ScheduledMessage();
            msg.setId(UUID.randomUUID().toString());
            msg.setPersonaId(personaId);
            msg.setScheduledTime(now);
            msg.setBurstGroupId(burstGroupId);
            msg.setBurstOrder(0);
            msg.setItemsJson(itemsJson);
            msg.setMood(mood);
            msg.setInnerThoughtJson(innerThoughtJson);
            msg.setIsSent(0);
            messageMapper.insert(msg);
            savedMsgs.add(msg);
        } else {
            for (int i = 0; i < textSegments.length; i++) {
                ScheduledMessage msg = new ScheduledMessage();
                msg.setId(UUID.randomUUID().toString());
                msg.setPersonaId(personaId);
                msg.setScheduledTime(now);
                msg.setBurstGroupId(burstGroupId);
                msg.setBurstOrder(i);
                msg.setItemsJson(itemsJson);
                msg.setMood(mood);
                msg.setInnerThoughtJson(innerThoughtJson);
                msg.setIsSent(0);
                messageMapper.insert(msg);
                savedMsgs.add(msg);
            }
        }

        if (innerThoughtJson != null && !innerThoughtJson.isEmpty()) {
            try {
                EventLog entry = new EventLog();
                entry.setId(UUID.randomUUID().toString());
                entry.setPersonaId(personaId);
                entry.setLogType("inner_thought");
                entry.setDetailJson(innerThoughtJson);
                entry.setOccurredAt(Instant.now().toString());
                eventLogMapper.insert(entry);
            } catch (Exception e) {
                log.warn("内心独白写入失败(非关键): {}", e.getMessage());
            }
        }

        if (!qqMsg.isEmpty() && ownerQq != null && !ownerQq.isEmpty()) {
            // ★ 含 [SPLIT] 时：qqMsg 是全量 items 拼接（含全部段），而分段的 burst 也会发后续段
            //    因此即时推送只发第一段文本，避免重复发送。后续段由 BurstGroupManager 延迟发送
            if (hasSplit && textSegments.length > 0) {
                qqMsg = textSegments[0].trim();
            }
            if (!qqMsg.isEmpty()) {
                qqWs.sendReply(persona.getAiQq(), ownerQq, qqMsg);
                delivered = true;
            }
        }

        if (delivered && !savedMsgs.isEmpty()) {
            ScheduledMessage first = savedMsgs.get(0);
            first.setIsSent(1);
            first.setActualSendTime(Instant.now().toString());
            messageMapper.updateById(first);
        }

        try {
            String pushJson = objectMapper.writeValueAsString(Map.of(
                    "type", "message",
                    "personaId", personaId,
                    "content", itemsJson,
                    "mood", mood != null ? mood : "",
                    "timestamp", now
            ));
            frontendWs.sendToPersona(personaId, pushJson);
        } catch (Exception e) {
            log.warn("前端即时推送失败: {}", e.getMessage());
        }

        // 有后续消息 → 挂接连发组延迟推送
        if (savedMsgs.size() > 1 && persona != null) {
            burstGroupManager.scheduleBurst(personaId, savedMsgs, persona);
        }

        scanScheduler.onMessageListChanged();
        scanScheduler.setOwnerQq(personaId, ownerQq);

        log.info("dispatchReply: persona={}, segments={}, burstGroup={}", personaId, textSegments.length, burstGroupId);
    }

    /** 解析 items JSON 为列表 */
    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> parseItemsList(String itemsJson) {
        if (itemsJson == null || itemsJson.isEmpty() || "[]".equals(itemsJson)) return List.of();
        try {
            return objectMapper.readValue(itemsJson, List.class);
        } catch (Exception e) {
            return List.of();
        }
    }

    /**
     * 调用 Python 图片生成 API，返回本地文件路径
     * 失败时返回 null
     */
    private String generateAndGetImage(String personaId, Map<String, Object> item) {
        try {
            Map<String, Object> imageConfig = loadImageApiConfig();
            if (imageConfig == null) return null;

            String prompt = (String) item.getOrDefault("content_prompt", "");
            if (prompt.isEmpty()) {
                prompt = (String) item.getOrDefault("content", "");
            }
            if (prompt.isEmpty()) return null;

            // ★ 自动拼接图片风格到 prompt，确保画风一致
            //    角色外貌仅在 LLM 标记为自拍(image_type="selfie")时才追加，避免风景/食物等图变成自拍
            Persona p = personaService.getById(personaId);
            if (p != null) {
                StringBuilder enriched = new StringBuilder(prompt);
                String imageType = (String) item.getOrDefault("image_type", "");
                boolean isSelfie = "selfie".equalsIgnoreCase(imageType);
                if (isSelfie && p.getCharacterAppearance() != null && !p.getCharacterAppearance().isEmpty()) {
                    enriched.append("\n【角色外貌】").append(p.getCharacterAppearance());
                }
                if (p.getImageStylePrompt() != null && !p.getImageStylePrompt().isEmpty()) {
                    enriched.append("\n【图片风格】").append(p.getImageStylePrompt());
                }
                prompt = enriched.toString();
            }

            ImageGenerateRequest req = new ImageGenerateRequest();
            req.setImageConfig(imageConfig);
            req.setPrompt(prompt);
            req.setPersonaId(personaId);
            req.setPersonaDirName(buildImageDirName(personaId));
            req.setContext("reply");

            ImageGenerateResponse resp = pythonClient.generateImage(req);
            if (resp != null && resp.isSuccess()) {
                log.info("图片生成成功: persona={}, path={}", personaId, resp.getLocalPath());
                return resp.getLocalPath();
            }
            log.warn("图片生成失败: persona={}, error={}", personaId,
                    resp != null ? resp.getError() : "null");
            return null;
        } catch (Exception e) {
            log.warn("图片生成异常(安全降级): persona={}, error={}", personaId, e.getMessage());
            return null;
        }
    }

    /** 生成图片文件夹名：{name_sanitized}-{uuid前8位} */
    private String buildImageDirName(String personaId) {
        Persona p = personaService.getById(personaId);
        if (p == null) return personaId;
        return com.socialpersona.persona.service.PersonaService.buildImageDirName(p);
    }

    /** 解析并应用 LLM 返回的关系增量（信任/亲密/张力变化） */
    @SuppressWarnings("unchecked")
    private void applyDeltas(String personaId, Object rawDeltas) {
        if (rawDeltas == null) return;
        try {
            Map<String, Object> map;
            if (rawDeltas instanceof Map) {
                map = (Map<String, Object>) rawDeltas;
            } else {
                map = objectMapper.readValue(rawDeltas.toString(), Map.class);
            }
            RelationshipDeltasDTO dto = new RelationshipDeltasDTO();
            if (map.containsKey("trust_delta")) dto.setTrustDelta(toDouble(map.get("trust_delta")));
            if (map.containsKey("closeness_delta")) dto.setClosenessDelta(toDouble(map.get("closeness_delta")));
            if (map.containsKey("tension_delta")) dto.setTensionDelta(toDouble(map.get("tension_delta")));
            if (map.containsKey("emotional_energy_delta")) dto.setEmotionalEnergyDelta(toDouble(map.get("emotional_energy_delta")));
            if (map.containsKey("contact_urge_delta")) dto.setContactUrgeDelta(toDouble(map.get("contact_urge_delta")));
            if (map.containsKey("is_qualitative_leap")) dto.setIsQualitativeLeap(toBoolean(map.get("is_qualitative_leap")));
            relationshipEngine.applyDeltas(personaId, dto);
        } catch (Exception e) {
            log.debug("应用关系增量失败(非关键): persona={}, error={}", personaId, e.getMessage());
        }
    }

    private Double toDouble(Object v) {
        if (v instanceof Number) return ((Number) v).doubleValue();
        if (v instanceof String) try { return Double.parseDouble((String) v); } catch (Exception e) { log.debug("String转Double失败: {}", v, e); }
        return null;
    }

    private Boolean toBoolean(Object v) {
        if (v instanceof Boolean) return (Boolean) v;
        if (v instanceof String) return "true".equalsIgnoreCase((String) v);
        return null;
    }

    private static String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }

    public void sendNow(String personaId, ScheduledMessage msg) {
        if ("now".equals(msg.getScheduledTime())) {
            msg.setScheduledTime(Instant.now().plusSeconds(1).toString());
        }
        msg.setActualSendTime(Instant.now().toString());
        msg.setIsSent(1);
        messageMapper.insert(msg);

        try {
            String json = objectMapper.writeValueAsString(Map.of(
                    "type", "message",
                    "personaId", personaId,
                    "content", msg.getItemsJson(),
                    "mood", msg.getMood() != null ? msg.getMood() : "",
                    "timestamp", msg.getScheduledTime()
            ));
            frontendWs.sendToPersona(personaId, json);
        } catch (Exception e) {
            log.warn("now消息推送失败: {}", e.getMessage());
        }
        scanScheduler.onMessageListChanged();
    }

    public void scheduleIntervalMessages(String personaId, List<ScheduledMessage> msgs) {
        for (ScheduledMessage msg : msgs) {
            if (msg.getId() == null) msg.setId(UUID.randomUUID().toString());
            msg.setPersonaId(personaId);
            msg.setIsSent(0);
            messageMapper.insert(msg);
        }
        scanScheduler.onMessageListChanged();
    }

    public void invalidateAfter(String personaId, LocalTime afterTime) {
        LambdaUpdateWrapper<ScheduledMessage> wrapper = new LambdaUpdateWrapper<>();
        wrapper.eq(ScheduledMessage::getPersonaId, personaId)
               .eq(ScheduledMessage::getIsSent, 0)
               .gt(ScheduledMessage::getScheduledTime, afterTime.toString());

        ScheduledMessage update = new ScheduledMessage();
        update.setIsSent(1);

        messageMapper.update(update, wrapper);
    }

    /** 先保存用户消息到 conversation_turn（不含 AI 回复），返回 log id 用于后续更新 */
    private String saveUserMessageAsTurn(String personaId, String userMsg) {
        try {
            Map<String, Object> turn = new LinkedHashMap<>();
            turn.put("user", userMsg);
            turn.put("timestamp", Instant.now().toString());

            EventLog log = new EventLog();
            String id = UUID.randomUUID().toString();
            log.setId(id);
            log.setPersonaId(personaId);
            log.setLogType("conversation_turn");
            log.setDetailJson(objectMapper.writeValueAsString(turn));
            log.setOccurredAt(Instant.now().toString());
            eventLogMapper.insert(log);
            return id;
        } catch (Exception e) {
            log.debug("保存用户消息失败(非关键): {}", e.getMessage());
            return null;
        }
    }

    /** LLM 回复后追加 AI 回复到已有 conversation_turn */
    private void updateConversationTurnWithReply(String personaId, String turnId, String aiReply) {
        if (turnId == null || aiReply == null) return;
        try {
            EventLog existing = eventLogMapper.selectById(turnId);
            if (existing == null) return;
            @SuppressWarnings("unchecked")
            Map<String, Object> turn = objectMapper.readValue(existing.getDetailJson(), Map.class);
            turn.put("assistant", aiReply);
            existing.setDetailJson(objectMapper.writeValueAsString(turn));
            eventLogMapper.updateById(existing);
        } catch (Exception e) {
            log.debug("更新AI回复失败(非关键): {}", e.getMessage());
        }
    }

    /** 加载最近 N 轮对话，最新的在前 */
    private List<Map<String, Object>> loadRecentConversationTurns(String personaId) {
        List<Map<String, Object>> turns = new ArrayList<>();
        try {
            List<EventLog> logs = eventLogMapper.selectList(
                new LambdaQueryWrapper<EventLog>()
                    .eq(EventLog::getPersonaId, personaId)
                    .eq(EventLog::getLogType, "conversation_turn")
                    .orderByDesc(EventLog::getCreatedAt)
                    .last("LIMIT 10")
            );
            for (int i = logs.size() - 1; i >= 0; i--) {
                try {
                    Map<String, Object> turn = objectMapper.readValue(
                        logs.get(i).getDetailJson(), Map.class);
                    turns.add(turn);
                } catch (Exception e) { log.debug("对话记录JSON解析失败", e); }
            }
        } catch (Exception e) {
            log.debug("加载对话记录跳过: {}", e.getMessage());
        }
        return turns;
    }

    /**
     * 将秒数格式化为人类可读的时间间隔描述
     * ★ 用于 Prompt 注入：让 LLM 感知用户沉默了多久
     *
     * @param seconds 沉默秒数
     * @return 中文时间描述，如 "3小时24分钟" / "2天5小时" / "45分钟"
     */
    private String formatElapsed(long seconds) {
        if (seconds < 60) {
            return seconds + "秒";
        }
        long minutes = seconds / 60;
        if (minutes < 60) {
            return minutes + "分钟";
        }
        long hours = minutes / 60;
        long remainMin = minutes % 60;
        if (hours < 24) {
            if (remainMin == 0) return hours + "小时";
            return hours + "小时" + remainMin + "分钟";
        }
        long days = hours / 24;
        long remainHr = hours % 24;
        if (remainHr == 0) return days + "天";
        return days + "天" + remainHr + "小时";
    }
}
