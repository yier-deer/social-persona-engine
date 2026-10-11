package com.socialpersona.message.service;

import com.socialpersona.error.SystemErrorHandler;
import com.socialpersona.event.entity.EventLog;
import com.socialpersona.event.repository.EventLogMapper;
import com.socialpersona.event.repository.EventMapper;
import com.socialpersona.event.service.EventService;
import com.socialpersona.gateway.PythonClient;
import com.socialpersona.gateway.dto.MessageResponse;
import com.socialpersona.message.burst.BurstGroupManager;
import com.socialpersona.message.entity.ScheduledMessage;
import com.socialpersona.message.repository.MessageMapper;
import com.socialpersona.message.scheduler.MessageScanScheduler;
import com.socialpersona.message.websocket.FrontendWebSocketHandler;
import com.socialpersona.message.websocket.QQWebSocketHandler;
import com.socialpersona.middleware.InterruptListener;
import com.socialpersona.persona.entity.Persona;
import com.socialpersona.persona.service.PersonaService;
import com.socialpersona.relationship.engine.RelationshipEngine;
import com.socialpersona.relationship.entity.RelationshipState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Map;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;

class MessageServiceStateRegressionTest {
    private MessageService service;
    private PersonaService personas;
    private PythonClient inference;
    private QQWebSocketHandler qq;
    private MessageMapper messages;
    private EventLogMapper eventLogs;

    @BeforeEach
    void setUp() {
        service = new MessageService();
        personas = mock(PersonaService.class);
        inference = mock(PythonClient.class);
        qq = mock(QQWebSocketHandler.class);
        messages = mock(MessageMapper.class);
        ReflectionTestUtils.setField(service, "personaService", personas);
        ReflectionTestUtils.setField(service, "pythonClient", inference);
        ReflectionTestUtils.setField(service, "qqWs", qq);
        ReflectionTestUtils.setField(service, "messageMapper", messages);
        ReflectionTestUtils.setField(service, "scanScheduler", mock(MessageScanScheduler.class));
        ReflectionTestUtils.setField(service, "burstGroupManager", mock(BurstGroupManager.class));
        ReflectionTestUtils.setField(service, "frontendWs", mock(FrontendWebSocketHandler.class));
        eventLogs = mock(EventLogMapper.class);
        ReflectionTestUtils.setField(service, "eventLogMapper", eventLogs);
        ReflectionTestUtils.setField(service, "eventMapper", mock(EventMapper.class));
        ReflectionTestUtils.setField(service, "eventService", mock(EventService.class));
        ReflectionTestUtils.setField(service, "interruptListener", mock(InterruptListener.class));
        ReflectionTestUtils.setField(service, "systemErrorHandler", mock(SystemErrorHandler.class));
        RelationshipEngine relationship = mock(RelationshipEngine.class);
        RelationshipState state = new RelationshipState();
        state.setTrust(50.0);
        state.setCloseness(20.0);
        state.setTension(0.0);
        state.setEmotionalEnergy(50.0);
        state.setTensionPressure(0.0);
        state.setContactUrge(0.0);
        when(relationship.getState("persona")).thenReturn(state);
        ReflectionTestUtils.setField(service, "relationshipEngine", relationship);
    }

    private Persona persona(String status) {
        Persona persona = new Persona();
        persona.setId("persona");
        persona.setName("demo");
        persona.setAiQq("10001");
        persona.setOwnerQq("888");
        persona.setStatus(status);
        return persona;
    }

    @ParameterizedTest
    @ValueSource(strings = {"paused", "sleeping", "archived"})
    void inactivePersonaNeverCallsModel(String status) {
        when(personas.getById("persona")).thenReturn(persona(status));
        when(inference.handleMessage(any())).thenReturn(new MessageResponse());

        service.handleUserMessage("persona", "hello");

        verify(inference, never()).handleMessage(any());
        verifyNoInteractions(qq);
    }

    @Test
    void pausingWhileModelRunsPreventsOutgoingReply() {
        when(personas.getById("persona")).thenReturn(persona("active"), persona("paused"));
        MessageResponse reply = new MessageResponse();
        reply.setShouldReply(true);
        reply.setReply(Map.of("raw_text", "hello"));
        when(inference.handleMessage(any())).thenReturn(reply);

        service.handleUserMessage("persona", "hello");

        verify(inference).handleMessage(any());
        verifyNoInteractions(qq);
        verify(messages, never()).insert(any(ScheduledMessage.class));
    }

    @Test
    void disconnectedBotDoesNotMarkMessageSent() {
        when(personas.getById("persona")).thenReturn(persona("active"));
        ((Map<?, ?>) ReflectionTestUtils.getField(QQWebSocketHandler.class, "napCatSessions")).clear();
        ReflectionTestUtils.setField(service, "qqWs", new QQWebSocketHandler(personas));

        assertThrows(IllegalStateException.class,
                () -> service.dispatchReply("persona", "hello", "[]", null, null));

        ArgumentCaptor<ScheduledMessage> saved = ArgumentCaptor.forClass(ScheduledMessage.class);
        verify(messages).insert(saved.capture());
        assertEquals(0, saved.getValue().getIsSent());
    }

    @Test
    void modelFailureIsRecordedAsSystemError() {
        when(personas.getById("persona")).thenReturn(persona("active"));
        when(inference.handleMessage(any())).thenThrow(new IllegalStateException("upstream failure"));

        service.handleUserMessage("persona", "hello");

        ArgumentCaptor<EventLog> records = ArgumentCaptor.forClass(EventLog.class);
        verify(eventLogs, atLeastOnce()).insert(records.capture());
        assertTrue(records.getAllValues().stream().anyMatch(record -> "system_error".equals(record.getLogType())));
    }
}
