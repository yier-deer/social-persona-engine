package com.socialpersona.health;

import com.socialpersona.gateway.PythonClient;
import com.socialpersona.gateway.dto.HealthResponse;
import com.socialpersona.message.websocket.QQWebSocketHandler;
import com.socialpersona.persona.service.PersonaService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class HealthControllerTest {
    @Mock PythonClient pythonClient;
    @Mock PersonaService personaService;
    @Mock RedisConnectionFactory redisFactory;
    @Mock RedisConnection redis;
    @Mock QQWebSocketHandler qqWs;
    @InjectMocks HealthController controller;

    @BeforeEach
    void setUp() {
        lenient().when(personaService.loadGlobalApiConfig()).thenReturn(Map.of("api_key", "test-private-key"));
        lenient().when(redisFactory.getConnection()).thenReturn(redis);
        lenient().when(redis.ping()).thenReturn("PONG");
        lenient().when(qqWs.getConnectedBotIds()).thenReturn(List.of());
    }

    @Test
    void pythonReachabilityDoesNotDependOnMemoryAndLlmIsOnlyConfigured() {
        HealthResponse python = new HealthResponse();
        python.setStatus("ok");
        python.setLlmConnected(true);
        python.setMemoryConnected(false);
        when(pythonClient.health()).thenReturn(python);
        Map<String, Object> health = controller.health();
        Map<?, ?> services = (Map<?, ?>) health.get("services");
        assertNotNull(services);
        assertEquals("connected", ((Map<?, ?>) services.get("pythonCore")).get("status"));
        assertEquals("connected", ((Map<?, ?>) services.get("redis")).get("status"));
        assertEquals("disconnected", ((Map<?, ?>) services.get("napcat")).get("status"));
        assertEquals(List.of(), ((Map<?, ?>) services.get("napcat")).get("botIds"));
        assertEquals("configured", ((Map<?, ?>) health.get("llm")).get("status"));
        assertFalse(health.toString().contains("test-private-key"));
        verify(redis).close();
    }

    @Test
    void unavailableServicesStayObservableWithoutErrorBodies() {
        when(pythonClient.health()).thenThrow(new RuntimeException("upstream-secret"));
        when(redisFactory.getConnection()).thenThrow(new RuntimeException("redis-secret"));
        when(personaService.loadGlobalApiConfig()).thenReturn(Map.of());
        Map<String, Object> health = controller.health();
        Map<?, ?> services = (Map<?, ?>) health.get("services");
        assertNotNull(services);
        assertEquals("disconnected", ((Map<?, ?>) services.get("pythonCore")).get("status"));
        assertEquals("disconnected", ((Map<?, ?>) services.get("redis")).get("status"));
        assertEquals("unconfigured", ((Map<?, ?>) health.get("llm")).get("status"));
        assertFalse(health.toString().contains("secret"));
    }

    @Test
    void reportsOnlyIdentifiedConnectedBotIds() {
        HealthResponse python = new HealthResponse();
        python.setStatus("ok");
        when(pythonClient.health()).thenReturn(python);
        when(qqWs.getConnectedBotIds()).thenReturn(List.of("12345", "67890"));
        Map<?, ?> services = (Map<?, ?>) controller.health().get("services");
        assertEquals("connected", ((Map<?, ?>) services.get("napcat")).get("status"));
        assertEquals(List.of("12345", "67890"), ((Map<?, ?>) services.get("napcat")).get("botIds"));
    }
}
