package com.socialpersona.event.scheduler;

import com.socialpersona.event.entity.DailyEvent;
import com.socialpersona.event.entity.EventLog;
import com.socialpersona.event.repository.EventLogMapper;
import com.socialpersona.event.service.EventService;
import com.socialpersona.gateway.PythonClient;
import com.socialpersona.gateway.dto.EventGenerateRequest;
import com.socialpersona.gateway.dto.EventGenerateResponse;
import com.socialpersona.gateway.dto.EventTriggerRequest;
import com.socialpersona.gateway.dto.EventTriggerResponse;
import com.socialpersona.message.service.MessageService;
import com.socialpersona.message.scheduler.MessageScanScheduler;
import com.socialpersona.persona.entity.Persona;
import com.socialpersona.persona.repository.PersonaMapper;
import com.socialpersona.persona.service.PersonaService;
import com.socialpersona.message.scheduler.MessageScanScheduler;
import com.socialpersona.relationship.dto.RelationshipDeltasDTO;
import com.socialpersona.relationship.entity.RelationshipState;
import com.socialpersona.relationship.engine.RelationshipEngine;
import com.socialpersona.relationship.state.AIStateMachine;
import com.socialpersona.relationship.state.AIStatus;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 事件触发调度器 —— 每分钟扫描到期事件
 *
 * ★ Day 10 完整接入：scanEvents() 遍历所有活跃 Persona
 *   → scanForPersona() 调 Python POST /api/event/trigger
 *   → 解析 EventTriggerResponse → 决定是否推送消息
 */
@Component
public class EventTriggerScheduler {

    @Value("${app.automation.enabled:true}")
    private boolean automationEnabled = true;

    private static final Logger log = LoggerFactory.getLogger(EventTriggerScheduler.class);

    @Autowired
    private EventService eventService;

    @Autowired
    private AIStateMachine stateMachine;

    @Autowired
    private PythonClient pythonClient;

    @Autowired
    private PersonaService personaService;

    @Autowired
    private MessageScanScheduler scanScheduler;

    @Autowired
    private MessageService messageService;

    @Autowired
    private RelationshipEngine relationshipEngine;

    @Autowired
    private EventLogMapper eventLogMapper;

    @Autowired
    private PersonaMapper personaMapper;

    // ========== 自适应扫描频率 ==========

    private final ConcurrentHashMap<String, ScanLevel> personaScanLevel = new ConcurrentHashMap<>();
    private final java.util.Set<String> scansInProgress = ConcurrentHashMap.newKeySet();

    enum ScanLevel { IDLE, MINUTE_WATCH }

    /** 每小时检查：下一小时是否有事件。额外检测"事件全部过期"死锁并提供自救。 */
    @Scheduled(fixedDelay = 3600000)
    public void hourlyEventCheck() {
        if (!automationEnabled) return;
        String today = LocalDate.now().format(DATE_FMT);
        String now = LocalTime.now().format(TIME_FMT);
        for (Persona persona : personaService.listEnabled()) {
            String personaId = persona.getId();
            String inOneHour = LocalTime.now().plusHours(1).format(TIME_FMT);
            int count = eventService.countBetween(personaId, today, now, inOneHour);
            if (count > 0) {
                personaScanLevel.put(personaId, ScanLevel.MINUTE_WATCH);
                log.debug("EVENT_SCAN[{}]: 下一小时有{}个事件, 启动分钟扫描", personaId, count);
                continue;
            }
            // 自救：即使 future 没事件，但有 today 事件且全部过期 → 注册分钟扫描
            // 否则事件线断裂永远不会被 scanForPersona 的兜底B修复
            if (eventService.hasTodayEvents(personaId)) {
                DailyEvent nextEvent = eventService.findNextActiveAfter(personaId, today, now);
                if (nextEvent == null) {
                    personaScanLevel.put(personaId, ScanLevel.MINUTE_WATCH);
                    log.info("EVENT_SCAN[{}]: 今日事件全部过期, 注册分钟扫描等待自救", personaId);
                }
            }
        }
    }

    /** 分钟级扫描：只扫描标记为 MINUTE_WATCH 的 Persona */
    @Scheduled(fixedDelay = 60000)
    public void minuteEventCheck() {
        if (!automationEnabled) return;
        String today = LocalDate.now().format(DATE_FMT);
        for (Map.Entry<String, ScanLevel> entry : personaScanLevel.entrySet()) {
            if (entry.getValue() != ScanLevel.MINUTE_WATCH) continue;
            String personaId = entry.getKey();
            Persona persona = personaService.getById(personaId);
            if (!isEnabled(persona)) {
                personaScanLevel.remove(personaId);
                continue;
            }
            scanForPersona(persona);

            // 检查本小时剩余时间是否还有事件
            String now = LocalTime.now().format(TIME_FMT);
            String hourEnd = LocalTime.now().withMinute(59).withSecond(59).format(TIME_FMT);
            int remaining = eventService.countBetween(personaId, today, now, hourEnd);
            if (remaining == 0 && eventService.findDue(personaId).isEmpty()) {
                personaScanLevel.put(personaId, ScanLevel.IDLE);
                log.debug("EVENT_SCAN[{}]: 本小时无更多事件, 恢复IDLE", personaId);
            }
        }
    }

    // 原 scanEvents() 被 hourlyEventCheck + minuteEventCheck 替代
    // 保留手动触发版本供测试和管理调用
    public void scanEvents() {
        if (!automationEnabled) return;
        List<Persona> activePersonas = personaService.listEnabled();
        if (activePersonas.isEmpty()) return;

        log.debug("scanEvents: scanning {} active personas...", activePersonas.size());
        for (Persona persona : activePersonas) {
            try {
                scanForPersona(persona);
            } catch (Exception e) {
                log.error("扫描Persona {} 事件失败: {}", persona.getId(), e.getMessage());
            }
        }
    }

    /** ★ 凌晨 00:05 跨天事件生成 cron
     *  解决：服务器持续运行但没有重启/心跳触发懒加载时，跨天后仍能自动生成新一天的事件线 */
    @Scheduled(cron = "0 5 0 * * ?")
    public void midnightEventGeneration() {
        if (!automationEnabled) return;
        List<Persona> active = personaService.listEnabled();
        if (active.isEmpty()) {
            log.debug("午夜事件生成: 无活跃 Persona");
            return;
        }
        log.info("午夜事件生成: 为 {} 个活跃 Persona 生成今日事件", active.size());
        personaScanLevel.clear();
        for (Persona p : active) {
            try {
                lazyGenerateTodayEventsIfNeeded(p.getId());
            } catch (Exception e) {
                log.warn("午夜事件生成失败: persona={}, error={}", p.getId(), e.getMessage());
            }
        }
    }

    /**
     * ★ Day 10：完整接入 Python——填充全部请求字段
     */
    public void scanForPersona(Persona persona) {
        if (!automationEnabled || !isEnabled(persona)) return;
        if (!scansInProgress.add(persona.getId())) return;
        try {
            executeDueEvents(persona);
        } finally {
            scansInProgress.remove(persona.getId());
        }
    }

    private void executeDueEvents(Persona persona) {
        String personaId = persona.getId();
        List<DailyEvent> due = eventService.findDue(personaId);
        if (due.isEmpty()) {
            // 兜底A: 全天无事件 → 补生成
            if (!eventService.hasTodayEvents(personaId)) {
                lazyGenerateTodayEventsIfNeeded(personaId);
                return;
            }
            return;
        }

        for (DailyEvent event : due) {
            if ("sleep".equals(event.getEventType())) {
                stateMachine.onSleepEvent(personaId);
                eventService.invalidateEvent(event.getId());
                log.info("Persona {} → SLEEPING (sleep event: {})", personaId, event.getEventTime());
                continue;
            }

            if (stateMachine.getState(personaId) == AIStatus.SLEEPING) {
                if ("routine".equals(event.getEventType())) {
                    stateMachine.onWakeUpEvent(personaId);
                    persona.setStatus("active");
                } else {
                    eventService.invalidateEvent(event.getId());
                    continue;
                }
            }

            DailyEvent nextEvent = eventService.findNextActive(personaId, event.getEventTime());

            try {
                EventTriggerRequest request = new EventTriggerRequest();
                request.setApiConfig(personaService.loadGlobalApiConfig());
                request.setPersonaConfig(new LinkedHashMap<>(Map.of(
                        "id", personaId,
                        "name", persona.getName() != null ? persona.getName() : "AI网友"
                )));
                @SuppressWarnings("unchecked")
                Map<String, Object> pCfg = (Map<String, Object>) request.getPersonaConfig();
                String ctx = persona.getCharacterCurrentContext();
                if (ctx != null) {
                    pCfg.put("character_current_context", ctx);
                }
                String typing = persona.getTypingStyleJson();
                if (typing != null) {
                    pCfg.put("typing_style_json", typing);
                }
                RelationshipState state = relationshipEngine.getState(personaId);
                request.setRelationshipState(Map.of(
                        "trust", state.getTrust(),
                        "closeness", state.getCloseness(),
                        "tension", state.getTension(),
                        "emotional_energy", state.getEmotionalEnergy(),
                        "tension_pressure", state.getTensionPressure(),
                        "contact_urge", state.getContactUrge()
                ));
                request.setCurrentEvent(Map.of(
                        "type", event.getEventType(),
                        "description", event.getDescription()
                ));
                request.setNextEventType(nextEvent != null ? nextEvent.getEventType() : "");
                request.setNextEventTime(nextEvent != null ? nextEvent.getEventTime() : "");
                request.setNow(Instant.now().getEpochSecond());
                // Python Pydantic 要求这两个字段是 List 而非 null
                request.setRecentMemories(List.of());
                request.setTodayEventsSoFar(List.of());

                Map<String, Object> imageCfg = loadImageApiConfig();
                if (imageCfg != null) {
                    request.setImageConfig(imageCfg);
                }

                EventTriggerResponse resp = pythonClient.triggerEvent(request);
                if (resp == null) throw new IllegalStateException("Empty event response");

                // ★ 始终记录 LLM 响应到 event_log（即使 AI 决定不联系用户）
                // 这样 SimController 的 /api/sim/trigger-events 可准确判断 LLM 是否被调用
                if (resp != null && resp.getInnerThought() != null) {
                    try {
                        String innerThoughtJson = objectMapper.writeValueAsString(resp.getInnerThought());
                        if (innerThoughtJson != null && !innerThoughtJson.isEmpty()
                                && !"null".equals(innerThoughtJson)) {
                            EventLog entry = new EventLog();
                            entry.setId(UUID.randomUUID().toString());
                            entry.setPersonaId(personaId);
                            entry.setLogType("inner_thought");
                            entry.setDetailJson(innerThoughtJson);
                            entry.setOccurredAt(Instant.now().toString());
                            eventLogMapper.insert(entry);
                        }
                    } catch (Exception e) {
                        log.warn("事件扫描内心独白写入失败(非关键): persona={}", personaId);
                    }
                }

                if (resp != null && Boolean.TRUE.equals(resp.getShouldContactUser())) {
                    log.info("Persona {} 决定联系用户（{}）", personaId, event.getEventType());
                    String ownerQq = persona.getOwnerQq();
                    if (ownerQq != null && !ownerQq.isEmpty()) {
                        scanScheduler.setOwnerQq(personaId, ownerQq);
                    }
                    // ★ 主动联系 → 派发 LLM 生成的回复
                    if (resp.getReply() != null) {
                        messageService.handleEventReply(personaId, resp.getReply(), resp.getInnerThought());
                    }
                }

                // ★ 应用关系增量：事件也可能影响信任/亲密/张力
                if (resp != null) applyDeltas(personaId, resp.getRelationshipDeltas());
                eventService.invalidateEvent(event.getId());

            } catch (Exception e) {
                log.warn("事件触发失败，保留以便重试 persona={}, event={}, error={}",
                        personaId, event.getEventType(), e.getClass().getSimpleName());
            }
        }
    }

    /** 日期格式：yyyy-MM-dd */
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm:ss");

    /**
     * 今日事件懒加载 —— AI 上线时检测，今日无事件则生成
     *
     * 由 QQWebSocketHandler 心跳中调用（只负责触发，不阻塞消息）
     * 幂等：已有事件则跳过
     */
    public void lazyGenerateTodayEventsIfNeeded(String personaId) {
        if (!automationEnabled) return;
        if (eventService.hasTodayEvents(personaId)) {
            log.debug("事件已存在，跳过懒加载: persona={}", personaId);
            return;
        }

        Persona persona = personaService.getById(personaId);
        if (!isEnabled(persona)) return;

        log.info("今日无事件，懒加载触发: persona={}", personaId);

        try {
            EventGenerateRequest request = new EventGenerateRequest();
            request.setApiConfig(personaService.loadGlobalApiConfig());
            request.setPersonaConfig(buildPersonaConfigForEvent(persona));
            RelationshipState state = relationshipEngine.getState(personaId);
            request.setRelationshipState(Map.of(
                    "trust", state.getTrust(),
                    "closeness", state.getCloseness(),
                    "tension", state.getTension(),
                    "emotional_energy", state.getEmotionalEnergy(),
                    "tension_pressure", state.getTensionPressure(),
                    "contact_urge", state.getContactUrge()
            ));
            request.setTodayDate(LocalDate.now().format(DATE_FMT));
            request.setDayOfWeek(LocalDate.now().getDayOfWeek()
                    .getDisplayName(java.time.format.TextStyle.FULL, java.util.Locale.CHINESE));
            request.setYesterdayEvents(List.of());
            request.setTodayInnerThoughts(List.of());

            EventGenerateResponse resp = pythonClient.generateEvents(request);
            if (resp != null && resp.getEvents() != null && !resp.getEvents().isEmpty()) {
                eventService.insertEvents(personaId, convertEvents(resp.getEvents()));
                log.info("懒加载今日事件成功: persona={}, count={}", personaId, resp.getEvents().size());
                processLifeStageTransition(personaId, resp);
                registerForMinuteScan(personaId);
            } else {
                String diag = "respNull=" + (resp == null)
                    + ", eventsNull=" + (resp != null && resp.getEvents() == null)
                    + ", eventsEmpty=" + (resp != null && resp.getEvents() != null && resp.getEvents().isEmpty());
                Object refl = resp != null ? resp.getTodayReflection() : null;
                if (refl != null) {
                    try {
                        String jsonStr = objectMapper.writeValueAsString(refl);
                        if (jsonStr.length() > 500) jsonStr = jsonStr.substring(0, 500) + "...";
                        diag += ", today_reflection=" + jsonStr;
                    } catch (Exception e) {
                        log.debug("today_reflection序列化失败", e);
                        diag += ", today_reflection_class=" + refl.getClass().getSimpleName();
                    }
                } else {
                    diag += ", today_reflection=null";
                }
                log.warn("懒加载今日事件: Python 返回空事件列表, persona={}, {}", personaId, diag);
            }
        } catch (Exception e) {
            log.warn("懒加载今日事件失败(下次心跳重试): persona={}, error={}", personaId, e.getMessage());
        }
    }

    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * ★ 启动时：为所有活跃 Persona 自动生成今日事件（如果还没有的话）
     * 不依赖 NapCat 心跳——防止 NapCat 未连接时事件永远不生成
     *
     * ★ 用 @EventListener(ApplicationReadyEvent) 替代 @PostConstruct：
     *   @PostConstruct 阻塞应用启动——如果 Python 未启动，Feign 超时(默认10s)
     *   会导致 NapCat WebSocket 连接不上（10060 错误）。
     *   ApplicationReadyEvent 在 Tomcat 已接收请求后触发，不阻塞启动。
     */
    @EventListener(org.springframework.boot.context.event.ApplicationReadyEvent.class)
    @Async
    public void startupGenerateEventsForAllActive() {
        if (!automationEnabled) return;
        try {
            List<Persona> active = personaService.listEnabled();
            if (active.isEmpty()) {
                log.info("无活跃 Persona，跳过启动事件生成");
                return;
            }
            log.info("启动事件生成: 发现 {} 个活跃 Persona", active.size());
            for (Persona p : active) {
                try {
                    if (!eventService.hasTodayEvents(p.getId())) {
                        lazyGenerateTodayEventsIfNeeded(p.getId());
                    } else {
                        registerForMinuteScan(p.getId());
                    }
                } catch (Exception e) {
                    log.warn("启动事件生成失败: persona={}, error={}", p.getId(), e.getMessage());
                }
            }
        } catch (Exception outer) {
            log.warn("启动事件生成跳过 (例如测试环境): {}", outer.getMessage());
        }
    }

    /**
     * Insert a test event that fires in N seconds — used for debugging proactive messaging.
     */
    public DailyEvent insertTestEvent(String personaId, int delaySeconds) {
        return insertTestEvent(personaId, delaySeconds, "moment", "忽然想向用户发一句: 在不在");
    }

    /**
     * Insert a test event with custom type and description.
     */
    public DailyEvent insertTestEvent(String personaId, int delaySeconds,
                                       String eventType, String description) {
        DailyEvent e = new DailyEvent();
        e.setId(UUID.randomUUID().toString());
        e.setPersonaId(personaId);
        e.setEventDate(LocalDate.now().format(DATE_FMT));
        e.setEventTime(LocalTime.now().plusSeconds(delaySeconds).format(TIME_FMT));
        e.setEventType(eventType != null ? eventType : "moment");
        e.setDescription(description != null ? description : "忽然想向用户发一句: 在不在");
        e.setIsActive(1);
        eventService.insertEvent(e);
        log.info("测试事件已插入: persona={}, time={}, delay={}s, type={}",
                personaId, e.getEventTime(), delaySeconds, e.getEventType());
        return e;
    }

    /**
     * 从当前时间补生成事件到凌晨。用于事件线断裂时。
     */
    private void fillEventsFromNow(String personaId, Persona persona) {
        try {
            EventGenerateRequest request = new EventGenerateRequest();
            request.setApiConfig(personaService.loadGlobalApiConfig());
            request.setPersonaConfig(buildPersonaConfigForEvent(persona));
            request.setRelationshipState(getCurrentRelationshipState(personaId));
            request.setTodayDate(LocalDate.now().format(DATE_FMT));
            request.setDayOfWeek(LocalDate.now().getDayOfWeek()
                    .getDisplayName(java.time.format.TextStyle.FULL, java.util.Locale.CHINESE));
            request.setYesterdayEvents(List.of());
            request.setTodayInnerThoughts(List.of());

            EventGenerateResponse resp = pythonClient.generateEvents(request);
            if (resp != null && resp.getEvents() != null && !resp.getEvents().isEmpty()) {
                eventService.insertEvents(personaId, convertEvents(resp.getEvents()));
                log.info("事件线补生成成功: persona={}, 新增{}条", personaId, resp.getEvents().size());
                processLifeStageTransition(personaId, resp);
                registerForMinuteScan(personaId);
            } else {
                String diag = "respNull=" + (resp == null) + ", eventsNull=" + (resp != null && resp.getEvents() == null);
                Object refl = resp != null ? resp.getTodayReflection() : null;
                if (refl != null) {
                    try {
                        String jsonStr = objectMapper.writeValueAsString(refl);
                        if (jsonStr.length() > 500) jsonStr = jsonStr.substring(0, 500) + "...";
                        diag += ", today_reflection=" + jsonStr;
                    } catch (Exception e) { log.debug("today_reflection序列化失败", e); }
                } else {
                    diag += ", today_reflection=null";
                }
                log.warn("事件线补生成: Python 返回空事件列表, persona={}, {}", personaId, diag);
            }
        } catch (Exception e) {
            log.warn("事件线补生成失败(下次扫描重试): persona={}, error={}",
                    personaId, e.getMessage());
        }
    }

    /** 获取当前关系状态的 Map 表示 */
    private Map<String, Object> getCurrentRelationshipState(String personaId) {
        RelationshipState state = relationshipEngine.getState(personaId);
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("trust", state.getTrust());
        map.put("closeness", state.getCloseness());
        map.put("tension", state.getTension());
        map.put("emotional_energy", state.getEmotionalEnergy());
        map.put("tension_pressure", state.getTensionPressure());
        map.put("contact_urge", state.getContactUrge());
        return map;
    }

    /** 为事件生成构建完整的 personaConfig（含生命阶段和地点） */
    private Map<String, Object> buildPersonaConfigForEvent(Persona persona) {
        Map<String, Object> cfg = new LinkedHashMap<>();
        cfg.put("id", persona.getId());
        cfg.put("name", persona.getName() != null ? persona.getName() : "AI网友");
        cfg.put("character_current_context", persona.getCharacterCurrentContext());
        cfg.put("life_stage", persona.getLifeStage());
        cfg.put("life_stage_detail", persona.getLifeStageDetail());
        cfg.put("current_location", persona.getCurrentLocation());

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

        return cfg;
    }

    /**
     * 处理 LLM 声明的生命阶段切换（全自动）
     * 调用时机：lazyGenerate / fillEventsFromNow / midnightEventGeneration 的响应处理中
     */
    @SuppressWarnings("unchecked")
    private void processLifeStageTransition(String personaId, EventGenerateResponse resp) {
        Object reflection = resp.getTodayReflection();
        if (reflection == null) return;

        try {
            Map<String, Object> refMap;
            if (reflection instanceof Map) {
                refMap = (Map<String, Object>) reflection;
            } else {
                refMap = objectMapper.readValue(reflection.toString(), Map.class);
            }
            Map<String, Object> transition = (Map<String, Object>) refMap.get("life_stage_transition");
            if (transition == null) {
                log.debug("今日反思中无 life_stage_transition 字段, persona={}", personaId);
                return;
            }

            Object shouldObj = transition.get("should_transition");
            boolean shouldTransition = shouldObj instanceof Boolean && (Boolean) shouldObj;
            if (!shouldTransition) {
                log.debug("今日反思中 should_transition=false（无身份变化）, persona={}", personaId);
                return;
            }

            String newLifeStage = String.valueOf(transition.getOrDefault("new_life_stage", ""));
            String newDetail = String.valueOf(transition.getOrDefault("new_life_stage_detail", ""));
            String newLocation = String.valueOf(transition.getOrDefault("new_location", ""));
            String reason = String.valueOf(transition.getOrDefault("transition_reason", ""));

            Persona persona = personaService.getById(personaId);
            if (persona != null) {
                String oldLifeStage = persona.getLifeStage();
                if (!newLifeStage.isEmpty()) persona.setLifeStage(newLifeStage);
                if (!newDetail.isEmpty()) persona.setLifeStageDetail(newDetail);
                if (!newLocation.isEmpty()) persona.setCurrentLocation(newLocation);

                String newContext = buildUpdatedContext(persona, newDetail, newLocation, reason);
                persona.setCharacterCurrentContext(newContext);

                com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<Persona> wrapper =
                    new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<>();
                wrapper.eq(Persona::getId, personaId)
                       .set(Persona::getLifeStage, persona.getLifeStage())
                       .set(Persona::getLifeStageDetail, persona.getLifeStageDetail())
                       .set(Persona::getCurrentLocation, persona.getCurrentLocation())
                       .set(Persona::getCharacterCurrentContext, persona.getCharacterCurrentContext());
                personaMapper.update(wrapper);
            }

            EventLog milestone = new EventLog();
            milestone.setId(UUID.randomUUID().toString());
            milestone.setPersonaId(personaId);
            milestone.setLogType("event");
            try {
                milestone.setDetailJson(objectMapper.writeValueAsString(Map.of(
                    "event", "life_stage_transition",
                    "new_life_stage", newLifeStage,
                    "new_life_stage_detail", newDetail,
                    "new_location", newLocation,
                    "transition_reason", reason
                )));
            } catch (Exception e) {
                log.debug("milestone detailJson构建失败, 使用fallback", e);
                milestone.setDetailJson("{\"event\":\"life_stage_transition\"}");
            }
            milestone.setOccurredAt(Instant.now().toString());
            eventLogMapper.insert(milestone);

            log.info("生命阶段切换: persona={}, {} -> {}, reason={}",
                    personaId, persona != null ? persona.getLifeStage() : "", newLifeStage, reason);
        } catch (Exception e) {
            log.warn("处理生命阶段切换失败(非致命): persona={}, error={}", personaId, e.getMessage());
        }
    }

    /** 合并更新后的 context 文本 */
    private String buildUpdatedContext(Persona persona, String newDetail, String newLocation, String reason) {
        String base = persona.getCharacterCurrentContext() != null
                ? persona.getCharacterCurrentContext() : "";
        StringBuilder sb = new StringBuilder(base);
        if (!newDetail.isEmpty()) {
            sb.append("\n【当前身份】").append(newDetail);
        }
        if (!newLocation.isEmpty()) {
            sb.append("\n【当前地点】").append(newLocation);
        }
        if (!reason.isEmpty()) {
            sb.append("\n【切换原因】").append(reason);
        }
        return sb.toString();
    }

    /** 将 Persona 注册到分钟级扫描队列 */
    private void registerForMinuteScan(String personaId) {
        personaScanLevel.put(personaId, ScanLevel.MINUTE_WATCH);
        log.debug("EVENT_SCAN[{}]: 注册到分钟扫描", personaId);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> loadImageApiConfig() {
        return personaService.loadImageApiConfig();
    }

    /** 将 Python 返回的事件原始对象转为 DailyEvent 实体 */
    @SuppressWarnings("unchecked")
    private List<DailyEvent> convertEvents(List<Object> rawEvents) {
        List<DailyEvent> result = new ArrayList<>();
        for (Object raw : rawEvents) {
            if (raw instanceof Map) {
                Map<String, Object> m = (Map<String, Object>) raw;
                DailyEvent e = new DailyEvent();
                e.setId(java.util.UUID.randomUUID().toString());
                e.setEventDate(LocalDate.now().format(DATE_FMT));
                e.setEventTime(String.valueOf(m.getOrDefault("time", m.getOrDefault("event_time", "12:00:00"))));
                e.setEventType(String.valueOf(m.getOrDefault("type", m.getOrDefault("event_type", "routine"))));
                e.setDescription(String.valueOf(m.getOrDefault("description", "")));
                e.setIsActive(1);
                result.add(e);
            }
        }
        return result;
    }

    /** 解析并应用 LLM 返回的关系增量（事件触发也可能影响好感） */
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

    private boolean isEnabled(Persona persona) {
        return persona != null && ("active".equals(persona.getStatus()) || "sleeping".equals(persona.getStatus()));
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
}
