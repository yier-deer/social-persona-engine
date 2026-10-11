package com.socialpersona.event.scheduler;

import com.socialpersona.event.service.EventService;
import com.socialpersona.message.burst.BurstGroupManager;
import com.socialpersona.message.repository.MessageMapper;
import com.socialpersona.message.scheduler.MessageScanScheduler;
import com.socialpersona.persona.service.PersonaService;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.Mockito.*;

class AutomationGuardTest {
    @Test
    void disabledAutomationDoesNotGenerateOrScanEvents() {
        EventTriggerScheduler scheduler = new EventTriggerScheduler();
        PersonaService personas = mock(PersonaService.class);
        EventService events = mock(EventService.class);
        ReflectionTestUtils.setField(scheduler, "automationEnabled", false);
        ReflectionTestUtils.setField(scheduler, "personaService", personas);
        ReflectionTestUtils.setField(scheduler, "eventService", events);
        scheduler.hourlyEventCheck();
        scheduler.minuteEventCheck();
        scheduler.midnightEventGeneration();
        scheduler.startupGenerateEventsForAllActive();
        scheduler.lazyGenerateTodayEventsIfNeeded("p1");
        scheduler.scanEvents();
        verifyNoInteractions(personas, events);
    }

    @Test
    void disabledAutomationDoesNotScanOrScheduleMessages() {
        MessageScanScheduler scheduler = new MessageScanScheduler();
        MessageMapper messages = mock(MessageMapper.class);
        ReflectionTestUtils.setField(scheduler, "automationEnabled", false);
        ReflectionTestUtils.setField(scheduler, "messageMapper", messages);
        scheduler.registerPersona("p1");
        scheduler.hourlyScan();
        scheduler.minuteScan();
        scheduler.secondScan();
        verifyNoInteractions(messages);
        BurstGroupManager burst = new BurstGroupManager();
        ReflectionTestUtils.setField(burst, "automationEnabled", false);
        assertDoesNotThrow(() -> burst.scheduleBurst("p1", List.of(
                new com.socialpersona.message.entity.ScheduledMessage(),
                new com.socialpersona.message.entity.ScheduledMessage()), null));
    }
}
