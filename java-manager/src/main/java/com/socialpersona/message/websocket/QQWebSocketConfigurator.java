package com.socialpersona.message.websocket;

import com.socialpersona.persona.service.PersonaService;
import jakarta.servlet.Filter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.websocket.server.ServerEndpointConfig;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.context.annotation.Bean;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.net.URI;

@Component
public class QQWebSocketConfigurator extends ServerEndpointConfig.Configurator
        implements ApplicationContextAware {

    private static ApplicationContext applicationContext;

    @Override
    public void setApplicationContext(ApplicationContext ctx) {
        applicationContext = ctx;
    }

    @Override
    public boolean checkOrigin(String originHeaderValue) {
        if (originHeaderValue == null || originHeaderValue.isBlank()) return true;
        try {
            URI origin = URI.create(originHeaderValue);
            String host = origin.getHost();
            return ("http".equals(origin.getScheme()) || "https".equals(origin.getScheme()))
                    && ("localhost".equalsIgnoreCase(host) || "127.0.0.1".equals(host)
                    || "::1".equals(host) || "[::1]".equals(host));
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    @Bean
    public FilterRegistrationBean<Filter> qqLoopbackPeerFilter() {
        Filter filter = (request, response, chain) -> {
            HttpServletRequest httpRequest = (HttpServletRequest) request;
            boolean loopback;
            try {
                loopback = InetAddress.getByName(httpRequest.getRemoteAddr()).isLoopbackAddress();
            } catch (Exception e) {
                loopback = false;
            }
            if (!loopback) {
                ((HttpServletResponse) response).sendError(HttpServletResponse.SC_FORBIDDEN, "QQ WebSocket accepts local peers only");
                return;
            }
            chain.doFilter(request, response);
        };
        FilterRegistrationBean<Filter> registration = new FilterRegistrationBean<>(filter);
        registration.addUrlPatterns("/ws/qq");
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        return registration;
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> T getEndpointInstance(Class<T> endpointClass) throws InstantiationException {
        if (endpointClass == QQWebSocketHandler.class && applicationContext != null) {
            PersonaService personaService = applicationContext.getBean(PersonaService.class);
            return (T) new QQWebSocketHandler(personaService);
        }
        return super.getEndpointInstance(endpointClass);
    }
}
