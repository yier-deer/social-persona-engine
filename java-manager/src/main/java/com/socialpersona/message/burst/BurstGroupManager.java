package com.socialpersona.message.burst;

import com.socialpersona.message.entity.ScheduledMessage;
import com.socialpersona.message.repository.MessageMapper;
import com.socialpersona.message.websocket.FrontendWebSocketHandler;
import com.socialpersona.message.websocket.QQWebSocketHandler;
import com.socialpersona.middleware.InterruptListener;
import com.socialpersona.middleware.TypingSpeedSimulator;
import com.socialpersona.persona.entity.Persona;
import com.socialpersona.persona.service.PersonaService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.time.Instant;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 连发组管理器 —— now 消息的逐条延迟推送
 *
 * ★ 运作机制：
 *   同一 burst_group_id 的多条消息按 burst_order 排序。
 *   第 1 条 → WebSocket 立即推送（由 MessageService.dispatchReply 处理）
 *   后续 → 按打字速度 + 思考停顿逐条延迟推送（本类处理）
 *
 * ★ 为什么不能一次性发 3 条：
 *   真人不会一口气发 3 条——每打完一条会喘口气、想想下一条说什么。
 *   连发组模拟这个过程，逐条推送 + 打字延迟 → 用户感觉"对，真人在打字"。
 */
@Component
public class BurstGroupManager {

    @Value("${app.automation.enabled:true}")
    private boolean automationEnabled = true;

    private static final Logger log = LoggerFactory.getLogger(BurstGroupManager.class);

    /** 延迟发送线程池（2 线程足够——极少同时多个连发组活跃） */
    private final ScheduledExecutorService executor = Executors.newScheduledThreadPool(2);

    @Autowired
    private MessageMapper messageMapper;

    @Autowired
    private InterruptListener interruptListener;

    @Autowired
    private FrontendWebSocketHandler wsHandler;

    @Autowired
    private QQWebSocketHandler qqWs;

    @Autowired
    private PersonaService personaService;

    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 调度一个连发组的后续消息
     *
     * ★ 调用时机：MessageService.dispatchReply() 已发了第 1 条后调用
     *
     * @param personaId Persona UUID
     * @param group     同一 burst_group_id 的消息列表（含第 1 条）
     * @param persona   人格实体（用于取打字速度/依恋维度）
     */
    public void scheduleBurst(String personaId, List<ScheduledMessage> group, Persona persona) {
        if (!automationEnabled) return;
        if (group.size() <= 1) return;  // 只有 1 条不需要调度

        // 按 burst_order 排序
        group.sort(Comparator.comparingInt(m -> m.getBurstOrder() != null ? m.getBurstOrder() : 0));

        // ★ 注册中断：用户在连发期间发新消息→作废剩余
        interruptListener.register(personaId);

        // 逐条计算累计延迟并调度
        long cumulativeDelay = 0;
        for (int i = 1; i < group.size(); i++) {
            ScheduledMessage msg = group.get(i);

            // ★ 累计打前 i 条消息的耗时
            String text = extractText(group.get(i).getItemsJson());
            double anxiety  = persona.getAttachmentAnxiety()  != null ? persona.getAttachmentAnxiety()  : 0.5;
            double avoidance = persona.getAttachmentAvoidance() != null ? persona.getAttachmentAvoidance() : 0.3;

            cumulativeDelay += TypingSpeedSimulator.typingDelay(
                    text, persona.getInputMethod(), persona.getTypingSpeed());
            cumulativeDelay += TypingSpeedSimulator.thinkingPause(anxiety, avoidance);

            final int index = i;
            final long delay = cumulativeDelay;

            executor.schedule(() -> {
                if (!automationEnabled) return;
                Persona current = personaService.getById(personaId);
                if (current == null || !"active".equals(current.getStatus())) return;
                // ★ 发送前检查中断
                if (interruptListener.isInterrupted(personaId)) {
                    log.debug("Burst interrupted for persona {}, skipping item {}", personaId, index);
                    return;
                }

                String textContent = extractText(msg.getItemsJson());
                try {
                    String json = objectMapper.writeValueAsString(Map.of(
                            "type", "message",
                            "personaId", personaId,
                            "content", textContent,
                            "mood", msg.getMood() != null ? msg.getMood() : "",
                            "timestamp", Instant.now().toString()
                    ));
                    wsHandler.sendToPersona(personaId, json);
                } catch (Exception e) {
                    log.warn("Burst前端推送失败: {}", e.getMessage());
                }

                // ★ 延迟消息也推 QQ
                String ownerQq = current.getOwnerQq();
                if (ownerQq != null && !ownerQq.isEmpty() && textContent != null && !textContent.isEmpty()) {
                    try {
                        qqWs.sendReply(current.getAiQq(), ownerQq, textContent);
                    } catch (Exception e) {
                        log.warn("Burst QQ发送失败: {}", e.getClass().getSimpleName());
                        return;
                    }
                }

                // 标记已发送
                msg.setIsSent(1);
                messageMapper.updateById(msg);

                // 最后一条发完 → 清中断
                if (index == group.size() - 1) {
                    interruptListener.clear(personaId);
                }
            }, cumulativeDelay, TimeUnit.MILLISECONDS);
        }
    }

    /**
     * 从 items_json 中提取文本内容（用于计算打字时间）
     *
     * ★ 为什么只提取 text 项：image 不需要打字时间
     */
    private String extractText(String itemsJson) {
        if (itemsJson == null) return "";
        try {
            List<Map<String, Object>> items = objectMapper.readValue(
                itemsJson, new com.fasterxml.jackson.core.type.TypeReference<List<Map<String, Object>>>() {});
            StringBuilder sb = new StringBuilder();
            for (Map<String, Object> item : items) {
                if ("text".equals(item.get("type")) && item.get("content") != null) {
                    sb.append(item.get("content").toString());
                }
            }
            return sb.toString();
        } catch (Exception e) {
            log.warn("解析itemsJson失败，回退到原始字符串: {}", e.getMessage());
            return itemsJson;
        }
    }
}
