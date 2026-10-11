package com.socialpersona.event.scheduler;

import com.socialpersona.event.entity.DailyEvent;
import com.socialpersona.event.service.EventService;
import com.socialpersona.gateway.PythonClient;
import com.socialpersona.gateway.dto.EventGenerateRequest;
import com.socialpersona.gateway.dto.EventGenerateResponse;
import com.socialpersona.gateway.dto.EventTriggerRequest;
import com.socialpersona.gateway.dto.EventTriggerResponse;
import com.socialpersona.persona.entity.Persona;
import com.socialpersona.persona.service.PersonaService;
import com.socialpersona.message.scheduler.MessageScanScheduler;
import com.socialpersona.relationship.engine.RelationshipEngine;
import com.socialpersona.relationship.entity.RelationshipState;
import com.socialpersona.relationship.state.AIStateMachine;
import com.socialpersona.relationship.state.AIStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * EventTriggerScheduler 单元测试 —— 验证每分钟事件扫描逻辑
 *
 * 覆盖 4 个核心场景：
 *   1. routine 事件 + AI 非 SLEEPING → 调 PythonClient
 *   2. sleep 事件 → 调 AIStateMachine.onSleepEvent
 *   3. AI 处于 SLEEPING → 跳过事件
 *   4. 无到期事件 → 不调 Python
 */
@ExtendWith(MockitoExtension.class)
public class EventTriggerSchedulerTest {

    @Mock private EventService eventService;
    @Mock private AIStateMachine stateMachine;
    @Mock private PythonClient pythonClient;
    @Mock private PersonaService personaService;
    @Mock private MessageScanScheduler scanScheduler;
    @Mock private RelationshipEngine relationshipEngine;

    @InjectMocks
    private EventTriggerScheduler scheduler;

    private Persona testPersona;

    @BeforeEach
    public void setUp() {
        testPersona = new Persona();
        testPersona.setId("persona1");
        testPersona.setName("小奈");
        testPersona.setOwnerQq("1875552542");
        testPersona.setStatus("active");
        testPersona.setCharacterCurrentContext("大学生，正在准备期末考试");

        RelationshipState defaultState = new RelationshipState();
        defaultState.setTrust(50.0);
        defaultState.setCloseness(20.0);
        defaultState.setTension(0.0);
        defaultState.setEmotionalEnergy(30.0);
        defaultState.setTensionPressure(0.0);
        defaultState.setContactUrge(0.0);
        lenient().when(relationshipEngine.getState(anyString())).thenReturn(defaultState);
    }

    /**
     * 场景①：routine 事件 + AI 活跃 → 调 PythonClient
     */
    @Test
    public void testRoutineEventTriggersPythonCall() {
        when(personaService.listEnabled()).thenReturn(Arrays.asList(testPersona));

        DailyEvent routineEvent = new DailyEvent();
        routineEvent.setEventType("routine");
        routineEvent.setEventTime("10:00:00");
        routineEvent.setDescription("发呆时刻");

        when(eventService.findDue("persona1")).thenReturn(Arrays.asList(routineEvent));
        when(stateMachine.getState("persona1")).thenReturn(AIStatus.ACTIVE);
        when(eventService.findNextActive(anyString(), anyString())).thenReturn(null);

        EventTriggerResponse mockResp = new EventTriggerResponse();
        mockResp.setShouldContactUser(false);
        when(pythonClient.triggerEvent(any(EventTriggerRequest.class))).thenReturn(mockResp);

        scheduler.scanEvents();

        verify(pythonClient).triggerEvent(any(EventTriggerRequest.class));
        verify(stateMachine, never()).onSleepEvent(anyString());
    }

    /**
     * 场景②：sleep 事件 → 调 AIStateMachine.onSleepEvent，不调 Python
     */
    @Test
    public void testSleepEventTriggersStateMachine() {
        when(personaService.listEnabled()).thenReturn(Arrays.asList(testPersona));

        DailyEvent sleepEvent = new DailyEvent();
        sleepEvent.setEventType("sleep");
        sleepEvent.setEventTime("23:00:00");
        sleepEvent.setDescription("睡觉时间");

        when(eventService.findDue("persona1")).thenReturn(Arrays.asList(sleepEvent));

        scheduler.scanEvents();

        verify(stateMachine).onSleepEvent("persona1");
        verify(pythonClient, never()).triggerEvent(any());
    }

    /**
     * 场景③：AI 处于 SLEEPING → 跳过事件
     */
    @Test
    public void testSleepingPersonaSkipsMoment() {
        when(personaService.listEnabled()).thenReturn(Arrays.asList(testPersona));

        DailyEvent routineEvent = new DailyEvent();
        routineEvent.setEventType("moment");
        routineEvent.setEventTime("10:00:00");
        routineEvent.setDescription("发呆时刻");

        when(eventService.findDue("persona1")).thenReturn(Arrays.asList(routineEvent));
        when(stateMachine.getState("persona1")).thenReturn(AIStatus.SLEEPING);

        scheduler.scanEvents();

        verify(pythonClient, never()).triggerEvent(any());
        verify(stateMachine, never()).onSleepEvent(anyString());
    }

    /**
     * 场景④：无到期事件 → 不调 Python
     */
    @Test
    public void testNoEventsDoesNothing() {
        when(personaService.listEnabled()).thenReturn(Arrays.asList(testPersona));
        when(eventService.findDue("persona1")).thenReturn(Collections.emptyList());

        scheduler.scanEvents();

        verify(pythonClient, never()).triggerEvent(any());
        verify(stateMachine, never()).onSleepEvent(anyString());
    }

    /**
     * 无活跃 Persona → 跳过扫描
     */
    @Test
    public void testNoActivePersonasSkipsScan() {
        when(personaService.listEnabled()).thenReturn(Collections.emptyList());

        scheduler.scanEvents();

        verify(eventService, never()).findDue(anyString());
    }

    /**
     * 场景⑤：routine 事件 + should_contact_user=true → 设置 ownerQq
     */
    @Test
    public void testShouldContactUserSetsOwnerQq() {
        when(personaService.listEnabled()).thenReturn(Arrays.asList(testPersona));

        DailyEvent routineEvent = new DailyEvent();
        routineEvent.setEventType("moment");
        routineEvent.setEventTime("14:00:00");
        routineEvent.setDescription("看到一张有趣的图");

        when(eventService.findDue("persona1")).thenReturn(Arrays.asList(routineEvent));
        when(stateMachine.getState("persona1")).thenReturn(AIStatus.ACTIVE);

        EventTriggerResponse mockResp = new EventTriggerResponse();
        mockResp.setShouldContactUser(true);
        when(pythonClient.triggerEvent(any(EventTriggerRequest.class))).thenReturn(mockResp);

        scheduler.scanEvents();

        verify(scanScheduler).setOwnerQq("persona1", "1875552542");
    }

    // ==================== 今日事件懒加载 ====================

    @Test
    public void testLazyGenerateSkipsWhenEventsExist() {
        when(eventService.hasTodayEvents("persona1")).thenReturn(true);

        scheduler.lazyGenerateTodayEventsIfNeeded("persona1");

        verify(personaService, never()).getById(anyString());
        verify(pythonClient, never()).generateEvents(any());
    }

    @Test
    public void testLazyGenerateCallsPythonWhenNoEvents() {
        when(eventService.hasTodayEvents("persona1")).thenReturn(false);
        when(personaService.getById("persona1")).thenReturn(testPersona);
        when(personaService.loadGlobalApiConfig())
                .thenReturn(Map.of("provider", "deepseek", "api_key", "sk-test", "base_url", "https://api.deepseek.com/v1", "model", "deepseek-chat"));

        EventGenerateResponse mockResp = new EventGenerateResponse();
        List<Object> events = new ArrayList<>();
        Map<String, Object> evt1 = new LinkedHashMap<>();
        evt1.put("event_time", "10:00:00");
        evt1.put("event_type", "routine");
        evt1.put("description", "早饭时间");
        events.add(evt1);
        Map<String, Object> evt2 = new LinkedHashMap<>();
        evt2.put("event_time", "23:00:00");
        evt2.put("event_type", "sleep");
        evt2.put("description", "睡觉");
        events.add(evt2);
        mockResp.setEvents(events);
        when(pythonClient.generateEvents(any(EventGenerateRequest.class))).thenReturn(mockResp);

        scheduler.lazyGenerateTodayEventsIfNeeded("persona1");

        verify(pythonClient).generateEvents(any(EventGenerateRequest.class));
        verify(eventService).insertEvents(eq("persona1"), argThat(list ->
                list != null && list.size() == 2 && "10:00:00".equals(list.get(0).getEventTime()) && "sleep".equals(list.get(1).getEventType())));
    }

    @Test
    public void testLazyGenerateHandlesEmptyResponse() {
        when(eventService.hasTodayEvents("persona1")).thenReturn(false);
        when(personaService.getById("persona1")).thenReturn(testPersona);
        when(personaService.loadGlobalApiConfig())
                .thenReturn(Map.of("provider", "deepseek", "api_key", "sk-test", "base_url", "", "model", ""));

        EventGenerateResponse mockResp = new EventGenerateResponse();
        mockResp.setEvents(new ArrayList<>());
        when(pythonClient.generateEvents(any(EventGenerateRequest.class))).thenReturn(mockResp);

        scheduler.lazyGenerateTodayEventsIfNeeded("persona1");

        verify(eventService, never()).insertEvents(anyString(), anyList());
    }

    @Test
    public void testLazyGenerateHandlesPythonException() {
        when(eventService.hasTodayEvents("persona1")).thenReturn(false);
        when(personaService.getById("persona1")).thenReturn(testPersona);
        when(personaService.loadGlobalApiConfig())
                .thenReturn(Map.of("provider", "deepseek", "api_key", "sk-test", "base_url", "", "model", ""));
        when(pythonClient.generateEvents(any(EventGenerateRequest.class)))
                .thenThrow(new RuntimeException("Python 不可用"));

        assertDoesNotThrow(() -> scheduler.lazyGenerateTodayEventsIfNeeded("persona1"));

        verify(eventService, never()).insertEvents(anyString(), anyList());
    }
}