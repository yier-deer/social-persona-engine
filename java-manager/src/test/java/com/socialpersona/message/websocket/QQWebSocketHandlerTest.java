package com.socialpersona.message.websocket;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.socialpersona.persona.entity.Persona;
import com.socialpersona.persona.service.PersonaService;
import jakarta.websocket.RemoteEndpoint;
import jakarta.websocket.Session;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.context.ApplicationContext;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * QQWebSocketHandler 单元测试 —— 验证 OneBot V11 消息路由逻辑
 *
 * ★ 核心验收场景：
 *   1. 按 self_id（AI的QQ号）匹配 Persona
 *   2. 按 user_id（发送者QQ）校验是否为 owner_qq
 *   3. 非主人消息忽略
 *   4. 群消息忽略
 *   5. 心跳消息跳过
 */
@ExtendWith(MockitoExtension.class)
public class QQWebSocketHandlerTest {

    @Mock private PersonaService personaService;
    @Mock private ApplicationContext applicationContext;
    @Mock private QQAsyncMessageHandler asyncHandler;
    @Mock private Session session;
    @Mock private RemoteEndpoint.Basic basicRemote;

    private QQWebSocketHandler handler;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private Persona testPersona;

    @BeforeEach
    public void setUp() throws Exception {
        ((java.util.Map<?, ?>) ReflectionTestUtils.getField(QQWebSocketHandler.class, "napCatSessions")).clear();
        ReflectionTestUtils.setField(QQWebSocketHandler.class, "applicationContext", null);
        handler = new QQWebSocketHandler(personaService);

        testPersona = new Persona();
        testPersona.setId("d926f28e-ea21-4f6a-8d69-9ed3dba6052b");
        testPersona.setName("小奈");
        testPersona.setAiQq("2387511709");
        testPersona.setOwnerQq("1875552542");
        testPersona.setStatus("active");
    }

    @Test
    public void testOnOpen() {
        when(session.getId()).thenReturn("napcat-session-1");
        handler.onOpen(session);
    }

    @Test
    public void testOnClose() {
        handler.onClose(session);
    }

    // ==================== 心跳消息 ====================

    @Test
    public void testHeartbeatIgnored() throws Exception {
        String heartbeat = """
            {"post_type":"meta_event","meta_event_type":"heartbeat","status":{"online":true}}
            """;
        handler.onMessage(heartbeat, session);
        verify(personaService, never()).findByAiQQ(anyString());
    }

    @Test
    public void testHeartbeatTriggersLazyEventCheck() throws Exception {
        when(session.isOpen()).thenReturn(true);
        String heartbeat = """
            {"post_type":"meta_event","meta_event_type":"heartbeat","self_id":"2387511709","status":{"online":true}}
            """;
        handler.onMessage(heartbeat, session);

        assertEquals(java.util.List.of("2387511709"), handler.getConnectedBotIds());
    }

    // ==================== ★ 核心路由：主人私聊 ====================

    @Test
    public void testOwnerPrivateMessageRoutesToMessageService() throws Exception {
        String msg = """
            {"post_type":"message","message_type":"private",
             "self_id":2387511709,"user_id":1875552542,"raw_message":"你好"}
            """;

        when(personaService.findByAiQQ("2387511709")).thenReturn(testPersona);
        when(applicationContext.getBean(QQAsyncMessageHandler.class)).thenReturn(asyncHandler);
        handler.setApplicationContext(applicationContext);

        handler.onMessage(msg, session);

        verify(personaService).findByAiQQ("2387511709");
        verify(asyncHandler).handleMessage("d926f28e-ea21-4f6a-8d69-9ed3dba6052b", "你好");
    }

    // ==================== 非主人消息忽略 ====================

    @Test
    public void testNonOwnerMessageIgnored() throws Exception {
        String msg = """
            {"post_type":"message","message_type":"private",
             "self_id":2387511709,"user_id":999999999,"raw_message":"hello"}
            """;

        when(personaService.findByAiQQ("2387511709")).thenReturn(testPersona);

        handler.onMessage(msg, session);

        verify(applicationContext, never()).getBean(QQAsyncMessageHandler.class);
    }

    // ==================== 未匹配到 AI ====================

    @Test
    public void testUnknownSelfIdIgnored() throws Exception {
        String msg = """
            {"post_type":"message","message_type":"private",
             "self_id":0,"user_id":1875552542,"raw_message":"hello"}
            """;

        when(personaService.findByAiQQ("0")).thenReturn(null);

        handler.onMessage(msg, session);

        verify(applicationContext, never()).getBean(QQAsyncMessageHandler.class);
    }

    // ==================== AI 未配 owner_qq ====================

    @Test
    public void testNoOwnerQqIgnored() throws Exception {
        testPersona.setOwnerQq(null);
        String msg = """
            {"post_type":"message","message_type":"private",
             "self_id":2387511709,"user_id":1875552542,"raw_message":"hello"}
            """;

        when(personaService.findByAiQQ("2387511709")).thenReturn(testPersona);

        handler.onMessage(msg, session);

        verify(applicationContext, never()).getBean(QQAsyncMessageHandler.class);
    }

    // ==================== 群消息忽略 ====================

    @Test
    public void testGroupMessageIgnored() throws Exception {
        String msg = """
            {"post_type":"message","message_type":"group",
             "self_id":2387511709,"user_id":1875552542,"group_id":12345,"raw_message":"大家好"}
            """;

        handler.onMessage(msg, session);

        verify(personaService, never()).findByAiQQ(anyString());
    }

    // ==================== 空消息 ====================

    @Test
    public void testEmptyRawMessageIgnored() throws Exception {
        String msg = """
            {"post_type":"message","message_type":"private",
             "self_id":2387511709,"user_id":1875552542,"raw_message":""}
            """;

        handler.onMessage(msg, session);

        verify(personaService, never()).findByAiQQ(anyString());
    }

    // ==================== sendReply ====================

    @Test
    public void testSendReplyWithoutConnection() {
        assertThrows(IllegalStateException.class, () -> handler.sendReply("2387511709", "1875552542", "test"));
    }

    @Test
    public void testSendImageWithCaption() throws Exception {
        when(session.isOpen()).thenReturn(true);
        when(session.getBasicRemote()).thenReturn(basicRemote);

        String heartbeat = """
            {"post_type":"meta_event","meta_event_type":"heartbeat","status":{"online":true,"self_id":"2387511709"}}
            """;
        handler.onMessage(heartbeat, session);

        String imagePath = "C:/images/test-cat.png";
        handler.sendImage("1875552542", imagePath, "看这个");

        verify(basicRemote).sendText(argThat(json ->
                json.contains("CQ:image") && json.contains("test-cat.png")));
    }

    @Test
    public void testSendImageSendsText() throws Exception {
        when(session.isOpen()).thenReturn(true);
        when(session.getBasicRemote()).thenReturn(basicRemote);

        String heartbeat = """
            {"post_type":"meta_event","meta_event_type":"heartbeat","status":{"online":true,"self_id":"2387511709"}}
            """;
        handler.onMessage(heartbeat, session);

        String imagePath = "C:/images/test-cat.png";
        handler.sendImage("1875552542", imagePath, null);

        verify(basicRemote, times(1)).sendText(anyString());
    }

    @Test
    public void testSendReplyWithConnection() throws Exception {
        when(session.isOpen()).thenReturn(true);
        when(session.getBasicRemote()).thenReturn(basicRemote);

        String heartbeat = """
            {"post_type":"meta_event","meta_event_type":"heartbeat","status":{"online":true,"self_id":"2387511709"}}
            """;
        handler.onMessage(heartbeat, session);

        handler.sendReply("1875552542", "你好呀");

        verify(basicRemote).sendText(anyString());
    }

    // ==================== 其他 post_type 忽略 ====================

    @Test
    public void testUnknownPostTypeIgnored() throws Exception {
        String msg = """
            {"post_type":"request","request_type":"friend","user_id":1875552542}
            """;

        handler.onMessage(msg, session);

        verify(personaService, never()).findByAiQQ(anyString());
    }

    // ==================== 私聊但 user_id 字符串形式 ====================

    @Test
    public void testUserIdAsString() throws Exception {
        String msg = """
            {"post_type":"message","message_type":"private",
             "self_id":"2387511709","user_id":"1875552542","raw_message":"测试"}
            """;

        when(personaService.findByAiQQ("2387511709")).thenReturn(testPersona);
        when(applicationContext.getBean(QQAsyncMessageHandler.class)).thenReturn(asyncHandler);
        handler.setApplicationContext(applicationContext);

        handler.onMessage(msg, session);

        verify(asyncHandler).handleMessage("d926f28e-ea21-4f6a-8d69-9ed3dba6052b", "测试");
    }
}
