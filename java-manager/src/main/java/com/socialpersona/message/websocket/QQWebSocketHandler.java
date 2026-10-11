package com.socialpersona.message.websocket;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.socialpersona.persona.entity.Persona;
import com.socialpersona.persona.service.PersonaService;
import com.socialpersona.event.scheduler.EventTriggerScheduler;
import jakarta.websocket.*;
import jakarta.websocket.server.ServerEndpoint;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

@Component
@ServerEndpoint(value = "/ws/qq", configurator = QQWebSocketConfigurator.class)
public class QQWebSocketHandler implements ApplicationContextAware {

    private static final Logger log = LoggerFactory.getLogger(QQWebSocketHandler.class);
    private static final ObjectMapper objectMapper = new ObjectMapper();

    private static ApplicationContext applicationContext;
    private final PersonaService personaService;

    private static final ConcurrentHashMap<String, Session> napCatSessions = new ConcurrentHashMap<>();
    private static final Set<String> pendingEventGeneration = ConcurrentHashMap.newKeySet();

    public QQWebSocketHandler(PersonaService personaService) {
        this.personaService = personaService;
    }

    @Override
    public void setApplicationContext(ApplicationContext ctx) {
        applicationContext = ctx;
    }

    @OnOpen
    public void onOpen(Session session) {
        String sessionId = session.getId();
        log.info("QQ WebSocket 连接: NapCat 已连入，session={}", sessionId);
    }

    @OnClose
    public void onClose(Session session) {
        String sessionId = session.getId();
        napCatSessions.entrySet().removeIf(e -> e.getValue().equals(session));
        log.info("QQ WebSocket 断开: session={}", sessionId);
    }

    @OnError
    public void onError(Session session, Throwable error) {
        log.error("QQ WebSocket 异常: {}", error.getMessage());
    }

    @OnMessage
    public void onMessage(String rawMessage, Session session) {
        try {
            JsonNode json = objectMapper.readTree(rawMessage);
            String postType = json.path("post_type").asText();

            if ("meta_event".equals(postType)) {
                handleMetaEvent(json, session);
                return;
            }
            if (!"message".equals(postType)) return;

            String messageType = json.path("message_type").asText();
            String rawMsg = json.path("raw_message").asText(null);
            if (rawMsg == null || rawMsg.isEmpty()) return;

            String userId = json.path("user_id").asText();
            if (userId.isEmpty()) userId = String.valueOf(json.path("user_id").asLong());

            String selfId = json.path("self_id").asText();
            if (selfId.isEmpty()) selfId = String.valueOf(json.path("self_id").asLong());

            if (!selfId.isEmpty()) {
                napCatSessions.put(selfId, session);
            }

            if ("group".equals(messageType)) {
                log.debug("群消息暂不处理: group_id={}, user_id={}", json.path("group_id").asText(), userId);
                return;
            }
            if (!"private".equals(messageType)) return;

            Persona persona = personaService.findByAiQQ(selfId);
            if (persona == null) {
                log.debug("未找到匹配AI: self_id={}", selfId);
                return;
            }

            if (!"active".equals(persona.getStatus())) {
                log.debug("QQ 消息暂不处理: persona={}, status={}", persona.getId(), persona.getStatus());
                return;
            }

            if (persona.getOwnerQq() == null || persona.getOwnerQq().isEmpty()) {
                log.debug("AI 未配置 owner_qq: persona={}", persona.getId());
                return;
            }
            if (!userId.equals(persona.getOwnerQq())) {
                log.debug("非主人消息忽略: user_id={}, owner_qq={}", userId, persona.getOwnerQq());
                return;
            }

            if (applicationContext != null) {
                QQAsyncMessageHandler asyncHandler =
                        applicationContext.getBean(QQAsyncMessageHandler.class);
                asyncHandler.handleMessage(persona.getId(), rawMsg);
            }

        } catch (Exception e) {
            log.error("QQ消息处理失败: {}", e.getMessage(), e);
        }
    }

    private void handleMetaEvent(JsonNode json, Session session) {
        String metaType = json.path("meta_event_type").asText();
        if ("heartbeat".equals(metaType) || "lifecycle".equals(metaType)) {
            String selfId = json.path("self_id").asText();
            // Compatibility for older adapters; OneBot V11 identifies the bot at the top level.
            if (selfId.isEmpty()) selfId = json.path("status").path("self_id").asText();
            if (!selfId.isEmpty()) {
                if (json.path("status").has("online") && !json.path("status").path("online").asBoolean()) {
                    napCatSessions.remove(selfId, session);
                    return;
                }
                napCatSessions.put(selfId, session);
                triggerLazyEventGeneration(selfId);
            }
        }
    }

    /**
     * 心跳时触发今日事件懒加载 —— 异步执行，不阻塞 WebSocket 线程
     */
    private void triggerLazyEventGeneration(String selfId) {
        ApplicationContext context = applicationContext;
        if (context == null || !pendingEventGeneration.add(selfId)) return;
        CompletableFuture.runAsync(() -> {
            try {
                Persona persona = personaService.findByAiQQ(selfId);
                if (persona == null || !("active".equals(persona.getStatus()) || "sleeping".equals(persona.getStatus()))) return;
                context.getBean(EventTriggerScheduler.class).lazyGenerateTodayEventsIfNeeded(persona.getId());
            } catch (Exception e) {
                log.debug("懒加载触发失败: bot={}, type={}", selfId, e.getClass().getSimpleName());
            } finally {
                pendingEventGeneration.remove(selfId);
            }
        });
    }

    public List<String> getConnectedBotIds() {
        return napCatSessions.entrySet().stream()
                .filter(entry -> entry.getValue().isOpen())
                .map(Map.Entry::getKey).sorted().toList();
    }

    /** Compatibility for callers that can operate only when exactly one bot is connected. */
    @Deprecated
    public void sendReply(String userId, String message) {
        sendReply(singleConnectedBot(), userId, message);
    }

    public void sendReply(String aiQq, String userId, String message) {
        if (aiQq == null || aiQq.isBlank() || userId == null || userId.isBlank()) {
            throw new IllegalStateException("角色尚未绑定机器人 QQ 和主人 QQ");
        }
        sendWithRetry(aiQq, userId, message, 0);
    }

    @Deprecated
    public void sendImage(String userId, String imagePath, String caption) {
        sendImage(singleConnectedBot(), userId, imagePath, caption);
    }

    public void sendImage(String aiQq, String userId, String imagePath, String caption) {
        String fileUri = Path.of(imagePath).toAbsolutePath().toUri().toString();
        String cqCode = "[CQ:image,file=" + fileUri + "]";
        String message = (caption != null && !caption.isEmpty()) ? caption + " " + cqCode : cqCode;
        sendReply(aiQq, userId, message);
    }

    private String singleConnectedBot() {
        List<String> bots = getConnectedBotIds();
        if (bots.size() != 1) throw new IllegalStateException("发送消息必须指定已连接的机器人 QQ");
        return bots.get(0);
    }

    private void sendWithRetry(String aiQq, String userId, String message, int attempt) {
        Session targetSession = napCatSessions.get(aiQq);
        if (targetSession == null || !targetSession.isOpen()) {
            throw new IllegalStateException("绑定的机器人 QQ 尚未连接 NapCat");
        }
        try {
            Map<String, Object> action = Map.of(
                    "action", "send_private_msg",
                    "params", Map.of("user_id", userId, "message", message)
            );
            String json = objectMapper.writeValueAsString(action);
            synchronized (targetSession) {
                targetSession.getBasicRemote().sendText(json);
            }
        } catch (IOException | IllegalStateException e) {
            if (attempt < 2) {
                try {
                    Thread.sleep(500L * (attempt + 1));
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("QQ 发送已中断");
                }
                sendWithRetry(aiQq, userId, message, attempt + 1);
            } else {
                throw new IllegalStateException("QQ 消息发送失败，请检查 NapCat 连接");
            }
        }
    }
}
