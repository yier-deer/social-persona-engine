package com.socialpersona.message.scheduler;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.socialpersona.message.entity.ScheduledMessage;
import com.socialpersona.message.repository.MessageMapper;
import com.socialpersona.message.websocket.FrontendWebSocketHandler;
import com.socialpersona.message.websocket.QQWebSocketHandler;
import com.socialpersona.persona.entity.Persona;
import com.socialpersona.persona.service.PersonaService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.time.Instant;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class MessageScanScheduler {

    @Value("${app.automation.enabled:true}")
    private boolean automationEnabled = true;

    private static final Logger log = LoggerFactory.getLogger(MessageScanScheduler.class);
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm:ss");

    @Autowired
    private MessageMapper messageMapper;

    @Autowired
    private FrontendWebSocketHandler wsHandler;

    @Autowired
    private QQWebSocketHandler qqWs;

    @Autowired
    private PersonaService personaService;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private final ConcurrentHashMap<String, ScanLevel> personaLevels = new ConcurrentHashMap<>();

    private final ConcurrentHashMap<String, String> personaOwnerQq = new ConcurrentHashMap<>();

    enum ScanLevel {
        IDLE, MINUTE_WATCH, SECOND_WATCH
    }

    @Scheduled(fixedDelay = 3600000)
    public void hourlyScan() {
        if (!automationEnabled) return;
        for (Map.Entry<String, ScanLevel> entry : personaLevels.entrySet()) {
            if (entry.getValue() != ScanLevel.IDLE) continue;

            String now = LocalTime.now().format(TIME_FMT);
            String later = LocalTime.now().plusHours(1).format(TIME_FMT);
            int count = messageMapper.countInNextHour(entry.getKey(), now, later);

            if (count > 0) {
                personaLevels.put(entry.getKey(), ScanLevel.MINUTE_WATCH);
                log.debug("MSG_SCAN[{}]: IDLE → MINUTE_WATCH ({} msgs)", entry.getKey(), count);
            }
        }
    }

    @Scheduled(fixedDelay = 60000)
    public void minuteScan() {
        if (!automationEnabled) return;
        for (Map.Entry<String, ScanLevel> entry : personaLevels.entrySet()) {
            if (entry.getValue() != ScanLevel.MINUTE_WATCH) continue;

            String minuteStart = LocalTime.now().withSecond(0).format(TIME_FMT);
            String minuteEnd   = LocalTime.now().withSecond(59).format(TIME_FMT);
            int count = messageMapper.countInCurrentMinute(entry.getKey(), minuteStart, minuteEnd);

            if (count > 0) {
                personaLevels.put(entry.getKey(), ScanLevel.SECOND_WATCH);
                log.debug("MSG_SCAN[{}]: MINUTE_WATCH → SECOND_WATCH ({} msgs)", entry.getKey(), count);
                continue;
            }

            String now = LocalTime.now().format(TIME_FMT);
            String later = LocalTime.now().plusHours(1).format(TIME_FMT);
            int nextHourCount = messageMapper.countInNextHour(entry.getKey(), now, later);
            if (nextHourCount == 0) {
                personaLevels.put(entry.getKey(), ScanLevel.IDLE);
                log.debug("MSG_SCAN[{}]: MINUTE_WATCH → IDLE", entry.getKey());
            }
        }
    }

    @Scheduled(fixedDelay = 1000)
    public void secondScan() {
        if (!automationEnabled) return;
        for (Map.Entry<String, ScanLevel> entry : personaLevels.entrySet()) {
            if (entry.getValue() != ScanLevel.SECOND_WATCH) continue;

            String now = Instant.now().toString();
            String localTime = LocalTime.now().format(TIME_FMT);
            String oneSecondAgo = Instant.now().minusSeconds(1).toString();
            List<ScheduledMessage> msgs = messageMapper.findDueThisSecond(entry.getKey(), now, oneSecondAgo);
            if (msgs.isEmpty()) {
                msgs = messageMapper.findDue(entry.getKey(), now, localTime, 100);
            }

            if (msgs.isEmpty()) {
                String minuteStart = LocalTime.now().withSecond(0).format(TIME_FMT);
                String minuteEnd   = LocalTime.now().withSecond(59).format(TIME_FMT);
                int count = messageMapper.countInCurrentMinute(entry.getKey(), minuteStart, minuteEnd);
                if (count == 0) {
                    personaLevels.put(entry.getKey(), ScanLevel.MINUTE_WATCH);
                    log.debug("MSG_SCAN[{}]: SECOND_WATCH → MINUTE_WATCH", entry.getKey());
                }
                continue;
            }

            for (ScheduledMessage msg : msgs) {
                Persona persona = personaService.getById(msg.getPersonaId());
                if (persona == null || !"active".equals(persona.getStatus())) continue;
                try {
                    String json = objectMapper.writeValueAsString(Map.of(
                            "type", "message",
                            "personaId", msg.getPersonaId(),
                            "content", msg.getItemsJson(),
                            "mood", msg.getMood() != null ? msg.getMood() : "",
                            "timestamp", msg.getScheduledTime()
                    ));
                    wsHandler.sendToPersona(msg.getPersonaId(), json);
                } catch (Exception e) {
                    log.warn("秒级扫描推送失败[{}]: {}", entry.getKey(), e.getMessage());
                }

                try {
                    String text = extractTextFromItems(msg.getItemsJson());
                    if (text != null && !text.isEmpty()) {
                        String qq = persona.getOwnerQq();
                        if (qq != null && !qq.isEmpty()) {
                            qqWs.sendReply(persona.getAiQq(), qq, text);
                        }
                    }
                } catch (Exception e) {
                    log.warn("QQ推送失败[{}]: {}", entry.getKey(), e.getClass().getSimpleName());
                    continue;
                }

                msg.setIsSent(1);
                messageMapper.updateById(msg);
                log.debug("MSG_SCAN[{}]: sent msg {} at {}", entry.getKey(), msg.getId(), msg.getScheduledTime());
            }
        }
    }

    private String extractTextFromItems(String itemsJson) {
        if (itemsJson == null) return null;
        try {
            List<Map<String, Object>> items = objectMapper.readValue(itemsJson, new TypeReference<List<Map<String, Object>>>() {});
            StringBuilder sb = new StringBuilder();
            for (Map<String, Object> item : items) {
                if ("text".equals(item.get("type")) && item.get("content") != null) {
                    sb.append(item.get("content").toString());
                }
            }
            return sb.toString();
        } catch (Exception e) {
            return itemsJson;
        }
    }

    public void onMessageListChanged() {
        personaLevels.replaceAll((k, v) -> ScanLevel.IDLE);
        log.debug("MSG_SCAN: reset all to IDLE");
    }

    public void onMessageListChanged(String personaId) {
        personaLevels.put(personaId, ScanLevel.IDLE);
        log.debug("MSG_SCAN[{}]: reset to IDLE", personaId);
    }

    public void registerPersona(String personaId) {
        personaLevels.putIfAbsent(personaId, ScanLevel.IDLE);
    }

    public void unregisterPersona(String personaId) {
        personaLevels.remove(personaId);
        log.debug("MSG_SCAN: unregistered {}", personaId);
    }

    public void setOwnerQq(String personaId, String ownerQq) {
        if (ownerQq != null) {
            this.personaOwnerQq.put(personaId, ownerQq);
        } else {
            this.personaOwnerQq.remove(personaId);
        }
    }

    public ScanLevel getCurrentLevel(String personaId) {
        return personaLevels.getOrDefault(personaId, ScanLevel.IDLE);
    }
}
