package com.socialpersona.message.scheduler;

import com.socialpersona.message.entity.ScheduledMessage;
import com.socialpersona.message.repository.MessageMapper;
import com.socialpersona.message.websocket.FrontendWebSocketHandler;
import com.socialpersona.message.websocket.QQWebSocketHandler;
import com.socialpersona.persona.entity.Persona;
import com.socialpersona.persona.service.PersonaService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * MessageScanScheduler 单元测试 —— 验证三级扫描升降级 + QQ 推送
 *
 * 覆盖场景：
 *   1. IDLE → MINUTE_WATCH（下一小时有消息）
 *   2. MINUTE_WATCH → SECOND_WATCH（当前分钟有消息）
 *   3. SECOND_WATCH 推送消息（含 QQ 推送）
 *   4. SECOND_WATCH → MINUTE_WATCH（当前分钟无消息）
 *   5. MINUTE_WATCH → IDLE（下一小时无消息）
 *   6. onMessageListChanged → 重置 IDLE
 */
@ExtendWith(MockitoExtension.class)
public class MessageScanSchedulerTest {

    @Mock private MessageMapper messageMapper;
    @Mock private FrontendWebSocketHandler wsHandler;
    @Mock private QQWebSocketHandler qqWs;
    @Mock private PersonaService personaService;
    private Persona currentPersona;

    @InjectMocks
    private MessageScanScheduler scheduler;

    @BeforeEach
    public void setUp() {
        currentPersona = new Persona();
        currentPersona.setId("persona1");
        currentPersona.setStatus("active");
        currentPersona.setAiQq("bot-1");
        currentPersona.setOwnerQq("1875552542");
        lenient().when(personaService.getById("persona1")).thenReturn(currentPersona);
        scheduler.registerPersona("persona1");
        scheduler.setOwnerQq("persona1", "1875552542");
    }

    // ==================== IDLE → MINUTE_WATCH ====================

    /**
     * IDLE 状态，下一小时有消息 → 升到 MINUTE_WATCH
     */
    @Test
    public void testIdleToMinuteWatch() {
        when(messageMapper.countInNextHour(eq("persona1"), anyString(), anyString()))
                .thenReturn(5);

        scheduler.hourlyScan();

        assertEquals(MessageScanScheduler.ScanLevel.MINUTE_WATCH, scheduler.getCurrentLevel("persona1"),
                "IDLE 下发现 5 条消息应升到 MINUTE_WATCH");
    }

    /**
     * IDLE 状态，下一小时无消息 → 保持 IDLE
     */
    @Test
    public void testIdleStaysIdleWhenNoMessages() {
        when(messageMapper.countInNextHour(eq("persona1"), anyString(), anyString()))
                .thenReturn(0);

        scheduler.hourlyScan();

        assertEquals(MessageScanScheduler.ScanLevel.IDLE, scheduler.getCurrentLevel("persona1"),
                "IDLE 下无消息应保持 IDLE");
    }

    // ==================== MINUTE_WATCH → SECOND_WATCH ====================

    /**
     * MINUTE_WATCH 状态，当前分钟有消息 → 升到 SECOND_WATCH
     */
    @Test
    public void testMinuteWatchToSecondWatch() {
        when(messageMapper.countInNextHour(eq("persona1"), anyString(), anyString()))
                .thenReturn(3);
        scheduler.hourlyScan();
        assertEquals(MessageScanScheduler.ScanLevel.MINUTE_WATCH, scheduler.getCurrentLevel("persona1"));

        when(messageMapper.countInCurrentMinute(eq("persona1"), anyString(), anyString()))
                .thenReturn(2);
        scheduler.minuteScan();

        assertEquals(MessageScanScheduler.ScanLevel.SECOND_WATCH, scheduler.getCurrentLevel("persona1"));
    }

    // ==================== MINUTE_WATCH → IDLE ====================

    /**
     * MINUTE_WATCH 状态，当前分钟无消息且下一小时也无消息 → 降回 IDLE
     */
    @Test
    public void testMinuteWatchToIdle() {
        when(messageMapper.countInNextHour(eq("persona1"), anyString(), anyString()))
                .thenReturn(3);
        scheduler.hourlyScan();
        assertEquals(MessageScanScheduler.ScanLevel.MINUTE_WATCH, scheduler.getCurrentLevel("persona1"));

        when(messageMapper.countInCurrentMinute(eq("persona1"), anyString(), anyString()))
                .thenReturn(0);
        when(messageMapper.countInNextHour(eq("persona1"), anyString(), anyString()))
                .thenReturn(0);
        scheduler.minuteScan();

        assertEquals(MessageScanScheduler.ScanLevel.IDLE, scheduler.getCurrentLevel("persona1"));
    }

    // ==================== SECOND_WATCH 推送 ====================

    /**
     * SECOND_WATCH: 到期消息 → 推前端 + 推 QQ
     */
    @Test
    public void testSecondWatchPushesToFrontendAndQQ() {
        when(messageMapper.countInNextHour(eq("persona1"), anyString(), anyString())).thenReturn(3);
        scheduler.hourlyScan();
        when(messageMapper.countInCurrentMinute(eq("persona1"), anyString(), anyString())).thenReturn(2);
        scheduler.minuteScan();
        assertEquals(MessageScanScheduler.ScanLevel.SECOND_WATCH, scheduler.getCurrentLevel("persona1"));

        ScheduledMessage msg = new ScheduledMessage();
        msg.setId("msg-1");
        msg.setPersonaId("persona1");
        msg.setItemsJson("[{\"type\":\"text\",\"content\":\"hello\"}]");
        msg.setMood("happy");
        msg.setScheduledTime("08:00:00");

        when(messageMapper.findDueThisSecond(eq("persona1"), anyString(), anyString()))
                .thenReturn(Arrays.asList(msg));

        scheduler.secondScan();

        verify(wsHandler).sendToPersona(eq("persona1"), anyString());
        verify(qqWs).sendReply(eq("bot-1"), eq("1875552542"), eq("hello"));
        verify(messageMapper).updateById(msg);
    }

    /**
     * SECOND_WATCH: 无到期消息且当前分钟也无消息 → 降回 MINUTE_WATCH
     */
    @Test
    public void testSecondWatchToMinuteWatch() {
        when(messageMapper.countInNextHour(eq("persona1"), anyString(), anyString())).thenReturn(3);
        scheduler.hourlyScan();
        when(messageMapper.countInCurrentMinute(eq("persona1"), anyString(), anyString())).thenReturn(2);
        scheduler.minuteScan();
        assertEquals(MessageScanScheduler.ScanLevel.SECOND_WATCH, scheduler.getCurrentLevel("persona1"));

        when(messageMapper.findDueThisSecond(eq("persona1"), anyString(), anyString()))
                .thenReturn(Collections.emptyList());
        when(messageMapper.countInCurrentMinute(eq("persona1"), anyString(), anyString()))
                .thenReturn(0);
        scheduler.secondScan();

        assertEquals(MessageScanScheduler.ScanLevel.MINUTE_WATCH, scheduler.getCurrentLevel("persona1"));
    }

    // ==================== onMessageListChanged ====================

    /**
     * onMessageListChanged → 重置为 IDLE
     */
    @Test
    public void testOnMessageListChangedResetsToIdle() {
        when(messageMapper.countInNextHour(eq("persona1"), anyString(), anyString())).thenReturn(3);
        scheduler.hourlyScan();
        assertEquals(MessageScanScheduler.ScanLevel.MINUTE_WATCH, scheduler.getCurrentLevel("persona1"));

        scheduler.onMessageListChanged();

        assertEquals(MessageScanScheduler.ScanLevel.IDLE, scheduler.getCurrentLevel("persona1"),
                "onMessageListChanged 后应重置为 IDLE");
    }

    // ==================== registerPersona / setOwnerQq ====================

    @Test
    public void testRegisterPersona() {
        scheduler.registerPersona("persona2");
        assertEquals(MessageScanScheduler.ScanLevel.IDLE, scheduler.getCurrentLevel("persona2"));
    }

    @Test
    public void testUnregisterPersona() {
        scheduler.registerPersona("persona2");
        scheduler.unregisterPersona("persona2");
        assertEquals(MessageScanScheduler.ScanLevel.IDLE,
                scheduler.getCurrentLevel("persona2"),
                "unregister 后应返回 IDLE");
    }

    @Test
    public void testScanWithoutPersona() {
        scheduler.secondScan();
    }

    // ==================== SECOND_WATCH 无 QQ owner ====================

    @Test
    public void testSecondWatchWithoutOwnerQq() {
        scheduler.setOwnerQq("persona1", null);
        currentPersona.setOwnerQq(null);

        when(messageMapper.countInNextHour(eq("persona1"), anyString(), anyString())).thenReturn(3);
        scheduler.hourlyScan();
        when(messageMapper.countInCurrentMinute(eq("persona1"), anyString(), anyString())).thenReturn(2);
        scheduler.minuteScan();

        ScheduledMessage msg = new ScheduledMessage();
        msg.setId("msg-2");
        msg.setPersonaId("persona1");
        msg.setItemsJson("[{\"type\":\"text\",\"content\":\"no qq\"}]");
        msg.setScheduledTime("08:01:00");

        when(messageMapper.findDueThisSecond(eq("persona1"), anyString(), anyString()))
                .thenReturn(Arrays.asList(msg));

        scheduler.secondScan();

        verify(qqWs, never()).sendReply(anyString(), anyString(), anyString());
    }

    // ==================== extractTextFromItems ====================

    @Test
    public void testSecondScanExtractsOnlyTextItems() {
        scheduler.setOwnerQq("persona1", "1875552542");

        when(messageMapper.countInNextHour(eq("persona1"), anyString(), anyString())).thenReturn(3);
        scheduler.hourlyScan();
        when(messageMapper.countInCurrentMinute(eq("persona1"), anyString(), anyString())).thenReturn(2);
        scheduler.minuteScan();

        ScheduledMessage msg = new ScheduledMessage();
        msg.setId("msg-3");
        msg.setPersonaId("persona1");
        msg.setItemsJson("[{\"type\":\"text\",\"content\":\"only text\"}]");
        msg.setScheduledTime("08:02:00");

        when(messageMapper.findDueThisSecond(eq("persona1"), anyString(), anyString()))
                .thenReturn(Arrays.asList(msg));

        scheduler.secondScan();

        verify(qqWs).sendReply("bot-1", "1875552542", "only text");
        verify(wsHandler).sendToPersona(eq("persona1"), anyString());
    }

    // ==================== 多 AI 并发 ====================

    @Test
    public void testTwoPersonasScannedIndependently() {
        scheduler.registerPersona("persona2");

        // persona1 → SECOND_WATCH, persona2 → IDLE
        when(messageMapper.countInNextHour(eq("persona1"), anyString(), anyString())).thenReturn(3);
        when(messageMapper.countInNextHour(eq("persona2"), anyString(), anyString())).thenReturn(0);
        scheduler.hourlyScan();

        assertEquals(MessageScanScheduler.ScanLevel.MINUTE_WATCH, scheduler.getCurrentLevel("persona1"));
        assertEquals(MessageScanScheduler.ScanLevel.IDLE, scheduler.getCurrentLevel("persona2"));

        // persona1 → SECOND_WATCH
        when(messageMapper.countInCurrentMinute(eq("persona1"), anyString(), anyString())).thenReturn(2);
        scheduler.minuteScan();
        assertEquals(MessageScanScheduler.ScanLevel.SECOND_WATCH, scheduler.getCurrentLevel("persona1"));

        // persona1 推送
        ScheduledMessage msg1 = new ScheduledMessage();
        msg1.setId("msg-p1");
        msg1.setPersonaId("persona1");
        msg1.setItemsJson("[{\"type\":\"text\",\"content\":\"hello from 1\"}]");
        msg1.setScheduledTime("08:00:00");

        when(messageMapper.findDueThisSecond(eq("persona1"), anyString(), anyString()))
                 .thenReturn(Arrays.asList(msg1));

         scheduler.secondScan();

         verify(wsHandler).sendToPersona(eq("persona1"), anyString());
     }
}