package com.socialpersona.event.scheduler;

import com.socialpersona.event.entity.DailyEvent;
import com.socialpersona.event.service.EventService;
import com.socialpersona.gateway.PythonClient;
import com.socialpersona.gateway.dto.EventTriggerRequest;
import com.socialpersona.gateway.dto.EventTriggerResponse;
import com.socialpersona.gateway.dto.EventGenerateResponse;
import com.socialpersona.message.service.MessageService;
import com.socialpersona.persona.entity.Persona;
import com.socialpersona.persona.service.PersonaService;
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
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class EventSchedulingRegressionTest {
    @Mock EventService eventService;
    @Mock AIStateMachine stateMachine;
    @Mock PythonClient pythonClient;
    @Mock PersonaService personaService;
    @Mock RelationshipEngine relationshipEngine;
    @Mock MessageService messageService;
    @InjectMocks EventTriggerScheduler scheduler;

    Persona persona;
    DailyEvent event;

    @BeforeEach
    void setUp() {
        persona = new Persona();
        persona.setId("p1");
        persona.setName("Demo");
        persona.setStatus("active");
        lenient().when(personaService.getById("p1")).thenReturn(persona);
        event = new DailyEvent();
        event.setId("event-1");
        event.setEventType("routine");
        event.setEventTime("08:00:00");
        event.setDescription("Breakfast");
        event.setIsActive(1);
        lenient().when(eventService.findDue("p1")).thenReturn(List.of(event));
        RelationshipState relationship = new RelationshipState();
        relationship.setTrust(50.0);
        relationship.setCloseness(20.0);
        relationship.setTension(0.0);
        relationship.setEmotionalEnergy(30.0);
        relationship.setTensionPressure(0.0);
        relationship.setContactUrge(0.0);
        lenient().when(relationshipEngine.getState("p1")).thenReturn(relationship);
        lenient().when(personaService.loadGlobalApiConfig()).thenReturn(Map.of(
                "provider", "custom", "api_key", "current-key", "base_url", "http://127.0.0.1:9999/v1", "model", "current-model"));
    }

    @Test
    void successfulEventExecutesOnlyOnceAcrossRepeatedScans() {
        List<DailyEvent> pending = new ArrayList<>(List.of(event));
        when(eventService.findDue("p1")).thenAnswer(inv -> new ArrayList<>(pending));
        when(eventService.hasTodayEvents("p1")).thenReturn(true);
        doAnswer(inv -> { pending.remove(event); return null; }).when(eventService).invalidateEvent("event-1");
        when(pythonClient.triggerEvent(any())).thenReturn(new EventTriggerResponse());

        scheduler.scanForPersona(persona);
        scheduler.scanForPersona(persona);

        verify(pythonClient, times(1)).triggerEvent(any());
        verify(eventService, times(1)).invalidateEvent("event-1");
        verify(pythonClient, never()).generateEvents(any());
    }

    @Test
    void sleepEventIsConsumedAfterStateTransition() {
        event.setEventType("sleep");
        scheduler.scanForPersona(persona);
        verify(stateMachine).onSleepEvent("p1");
        verify(eventService).invalidateEvent("event-1");
        verifyNoInteractions(pythonClient);
    }

    @Test
    void sleepingPersonaWakesOnNextRoutineAndRunsThatEvent() {
        persona.setStatus("sleeping");
        when(stateMachine.getState("p1")).thenReturn(AIStatus.SLEEPING);
        when(pythonClient.triggerEvent(any())).thenReturn(new EventTriggerResponse());
        scheduler.scanForPersona(persona);
        verify(stateMachine).onWakeUpEvent("p1");
        verify(pythonClient).triggerEvent(any());
        verify(eventService).invalidateEvent("event-1");
    }

    @Test
    void momentDuringSleepDoesNotWakeOrCallModel() {
        persona.setStatus("sleeping");
        event.setEventType("moment");
        when(stateMachine.getState("p1")).thenReturn(AIStatus.SLEEPING);
        scheduler.scanForPersona(persona);
        verify(stateMachine, never()).onWakeUpEvent(anyString());
        verifyNoInteractions(pythonClient);
        verify(eventService).invalidateEvent("event-1");
    }

    @Test
    void failedEventRemainsRetryableAndSuccessConsumesIt() {
        when(pythonClient.triggerEvent(any())).thenThrow(new IllegalStateException("upstream unavailable"))
                .thenReturn(new EventTriggerResponse());
        scheduler.scanForPersona(persona);
        verify(eventService, never()).invalidateEvent(any());
        scheduler.scanForPersona(persona);
        verify(pythonClient, times(2)).triggerEvent(any());
        verify(eventService).invalidateEvent("event-1");
    }

    @Test
    void nullResponseRemainsRetryable() {
        when(pythonClient.triggerEvent(any())).thenReturn(null);
        scheduler.scanForPersona(persona);
        verify(eventService, never()).invalidateEvent(any());
    }

    @Test
    void eventRequestUsesCurrentGlobalCredentials() {
        when(pythonClient.triggerEvent(any())).thenReturn(new EventTriggerResponse());
        scheduler.scanForPersona(persona);
        ArgumentCaptor<EventTriggerRequest> captor = ArgumentCaptor.forClass(EventTriggerRequest.class);
        verify(pythonClient).triggerEvent(captor.capture());
        Map<?, ?> config = (Map<?, ?>) captor.getValue().getApiConfig();
        assertEquals("current-model", config.get("model"));
        assertEquals("current-key", config.get("api_key"));
        verify(personaService, never()).decryptApiConfig(anyString());
    }

    @Test
    void pausedOrArchivedPersonaDoesNotScan() {
        for (String status : List.of("paused", "archived", "request_pending")) {
            persona.setStatus(status);
            scheduler.scanForPersona(persona);
        }
        verifyNoInteractions(pythonClient);
        verify(eventService, never()).findDue(any());
    }

    @Test
    void concurrentScansCannotExecuteTheSameEventTwice() throws Exception {
        List<DailyEvent> pending = new ArrayList<>(List.of(event));
        when(eventService.findDue("p1")).thenAnswer(inv -> new ArrayList<>(pending));
        doAnswer(inv -> { pending.clear(); return null; }).when(eventService).invalidateEvent("event-1");
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(pythonClient.triggerEvent(any())).thenAnswer(inv -> {
            started.countDown();
            assertTrue(release.await(5, TimeUnit.SECONDS));
            return new EventTriggerResponse();
        });
        ExecutorService runner = Executors.newSingleThreadExecutor();
        try {
            Future<?> first = runner.submit(() -> scheduler.scanForPersona(persona));
            assertTrue(started.await(5, TimeUnit.SECONDS));
            scheduler.scanForPersona(persona);
            release.countDown();
            first.get(5, TimeUnit.SECONDS);
            verify(pythonClient, times(1)).triggerEvent(any());
        } finally {
            release.countDown();
            runner.shutdownNow();
        }
    }

    @Test
    void concurrentLazyGenerationOnlyInsertsOneDailyPlan() throws Exception {
        AtomicBoolean inserted = new AtomicBoolean();
        when(eventService.hasTodayEvents("p1")).thenAnswer(inv -> inserted.get());
        doAnswer(inv -> { inserted.set(true); return null; })
                .when(eventService).insertEvents(eq("p1"), anyList());
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        when(pythonClient.generateEvents(any())).thenAnswer(inv -> {
            if (calls.incrementAndGet() == 1) {
                started.countDown();
                assertTrue(release.await(5, TimeUnit.SECONDS));
            }
            return generatedPlan();
        });
        ExecutorService runner = Executors.newSingleThreadExecutor();
        try {
            Future<?> first = runner.submit(() -> scheduler.lazyGenerateTodayEventsIfNeeded("p1"));
            assertTrue(started.await(5, TimeUnit.SECONDS));
            scheduler.lazyGenerateTodayEventsIfNeeded("p1");
            release.countDown();
            first.get(5, TimeUnit.SECONDS);
            scheduler.lazyGenerateTodayEventsIfNeeded("p1");
            verify(pythonClient, times(1)).generateEvents(any());
            verify(eventService, times(1)).insertEvents(eq("p1"), anyList());
        } finally {
            release.countDown();
            runner.shutdownNow();
        }
    }

    @Test
    void generationFailureReleasesOwnershipForNextHeartbeat() {
        when(pythonClient.generateEvents(any())).thenThrow(new IllegalStateException("offline"))
                .thenReturn(generatedPlan());
        scheduler.lazyGenerateTodayEventsIfNeeded("p1");
        scheduler.lazyGenerateTodayEventsIfNeeded("p1");
        verify(pythonClient, times(2)).generateEvents(any());
        verify(eventService).insertEvents(eq("p1"), anyList());
    }

    @Test
    void pauseDuringGenerationDiscardsCompletedPlan() {
        when(pythonClient.generateEvents(any())).thenAnswer(inv -> {
            persona.setStatus("paused");
            return generatedPlan();
        });
        scheduler.lazyGenerateTodayEventsIfNeeded("p1");
        verify(eventService, never()).insertEvents(anyString(), anyList());
    }

    @Test
    void pauseWhileEventModelRunsStopsReplyDeltasAndFollowingSleep() {
        DailyEvent sleep = new DailyEvent();
        sleep.setId("sleep-2");
        sleep.setEventType("sleep");
        when(eventService.findDue("p1")).thenReturn(List.of(event, sleep));
        when(pythonClient.triggerEvent(any())).thenAnswer(inv -> {
            persona.setStatus("paused");
            EventTriggerResponse response = new EventTriggerResponse();
            response.setShouldContactUser(true);
            response.setReply(Map.of("raw_text", "hello"));
            response.setRelationshipDeltas(Map.of("trust_delta", 2));
            return response;
        });
        scheduler.scanForPersona(persona);
        verifyNoInteractions(messageService);
        verify(relationshipEngine, never()).applyDeltas(anyString(), any());
        verify(eventService, never()).invalidateEvent(any());
        verify(stateMachine, never()).onSleepEvent(any());
        assertEquals("paused", persona.getStatus());
    }

    @Test
    void staleActiveSnapshotCannotConsumeSleepAfterPause() {
        Persona paused = new Persona();
        paused.setId("p1");
        paused.setStatus("paused");
        when(personaService.getById("p1")).thenReturn(paused);
        event.setEventType("sleep");
        scheduler.scanForPersona(persona);
        verify(stateMachine, never()).onSleepEvent(any());
        verify(eventService, never()).invalidateEvent(any());
    }

    private EventGenerateResponse generatedPlan() {
        EventGenerateResponse response = new EventGenerateResponse();
        response.setEvents(List.of(Map.of("time", "12:00:00", "type", "routine", "description", "Lunch")));
        return response;
    }
}
