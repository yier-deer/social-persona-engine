package com.socialpersona.message.websocket;

import com.socialpersona.persona.entity.Persona;
import com.socialpersona.persona.service.PersonaService;
import jakarta.servlet.Filter;
import jakarta.websocket.RemoteEndpoint;
import jakarta.websocket.Session;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Method;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class QQTransportRegressionTest {
    private PersonaService personas;
    private QQWebSocketHandler handler;

    @BeforeEach
    void setUp() {
        ((Map<?, ?>) ReflectionTestUtils.getField(QQWebSocketHandler.class, "napCatSessions")).clear();
        ReflectionTestUtils.setField(QQWebSocketHandler.class, "applicationContext", null);
        personas = mock(PersonaService.class);
        handler = new QQWebSocketHandler(personas);
    }

    private Session connectedSession(String id) {
        Session session = mock(Session.class);
        when(session.getId()).thenReturn(id);
        when(session.isOpen()).thenReturn(true);
        when(session.getBasicRemote()).thenReturn(mock(RemoteEndpoint.Basic.class));
        return session;
    }

    private void registerThroughMessage(String botId, Session session) {
        handler.onMessage("{\"post_type\":\"message\",\"message_type\":\"private\","
                + "\"self_id\":\"" + botId + "\",\"user_id\":\"999\",\"raw_message\":\"ping\"}", session);
    }

    @Test
    void standardHeartbeatRegistersBotBeforeFirstPrivateMessage() throws Exception {
        Session session = connectedSession("heartbeat");
        handler.onMessage("{\"time\":1,\"self_id\":10001,\"post_type\":\"meta_event\","
                + "\"meta_event_type\":\"heartbeat\",\"status\":{\"online\":true,\"good\":true},\"interval\":5000}", session);

        handler.sendReply("888", "hello");

        verify(session.getBasicRemote()).sendText(contains("hello"));
    }

    @Test
    void lifecycleConnectRegistersBotBeforeFirstHeartbeat() throws Exception {
        Session session = connectedSession("lifecycle");
        handler.onMessage("{\"time\":1,\"self_id\":10001,\"post_type\":\"meta_event\","
                + "\"meta_event_type\":\"lifecycle\",\"sub_type\":\"connect\"}", session);

        handler.sendReply("888", "hello");

        verify(session.getBasicRemote()).sendText(contains("hello"));
    }

    @Test
    void twoBotsSendOnlyThroughTheirBoundSessions() throws Exception {
        Session first = connectedSession("first");
        Session second = connectedSession("second");
        registerThroughMessage("10001", first);
        registerThroughMessage("10002", second);
        Method send = assertDoesNotThrow(() -> QQWebSocketHandler.class.getMethod(
                "sendReply", String.class, String.class, String.class), "Sending must identify the bot QQ");

        send.invoke(handler, "10001", "888", "first reply");
        send.invoke(handler, "10002", "888", "second reply");

        verify(first.getBasicRemote(), times(1)).sendText(contains("first reply"));
        verify(second.getBasicRemote(), times(1)).sendText(contains("second reply"));
        verify(first.getBasicRemote(), never()).sendText(contains("second reply"));
    }

    @Test
    void missingConnectionIsReportedToTheCaller() {
        assertThrows(IllegalStateException.class, () -> handler.sendReply("888", "hello"));
    }

    @Test
    void pausedPersonaIsNotDispatchedToInference() {
        Persona persona = new Persona();
        persona.setId("persona");
        persona.setAiQq("10001");
        persona.setOwnerQq("888");
        persona.setStatus("paused");
        when(personas.findByAiQQ("10001")).thenReturn(persona);
        ApplicationContext context = mock(ApplicationContext.class);
        QQAsyncMessageHandler inference = mock(QQAsyncMessageHandler.class);
        when(context.getBean(QQAsyncMessageHandler.class)).thenReturn(inference);
        handler.setApplicationContext(context);

        handler.onMessage("{\"post_type\":\"message\",\"message_type\":\"private\","
                + "\"self_id\":10001,\"user_id\":888,\"raw_message\":\"hello\"}", connectedSession("paused"));

        verify(inference, never()).handleMessage(anyString(), anyString());
    }

    @Test
    void remoteBrowserOriginIsRejected() {
        QQWebSocketConfigurator configurator = new QQWebSocketConfigurator();
        assertFalse(configurator.checkOrigin("https://external.example"));
        assertTrue(configurator.checkOrigin("http://localhost:5173"));
        assertTrue(configurator.checkOrigin(null));
    }

    @Test
    @SuppressWarnings("unchecked")
    void nonLoopbackPeerCannotUpgradeEvenWithLocalOrigin() throws Exception {
        try (var context = new AnnotationConfigApplicationContext(QQWebSocketConfigurator.class)) {
            var registrations = context.getBeansOfType(FilterRegistrationBean.class);
            assertFalse(registrations.isEmpty(), "The QQ endpoint requires a loopback peer filter");
            Filter filter = registrations.values().iterator().next().getFilter();
            MockHttpServletRequest request = new MockHttpServletRequest("GET", "/ws/qq");
            request.setRemoteAddr("203.0.113.5");
            request.addHeader("Origin", "http://localhost:5173");
            MockHttpServletResponse response = new MockHttpServletResponse();
            MockFilterChain chain = new MockFilterChain();

            filter.doFilter(request, response, chain);

            assertEquals(403, response.getStatus());
            assertNull(chain.getRequest());
            request.setRemoteAddr("127.0.0.1");
            filter.doFilter(request, new MockHttpServletResponse(), chain);
            assertSame(request, chain.getRequest());
        }
    }
}
