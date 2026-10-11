package com.socialpersona.message.service;

import com.socialpersona.error.SystemErrorHandler;
import com.socialpersona.event.service.EventService;
import com.socialpersona.gateway.PythonClient;
import com.socialpersona.gateway.dto.ImageGenerateRequest;
import com.socialpersona.gateway.dto.ImageGenerateResponse;
import com.socialpersona.gateway.dto.MessageRequest;
import com.socialpersona.gateway.dto.MessageResponse;
import com.socialpersona.message.burst.BurstGroupManager;
import com.socialpersona.message.repository.MessageMapper;
import com.socialpersona.message.scheduler.MessageScanScheduler;
import com.socialpersona.message.websocket.FrontendWebSocketHandler;
import com.socialpersona.message.websocket.QQWebSocketHandler;
import com.socialpersona.middleware.InterruptListener;
import com.socialpersona.middleware.MessageSplitter;
import com.socialpersona.persona.entity.Persona;
import com.socialpersona.persona.service.PersonaService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.socialpersona.event.entity.EventLog;
import com.socialpersona.event.repository.EventLogMapper;
import com.socialpersona.relationship.engine.RelationshipEngine;
import com.socialpersona.relationship.entity.RelationshipState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * MessageService 单元测试 —— Mock 全部外部依赖，验证核心调用链
 *
 * 重点验证：
 *   1. handleUserMessage 完整调用链（PythonClient → dispatchReply）
 *   2. dispatchReply 即时推送逻辑（QQ + 前端）
 *   3. extractReplyText 字段解析
 *   4. 异常路径处理
 */
@ExtendWith(MockitoExtension.class)
public class MessageServiceTest {

    @Mock private MessageMapper messageMapper;
    @Mock private PersonaService personaService;
    @Mock private PythonClient pythonClient;
    @Mock private MessageScanScheduler scanScheduler;
    @Mock private BurstGroupManager burstGroupManager;
    @Mock private FrontendWebSocketHandler frontendWs;
    @Mock private QQWebSocketHandler qqWs;
    @Mock private EventLogMapper eventLogMapper;
    @Mock private RelationshipEngine relationshipEngine;
    @Mock private InterruptListener interruptListener;
    @Mock private SystemErrorHandler systemErrorHandler;
    @Mock private EventService eventService;

    @InjectMocks
    private MessageService messageService;

    private Persona testPersona;
    @TempDir private java.nio.file.Path configDirectory;
    private java.nio.file.Path configPath;

    @BeforeEach
    public void setUp() throws Exception {
        configPath = configDirectory.resolve("system_config.json");
        java.nio.file.Files.writeString(configPath, "{}");
        PersonaService configReader = new PersonaService();
        ReflectionTestUtils.setField(configReader, "configFilePath", configPath.toString());
        lenient().when(personaService.loadGlobalApiConfig()).thenAnswer(call -> configReader.loadGlobalApiConfig());
        lenient().when(personaService.loadImageApiConfig()).thenAnswer(call -> configReader.loadImageApiConfig());
        testPersona = new Persona();
        testPersona.setId("d926f28e-ea21-4f6a-8d69-9ed3dba6052b");
        testPersona.setName("小奈");
        testPersona.setAiQq("2387511709");
        testPersona.setOwnerQq("1875552542");
        testPersona.setAttachmentAnxiety(0.5);
        testPersona.setAttachmentAvoidance(0.3);
        testPersona.setInitiativeTendency(0.6);
        testPersona.setStatus("active");
    }

    private RelationshipState createDefaultRelState() {
        RelationshipState state = new RelationshipState();
        state.setPersonaId("d926f28e-ea21-4f6a-8d69-9ed3dba6052b");
        state.setTrust(50.0);
        state.setCloseness(20.0);
        state.setTension(10.0);
        state.setEmotionalEnergy(30.0);
        state.setTensionPressure(0.0);
        state.setContactUrge(0.0);
        return state;
    }

    // ==================== handleUserMessage ====================

    /**
     * ★ 核心验收场景：Python 返回 should_reply=true + raw_text
     *   → dispatchReply 被调用 → QQ 即时推送
     */
    @Test
    public void testHandleUserMessageWithReply() throws Exception {
        when(personaService.getById("d926f28e-ea21-4f6a-8d69-9ed3dba6052b")).thenReturn(testPersona);
        when(relationshipEngine.getState("d926f28e-ea21-4f6a-8d69-9ed3dba6052b")).thenReturn(createDefaultRelState());

        Map<String, String> replyMap = new LinkedHashMap<>();
        replyMap.put("raw_text", "你好呀[SPLIT]今天过得怎么样？");
        Map<String, Object> innerThought = new LinkedHashMap<>();
        innerThought.put("raw_thought", "还不错");
        innerThought.put("attitude", "positive");
        innerThought.put("should_remember", false);

        MessageResponse mockResponse = new MessageResponse();
        mockResponse.setShouldReply(true);
        mockResponse.setReply(replyMap);
        mockResponse.setInnerThought(innerThought);
        when(pythonClient.handleMessage(any(MessageRequest.class))).thenReturn(mockResponse);

        messageService.handleUserMessage("d926f28e-ea21-4f6a-8d69-9ed3dba6052b", "你好");

        verify(pythonClient).handleMessage(any(MessageRequest.class));
        verify(scanScheduler).registerPersona("d926f28e-ea21-4f6a-8d69-9ed3dba6052b");
        verify(messageMapper, atLeast(1)).insert(any(com.socialpersona.message.entity.ScheduledMessage.class));
        verify(scanScheduler).onMessageListChanged();
        verify(scanScheduler).setOwnerQq("d926f28e-ea21-4f6a-8d69-9ed3dba6052b", "1875552542");

        ArgumentCaptor<EventLog> eventLogCaptor = ArgumentCaptor.forClass(EventLog.class);
        verify(eventLogMapper, atLeast(2)).insert(eventLogCaptor.capture());
        List<EventLog> capturedLogs = eventLogCaptor.getAllValues();
        EventLog innerThoughtLog = capturedLogs.stream()
                .filter(log -> "inner_thought".equals(log.getLogType()))
                .findFirst().orElse(null);
        assertNotNull(innerThoughtLog, "应存在 inner_thought 类型的事件日志");
        assertNotNull(innerThoughtLog.getDetailJson(), "event_log detailJson 不能为 null");
        assertTrue(innerThoughtLog.getDetailJson().contains("raw_thought"), "detailJson 应包含内心独白");
        assertNotNull(innerThoughtLog.getOccurredAt(), "event_log occurredAt 不能为 null");
        assertEquals("d926f28e-ea21-4f6a-8d69-9ed3dba6052b", innerThoughtLog.getPersonaId());
    }

    /**
     * ★ 核心验收场景：dispatchReply 第一条消息应即时推送 QQ
     */
    @Test
    public void testDispatchReplyPushesFirstMessageToQQ() {
        when(personaService.getById("d926f28e-ea21-4f6a-8d69-9ed3dba6052b")).thenReturn(testPersona);

        messageService.dispatchReply(
                "d926f28e-ea21-4f6a-8d69-9ed3dba6052b",
                "今天天气真好",
                "[]", "happy", null
        );

        verify(qqWs).sendReply(eq("2387511709"), eq("1875552542"), eq("今天天气真好"));
        verify(scanScheduler).setOwnerQq("d926f28e-ea21-4f6a-8d69-9ed3dba6052b", "1875552542");
        verify(scanScheduler).onMessageListChanged();
        verify(eventLogMapper, never()).insert(any(EventLog.class));
    }

    /**
     * dispatchReply: innerThoughtJson 非空 → event_log 被写入
     */
    @Test
    public void testDispatchReplyWithInnerThought() {
        when(personaService.getById("d926f28e-ea21-4f6a-8d69-9ed3dba6052b")).thenReturn(testPersona);

        messageService.dispatchReply(
                "d926f28e-ea21-4f6a-8d69-9ed3dba6052b",
                "你好呀",
                "[]", "happy",
                "{\"raw_thought\":\"还不错\",\"attitude\":\"positive\"}"
        );

        verify(qqWs).sendReply(eq("2387511709"), eq("1875552542"), eq("你好呀"));
        verify(eventLogMapper).insert(any(EventLog.class));
    }

    /**
     * Python 返回 should_reply=false → 不调 dispatchReply
     */
    @Test
    public void testHandleUserMessageNoReply() throws Exception {
        when(personaService.getById("d926f28e-ea21-4f6a-8d69-9ed3dba6052b")).thenReturn(testPersona);
        when(relationshipEngine.getState("d926f28e-ea21-4f6a-8d69-9ed3dba6052b")).thenReturn(createDefaultRelState());

        MessageResponse mockResponse = new MessageResponse();
        mockResponse.setShouldReply(false);
        when(pythonClient.handleMessage(any(MessageRequest.class))).thenReturn(mockResponse);

        messageService.handleUserMessage("d926f28e-ea21-4f6a-8d69-9ed3dba6052b", "你好");

        verify(pythonClient).handleMessage(any(MessageRequest.class));
        // dispatchReply 不应被调用
        verify(qqWs, never()).sendReply(anyString(), anyString(), anyString());
    }

    /**
     * Python 返回 null → 静默忽略，不抛异常
     */
    @Test
    public void testHandleUserMessageNullResponse() throws Exception {
        when(personaService.getById("d926f28e-ea21-4f6a-8d69-9ed3dba6052b")).thenReturn(testPersona);
        when(relationshipEngine.getState("d926f28e-ea21-4f6a-8d69-9ed3dba6052b")).thenReturn(createDefaultRelState());
        when(pythonClient.handleMessage(any(MessageRequest.class))).thenReturn(null);

        messageService.handleUserMessage("d926f28e-ea21-4f6a-8d69-9ed3dba6052b", "你好");

        verify(qqWs, never()).sendReply(anyString(), anyString(), anyString());
        verify(eventLogMapper, atLeastOnce()).insert(argThat((EventLog log) -> "system_error".equals(log.getLogType())));
    }

    /**
     * Persona 不存在 → 提前返回
     */
    @Test
    public void testHandleUserMessagePersonaNotFound() {
        when(personaService.getById("nonexistent")).thenReturn(null);

        messageService.handleUserMessage("nonexistent", "你好");

        verify(pythonClient, never()).handleMessage(any());
        verify(qqWs, never()).sendReply(anyString(), anyString(), anyString());
    }

    /**
     * Python 调用抛异常 → catch 住，调 SystemErrorHandler 降级
     */
    @Test
    public void testHandleUserMessagePythonException() throws Exception {
        when(personaService.getById("d926f28e-ea21-4f6a-8d69-9ed3dba6052b")).thenReturn(testPersona);
        when(relationshipEngine.getState("d926f28e-ea21-4f6a-8d69-9ed3dba6052b")).thenReturn(createDefaultRelState());
        when(pythonClient.handleMessage(any(MessageRequest.class)))
                .thenThrow(new RuntimeException("Python 超时"));

        when(systemErrorHandler.incrementFailCount("d926f28e-ea21-4f6a-8d69-9ed3dba6052b")).thenReturn(1);

        messageService.handleUserMessage("d926f28e-ea21-4f6a-8d69-9ed3dba6052b", "你好");

        // 验证降级回复被推送
        verify(qqWs, atLeastOnce()).sendReply(anyString(), anyString(), contains("[系统提示]"));
        verify(systemErrorHandler).incrementFailCount("d926f28e-ea21-4f6a-8d69-9ed3dba6052b");
    }

    /**
     * buildPersonaConfig: MessageRequest 的 personaConfig 应包含 relationship_phase
     */
    @Test
    public void testPersonaConfigIncludesRelationshipPhase() throws Exception {
        testPersona.setRelationshipPhase("friend");

        when(personaService.getById("d926f28e-ea21-4f6a-8d69-9ed3dba6052b")).thenReturn(testPersona);
        when(relationshipEngine.getState("d926f28e-ea21-4f6a-8d69-9ed3dba6052b")).thenReturn(createDefaultRelState());

        Map<String, String> replyMap = new LinkedHashMap<>();
        replyMap.put("raw_text", "测试回复");
        MessageResponse mockResponse = new MessageResponse();
        mockResponse.setShouldReply(true);
        mockResponse.setReply(replyMap);
        when(pythonClient.handleMessage(any(MessageRequest.class))).thenReturn(mockResponse);

        messageService.handleUserMessage("d926f28e-ea21-4f6a-8d69-9ed3dba6052b", "你好");

        ArgumentCaptor<MessageRequest> captor = ArgumentCaptor.forClass(MessageRequest.class);
        verify(pythonClient).handleMessage(captor.capture());
        MessageRequest req = captor.getValue();

        @SuppressWarnings("unchecked")
        Map<String, Object> cfg = (Map<String, Object>) req.getPersonaConfig();
        assertNotNull(cfg, "personaConfig 不应为 null");
        assertEquals("friend", cfg.get("relationship_phase"), "personaConfig 应注入 relationship_phase");
    }

    /**
     * 未配置图片提供商时 imageConfig 为 null
     */
    @Test
    public void testImageConfigNullWhenNotConfigured() throws Exception {
        // 保存原始配置，清空图片字段，保证 loadImageApiConfig 返回 null
        java.nio.file.Path cfgPath = configPath;
        boolean existed = java.nio.file.Files.exists(cfgPath);
        byte[] backup = existed ? java.nio.file.Files.readAllBytes(cfgPath) : null;
        try {
            if (existed) {
                @SuppressWarnings("unchecked")
                java.util.Map<String, Object> cfg = new ObjectMapper().readValue(backup, java.util.Map.class);
                cfg.remove("imageProvider");
                cfg.remove("imageApiKeyEncrypted");
                cfg.remove("imageBaseUrl");
                cfg.remove("imageModel");
                java.nio.file.Files.write(cfgPath, new ObjectMapper().writeValueAsBytes(cfg));
            }

            when(personaService.getById("d926f28e-ea21-4f6a-8d69-9ed3dba6052b")).thenReturn(testPersona);
            when(personaService.loadImageApiConfig()).thenReturn(null);
            when(relationshipEngine.getState("d926f28e-ea21-4f6a-8d69-9ed3dba6052b")).thenReturn(createDefaultRelState());

            Map<String, String> replyMap = new LinkedHashMap<>();
            replyMap.put("raw_text", "你好");
            MessageResponse mockResponse = new MessageResponse();
            mockResponse.setShouldReply(true);
            mockResponse.setReply(replyMap);
            when(pythonClient.handleMessage(any(MessageRequest.class))).thenReturn(mockResponse);

            messageService.handleUserMessage("d926f28e-ea21-4f6a-8d69-9ed3dba6052b", "你好");

            ArgumentCaptor<MessageRequest> captor = ArgumentCaptor.forClass(MessageRequest.class);
            verify(pythonClient, atLeastOnce()).handleMessage(captor.capture());
            MessageRequest req = captor.getValue();
            assertNull(req.getImageConfig(), "未配置图片提供商时 imageConfig 应为 null");
        } finally {
            // 恢复原始配置
            if (backup != null) {
                java.nio.file.Files.write(cfgPath, backup);
            }
        }
    }

    // ==================== extractReplyText ====================

    /**
     * ★ extractReplyText 应能提取 Python 返回的 "raw_text" 字段
     */
    @Test
    public void testExtractReplyTextFromRawText() {
        Map<String, Object> replyMap = new LinkedHashMap<>();
        replyMap.put("raw_text", "hello world");

        String result = invokeExtractReplyText(replyMap);
        assertEquals("hello world", result);
    }

    /**
     * extractReplyText fallback 到 "text" 字段
     */
    @Test
    public void testExtractReplyTextFallbackToText() {
        Map<String, Object> replyMap = new LinkedHashMap<>();
        replyMap.put("text", "你好");

        String result = invokeExtractReplyText(replyMap);
        assertEquals("你好", result);
    }

    /**
     * extractReplyText 无文本字段 → 返回 toString
     */
    @Test
    public void testExtractReplyTextNoTextFields() {
        Map<String, Object> replyMap = new LinkedHashMap<>();
        replyMap.put("type", "image");

        String result = invokeExtractReplyText(replyMap);
        assertNotNull(result);
    }

    /**
     * extractReplyText null → 返回 null
     */
    @Test
    public void testExtractReplyTextNull() {
        String result = invokeExtractReplyText(null);
        assertNull(result);
    }

    /**
     * extractReplyText String 直接返回
     */
    @Test
    public void testExtractReplyTextDirectString() {
        String result = invokeExtractReplyText("\"Hello\"");
        assertEquals("\"Hello\"", result);
    }

    // ==================== dispatchReply 无 owner_qq ====================

    /**
     * Persona 没有 owner_qq → 不推 QQ
     */
    @Test
    public void testDispatchReplyWithoutOwnerQq() {
        testPersona.setOwnerQq(null);
        when(personaService.getById(anyString())).thenReturn(testPersona);

        messageService.dispatchReply(
                "d926f28e-ea21-4f6a-8d69-9ed3dba6052b",
                "测试消息",
                "[]", null, null
        );

        verify(qqWs, never()).sendReply(anyString(), anyString(), anyString());
    }

    // ==================== dispatchReply 多段文本 ====================

    /**
     * dispatchReply 含 [SPLIT] → 第一条标记已发送，后续未发送
     */
    @Test
    public void testDispatchReplyWithSplitSetsFirstSent() {
        when(personaService.getById(anyString())).thenReturn(testPersona);

        messageService.dispatchReply(
                "d926f28e-ea21-4f6a-8d69-9ed3dba6052b",
                "第一段[SPLIT]第二段[SPLIT]第三段",
                "[]", null, null
        );

        ArgumentCaptor<com.socialpersona.message.entity.ScheduledMessage> captor =
                ArgumentCaptor.forClass(com.socialpersona.message.entity.ScheduledMessage.class);
        verify(messageMapper, times(3)).insert(captor.capture());

        List<com.socialpersona.message.entity.ScheduledMessage> msgs = captor.getAllValues();
        assertEquals(1, msgs.get(0).getIsSent(), "第一条应标记已发送");
        assertEquals(0, msgs.get(1).getIsSent(), "第二条应标记未发送");
        assertEquals(0, msgs.get(2).getIsSent(), "第三条应标记未发送");

        // 仅第一条推 QQ
        verify(qqWs).sendReply(eq("2387511709"), anyString(), eq("第一段"));
    }

    // ==================== 图片管线全流程 ====================

    /**
     * ★ 全流程：配置就绪 + Python 返回成功 → sendImage 被调用 → 不发重复文本
     */
    @Test
    public void testDispatchReplyWithImageItemFullPipeline() throws Exception {
        java.nio.file.Path cfgPath = configPath;
        boolean existed = java.nio.file.Files.exists(cfgPath);
        byte[] backup = existed ? java.nio.file.Files.readAllBytes(cfgPath) : null;
        try {
            @SuppressWarnings("unchecked")
            java.util.Map<String, Object> cfg = existed
                    ? new ObjectMapper().readValue(backup, java.util.Map.class)
                    : new java.util.LinkedHashMap<>();
            cfg.put("imageProvider", "custom_image");
            cfg.put("imageApiKeyEncrypted",
                    java.util.Base64.getEncoder().encodeToString("img-key-test-123".getBytes()));
            cfg.put("imageBaseUrl", "https://ark.cn-beijing.volces.com/api/v3");
            cfg.put("imageModel", "doubao-seedream-4-5-251128");
            java.nio.file.Files.write(cfgPath, new ObjectMapper().writeValueAsBytes(cfg));

            String realImg = "E:\\Trae\\Project\\Netizen-Simulator-project\\Netizen-Simulator\\java-manager\\data\\generated_images\\1.jpg";

            ImageGenerateResponse mockResp = new ImageGenerateResponse();
            mockResp.setSuccess(true);
            mockResp.setLocalPath(realImg);
            when(pythonClient.generateImage(any(ImageGenerateRequest.class))).thenReturn(mockResp);

            when(personaService.getById(anyString())).thenReturn(testPersona);

            String itemsJson = "[{\"type\":\"text\",\"content\":\"啧，给你看一眼\"},"
                    + "{\"type\":\"image\",\"content_prompt\":\"一张校园风景照\",\"generation_mode\":\"sync\"}]";

            messageService.dispatchReply(
                    "d926f28e-ea21-4f6a-8d69-9ed3dba6052b",
                    "随便", itemsJson, null, null);

            verify(pythonClient).generateImage(any(ImageGenerateRequest.class));
            verify(qqWs).sendImage(eq("2387511709"), eq("1875552542"),
                    org.mockito.ArgumentMatchers.contains("1.jpg"),
                    org.mockito.ArgumentMatchers.contains("啧，给你看一眼"));
            verify(qqWs, never()).sendReply(eq("2387511709"), eq("1875552542"),
                    org.mockito.ArgumentMatchers.contains("校园风景"));
        } finally {
            if (backup != null) java.nio.file.Files.write(cfgPath, backup);
        }
    }

    /**
     * ★ 图片 API 失败 → 静默跳过，不影响文本发送
     */
    @Test
    public void testDispatchReplyImageApiFailsStillSendsText() throws Exception {
        java.nio.file.Path cfgPath = configPath;
        boolean existed = java.nio.file.Files.exists(cfgPath);
        byte[] backup = existed ? java.nio.file.Files.readAllBytes(cfgPath) : null;
        try {
            @SuppressWarnings("unchecked")
            java.util.Map<String, Object> cfg = existed
                    ? new ObjectMapper().readValue(backup, java.util.Map.class)
                    : new java.util.LinkedHashMap<>();
            cfg.put("imageProvider", "custom_image");
            cfg.put("imageApiKeyEncrypted",
                    java.util.Base64.getEncoder().encodeToString("img-key-test-123".getBytes()));
            cfg.put("imageBaseUrl", "https://ark.cn-beijing.volces.com/api/v3");
            cfg.put("imageModel", "doubao-seedream-4-5-251128");
            java.nio.file.Files.write(cfgPath, new ObjectMapper().writeValueAsBytes(cfg));

            ImageGenerateResponse mockResp = new ImageGenerateResponse();
            mockResp.setSuccess(false);
            mockResp.setError("API rate limit");
            when(pythonClient.generateImage(any(ImageGenerateRequest.class))).thenReturn(mockResp);

            when(personaService.getById(anyString())).thenReturn(testPersona);

            String itemsJson = "[{\"type\":\"text\",\"content\":\"看这个\"},"
                    + "{\"type\":\"image\",\"content_prompt\":\"一张图\",\"generation_mode\":\"sync\"}]";

            messageService.dispatchReply(
                    "d926f28e-ea21-4f6a-8d69-9ed3dba6052b",
                    "随便", itemsJson, null, null);

            verify(pythonClient).generateImage(any(ImageGenerateRequest.class));
            verify(qqWs, never()).sendImage(anyString(), anyString(), anyString(), anyString());
            verify(qqWs).sendReply(eq("2387511709"), eq("1875552542"), eq("看这个"));
        } finally {
            if (backup != null) java.nio.file.Files.write(cfgPath, backup);
        }
    }

    /**
     * ★ 未配置图片 → generateAndGetImage 返回 null → 只发文本
     */
    @Test
    public void testDispatchReplyNoImageConfigSendsTextOnly() throws Exception {
        java.nio.file.Path cfgPath = configPath;
        boolean existed = java.nio.file.Files.exists(cfgPath);
        byte[] backup = existed ? java.nio.file.Files.readAllBytes(cfgPath) : null;
        try {
            @SuppressWarnings("unchecked")
            java.util.Map<String, Object> cfg = existed
                    ? new ObjectMapper().readValue(backup, java.util.Map.class)
                    : new java.util.LinkedHashMap<>();
            cfg.remove("imageProvider");
            java.nio.file.Files.write(cfgPath, new ObjectMapper().writeValueAsBytes(cfg));

            when(personaService.getById(anyString())).thenReturn(testPersona);
            when(personaService.loadImageApiConfig()).thenReturn(null);

            String itemsJson = "[{\"type\":\"text\",\"content\":\"看看这个\"},"
                    + "{\"type\":\"image\",\"content_prompt\":\"一张图\",\"generation_mode\":\"sync\"}]";

            messageService.dispatchReply(
                    "d926f28e-ea21-4f6a-8d69-9ed3dba6052b",
                    "随便", itemsJson, null, null);

            verify(pythonClient, never()).generateImage(any(ImageGenerateRequest.class));
            verify(qqWs, never()).sendImage(anyString(), anyString(), anyString(), anyString());
            verify(qqWs).sendReply(eq("2387511709"), eq("1875552542"), eq("看看这个"));
        } finally {
            if (backup != null) java.nio.file.Files.write(cfgPath, backup);
        }
    }

    // ==================== sendNow ====================

    /**
     * sendNow: "now" 关键字 → 替换为实际时间戳
     */
    @Test
    public void testSendNowReplacesNowKeyword() {
        com.socialpersona.message.entity.ScheduledMessage msg =
                new com.socialpersona.message.entity.ScheduledMessage();
        msg.setId("msg-1");
        msg.setPersonaId("d926f28e-ea21-4f6a-8d69-9ed3dba6052b");
        msg.setScheduledTime("now");
        msg.setItemsJson("[{\"type\":\"text\",\"content\":\"hello\"}]");

        messageService.sendNow("persona1", msg);

        assertNotNull(msg.getScheduledTime());
        assertNotEquals("now", msg.getScheduledTime(), "now 应被替换为实际时间戳");
        assertEquals(1, msg.getIsSent(), "sendNow 消息应标记已发送");
        verify(scanScheduler).onMessageListChanged();
    }

    // ==================== scheduleIntervalMessages ====================

    /**
     * scheduleIntervalMessages: 批量插入消息并重置调度器
     */
    @Test
    public void testScheduleIntervalMessages() {
        List<com.socialpersona.message.entity.ScheduledMessage> msgs = new ArrayList<>();
        com.socialpersona.message.entity.ScheduledMessage m1 =
                new com.socialpersona.message.entity.ScheduledMessage();
        m1.setId("m1");
        m1.setScheduledTime("08:00:00");
        m1.setIsSent(0);
        msgs.add(m1);

        messageService.scheduleIntervalMessages("persona1", msgs);

        verify(messageMapper).insert(m1);
        verify(scanScheduler).onMessageListChanged();
    }

    // ==================== 辅助方法 ====================

    /** 通过反射调用私有方法 extractReplyText */
    private String invokeExtractReplyText(Object replyObj) {
        try {
            java.lang.reflect.Method method = MessageService.class
                    .getDeclaredMethod("extractReplyText", Object.class);
            method.setAccessible(true);
            return (String) method.invoke(messageService, replyObj);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
