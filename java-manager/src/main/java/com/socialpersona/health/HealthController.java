package com.socialpersona.health;

import com.socialpersona.gateway.PythonClient;
import com.socialpersona.gateway.dto.HealthResponse;
import com.socialpersona.message.websocket.QQWebSocketHandler;
import com.socialpersona.persona.service.PersonaService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.List;

/**
 * Java 健康检查控制器
 *
 * 设计依据：规格书 Day 1 可验证标准 —— "curl :8080/api/health 返回 200"
 *
 * 职责：
 *   1. 暴露自身 GET /api/health 端点
 *   2. 透过 Feign 调用 Python 的 GET /api/health
 *   3. 将 Java 状态 + Python 状态合并返回
 *
 * 为什么在 Controller 层直接调 PythonClient 而非通过 Service：
 *   健康检查本身无业务逻辑，只是透传查询。Day 1 目标是验证通道，不是构建完整业务层。
 *
 * 错误处理策略：
 *   Python 未启动 → 不会抛 500，而是返回 pythonStatus: "unreachable"
 *   这样前端可以显示"Python 核心未连接"而非崩溃页面
 */
@RestController
public class HealthController {

    /**
     * Spring 自动注入 PythonClient 的 Feign 动态代理实例
     *
     * ★ 依赖注入（DI）原理速览：
     *   1. @EnableFeignClients 扫描到 @FeignClient("python-core")
     *   2. Spring 在启动时自动生成 PythonClient 接口的实现类（JDK 动态代理）
     *   3. @Autowired 把这个代理对象注入到 healthController 里
     *   4. 你调用 pythonClient.health() → 代理拦截 → 构造 HTTP GET → 发到 :8000
     */
    @Autowired
    private PythonClient pythonClient;

    @Autowired
    private QQWebSocketHandler qqWs;

    @Autowired
    private RedisConnectionFactory redisFactory;

    @Autowired
    private PersonaService personaService;

    /**
     * ★ Java 管理端健康检查端点
     *
     * 返回格式：
     * {
     *   "app": "social-persona-manager",
     *   "javaStatus": "ok",
     *   "pythonStatus": { "status": "ok", ... }  ← 来自 Python 响应
     *   或 "pythonStatus": "unreachable"          ← Python 连不上时
     * }
     *
     * HTTP 方法选 GET 的原因：
     *   健康检查是纯查询操作，不改变服务器状态，符合 RESTful 规范中 GET 的语义
     */
    @GetMapping("/api/health")
    public Map<String, Object> health() {

        // 用 LinkedHashMap 保持字段输出顺序（app → javaStatus → pythonStatus）
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("app", "social-persona-manager");
        result.put("javaStatus", "ok");
        Map<String, Object> services = new LinkedHashMap<>();

        /*
         * 为什么用 try-catch 包裹 Python 调用：
         *   如果 Python 还没启动，Feign 会抛 FeignException（连接拒绝），
         *   如果不捕获 → 500 Internal Server Error → 前端以为 Java 也挂了。
         *   捕获后 → 返回 pythonStatus: "unreachable" → 前端只提示 Python 模块未连接。
         */
        try {
            HealthResponse pythonHealth = pythonClient.health();
            result.put("pythonStatus", pythonHealth);
            services.put("pythonCore", Map.of("status",
                    pythonHealth != null && "ok".equals(pythonHealth.getStatus()) ? "connected" : "error"));
        } catch (Exception e) {
            // Python 不可达：不是 Java 的错，用 Map 告知前端状态而非抛异常
            Map<String, Object> unreachableInfo = new LinkedHashMap<>();
            unreachableInfo.put("status", "unreachable");
            unreachableInfo.put("error", "无法连接到 Python 核心服务 (http://127.0.0.1:8000)");
            result.put("pythonStatus", unreachableInfo);
            services.put("pythonCore", Map.of("status", "disconnected"));
        }

        try (var connection = redisFactory.getConnection()) {
            services.put("redis", Map.of("status", "PONG".equals(connection.ping()) ? "connected" : "error", "optional", true));
        } catch (Exception e) {
            services.put("redis", Map.of("status", "disconnected", "optional", true));
        }
        List<String> botIds = qqWs.getConnectedBotIds();
        if (botIds == null) botIds = List.of();
        services.put("napcat", Map.of("status", botIds.isEmpty() ? "disconnected" : "connected", "botIds", botIds));
        result.put("services", services);
        Map<String, Object> apiConfig;
        try {
            apiConfig = personaService.loadGlobalApiConfig();
        } catch (Exception ignored) {
            apiConfig = Map.of();
        }
        if (apiConfig == null) apiConfig = Map.of();
        Object apiKey = apiConfig.get("api_key");
        result.put("llm", Map.of("status", apiKey instanceof String && !((String) apiKey).isBlank() ? "configured" : "unconfigured"));

        return result;
    }
}
