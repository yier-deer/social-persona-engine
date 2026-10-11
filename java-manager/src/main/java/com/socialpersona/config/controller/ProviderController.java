package com.socialpersona.config.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.*;

@RestController
@RequestMapping("/api/config")
public class ProviderController {

    private static final Logger log = LoggerFactory.getLogger(ProviderController.class);
    private final ObjectMapper objectMapper = new ObjectMapper();

    /** 所有 Provider 元数据（含模型列表） */
    private static List<Map<String, Object>> cachedProviders = null;

    @GetMapping("/providers")
    public List<Map<String, Object>> getProviders(
            @RequestParam(required = false) String type) {
        List<Map<String, Object>> all = loadAllProviders();
        if (type == null || type.isEmpty()) return all;
        return all.stream()
                .filter(p -> type.equals(p.get("type")))
                .collect(java.util.stream.Collectors.toList());
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> loadAllProviders() {
        if (cachedProviders != null) return cachedProviders;
        try {
            InputStream in = new ClassPathResource("providers.json").getInputStream();
            Map<String, Object> root = objectMapper.readValue(in, Map.class);
            List<Map<String, Object>> list = (List<Map<String, Object>>) root.get("providers");
            cachedProviders = list;
            return list;
        } catch (Exception e) {
            log.error("读取 providers.json 失败: {}", e.getMessage());
            return List.of();
        }
    }

    /**
     * 动态刷新某个 Provider 的模型列表
     * 调 Provider 的 /models API，用用户 Key 鉴权
     */
    @PostMapping("/providers/{id}/refresh-models")
    public Map<String, Object> refreshModels(@PathVariable String id,
                                             @RequestBody Map<String, String> body) {
        String apiKey = body.getOrDefault("apiKey", "");
        String baseUrl = body.getOrDefault("baseUrl", "");

        List<Map<String, Object>> providers = loadAllProviders();
        Map<String, Object> provider = null;
        for (Map<String, Object> p : providers) {
            if (id.equals(p.get("id"))) { provider = p; break; }
        }
        if (provider == null) {
            return Map.of("success", false, "error", "Provider 不存在: " + id);
        }

        String modelsUrl = (String) provider.getOrDefault("models_url", null);

        // ★ 如果 models_url 为 null，用用户填的 baseUrl + /models 作为 fallback
        if ((modelsUrl == null || modelsUrl.isEmpty()) && baseUrl != null && !baseUrl.isEmpty()) {
            modelsUrl = baseUrl.replaceAll("/+$", "") + "/models";
        }

        if (modelsUrl == null || modelsUrl.isEmpty()) {
            return Map.of("success", false, "error", "该 Provider 不支持在线获取模型列表");
        }

        // 允许用自定义 URL 覆盖（非 fallback 情况）
        if (baseUrl != null && !baseUrl.isEmpty()
                && provider.get("models_url") != null
                && !((String) provider.get("models_url")).isEmpty()) {
            modelsUrl = baseUrl.replaceAll("/+$", "") + "/models";
        }

        try {
            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(10))
                    .build();

            HttpRequest.Builder reqBuilder = HttpRequest.newBuilder()
                    .uri(URI.create(modelsUrl))
                    .timeout(Duration.ofSeconds(15))
                    .header("Accept", "application/json");

            String authType = (String) provider.getOrDefault("auth_type", "bearer");
            if ("x-api-key".equals(authType)) {
                reqBuilder.header("x-api-key", apiKey);
            } else {
                reqBuilder.header("Authorization", "Bearer " + apiKey);
            }

            HttpResponse<String> resp = client.send(reqBuilder.GET().build(),
                    HttpResponse.BodyHandlers.ofString());

            if (resp.statusCode() == 200) {
                @SuppressWarnings("unchecked")
                Map<String, Object> body2 = objectMapper.readValue(resp.body(), Map.class);
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> rawModels = (List<Map<String, Object>>) body2.get("data");

                List<Map<String, Object>> parsed = new ArrayList<>();
                if (rawModels != null) {
                    for (Map<String, Object> m : rawModels) {
                        String modelId = (String) m.get("id");
                        if (modelId != null && !modelId.contains("embedding") && !modelId.contains("moderation")) {
                            parsed.add(Map.of(
                                    "id", modelId,
                                    "name", modelId,
                                    "desc", m.getOrDefault("owned_by", "")
                            ));
                        }
                    }
                }

                // 更新缓存
                provider.put("models", parsed);
                return Map.of("success", true, "models", parsed);
            } else {
                return Map.of("success", false, "error",
                        "HTTP " + resp.statusCode() + ": 无法获取模型列表，请检查 Key 和地址");
            }
        } catch (java.net.ConnectException e) {
            return Map.of("success", false, "error", "无法连接到 " + modelsUrl);
        } catch (Exception e) {
            log.error("刷新模型失败: {}", e.getMessage());
            return Map.of("success", false, "error", e.getMessage());
        }
    }

    /** 测试连接 */
    @PostMapping("/test")
    public Map<String, Object> testConnection(@RequestBody Map<String, String> body) {
        String provider = body.getOrDefault("provider", "deepseek");
        String apiKey = body.get("apiKey");
        String baseUrl = body.getOrDefault("baseUrl", "");
        String model = body.getOrDefault("model", "deepseek-chat");

        if (apiKey == null || apiKey.isEmpty()) {
            return Map.of("success", false, "error", "未提供 API Key");
        }

        // 查 provider 元数据
        Map<String, Object> providerMeta = null;
        for (Map<String, Object> p : loadAllProviders()) {
            if (provider.equals(p.get("id"))) { providerMeta = p; break; }
        }

        // 用 provider 默认 URL（如果用户没填 baseUrl）
        if (baseUrl.isEmpty() && providerMeta != null) {
            baseUrl = (String) providerMeta.getOrDefault("default_url", "");
        }
        if (baseUrl.isEmpty()) baseUrl = "https://api.deepseek.com/v1";
        baseUrl = baseUrl.replaceAll("/+$", "");

        String apiFormat = providerMeta != null
                ? (String) providerMeta.getOrDefault("api_format", "openai")
                : "openai";
        String authType = providerMeta != null
                ? (String) providerMeta.getOrDefault("auth_type", "bearer")
                : "bearer";

        try {
            long start = System.currentTimeMillis();

            String url = baseUrl + buildTestPath(apiFormat);
            String reqBody = buildTestRequestBody(apiFormat, model);

            HttpRequest.Builder req = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(20))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(reqBody));

            if ("x-api-key".equals(authType)) {
                req.header("x-api-key", apiKey);
                req.header("anthropic-version", "2023-06-01");
            } else {
                req.header("Authorization", "Bearer " + apiKey);
            }

            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(10))
                    .build();

            HttpResponse<String> resp = client.send(req.build(), HttpResponse.BodyHandlers.ofString());
            long latency = System.currentTimeMillis() - start;

            if (resp.statusCode() == 200) {
                @SuppressWarnings("unchecked")
                Map<String, Object> respBody = objectMapper.readValue(resp.body(), Map.class);
                String reply = parseTestResponse(apiFormat, respBody);
                return Map.of("success", true, "model", model, "response", reply, "latency_ms", latency);
            } else {
                String errMsg = resp.statusCode() + " ";
                if (resp.statusCode() == 401) errMsg += "Key 无效或已过期";
                else if (resp.statusCode() == 404) errMsg += "接口地址或模型名错误";
                else if (resp.statusCode() == 429) errMsg += "请求太频繁，稍后重试";
                else errMsg += "提供商返回错误，请检查配置";
                return Map.of("success", false, "error", errMsg);
            }
        } catch (java.net.ConnectException e) {
            return Map.of("success", false, "error", "无法连接: " + baseUrl + " — 检查网络或地址");
        } catch (java.net.http.HttpTimeoutException e) {
            return Map.of("success", false, "error", "连接超时 — 地址不通或网络太慢");
        } catch (Exception e) {
            log.warn("连接测试失败: {}", e.getClass().getSimpleName());
            return Map.of("success", false, "error", "连接测试失败，请检查地址、证书和提供商配置");
        }
    }

    /**
     * 根据 api_format 构建测试请求的 URL 路径
     */
    static String buildTestPath(String apiFormat) {
        if (apiFormat != null && apiFormat.contains("image")) {
            return "/images/generations";
        }
        return "/chat/completions";
    }

    /**
     * 根据 api_format 构建测试请求体 JSON
     *
     * ★ image API：仅发 {model, prompt}——不同 Provider 对 size/n/watermark 的格式要求不同。
     *   发最小请求体，让 API 用默认值，保证最大兼容性。
     */
    static String buildTestRequestBody(String apiFormat, String model) {
        boolean isImage = apiFormat != null && apiFormat.contains("image");
        Map<String, Object> req = new LinkedHashMap<>();
        req.put("model", model);
        if (isImage) {
            req.put("prompt", "a cute cat on windowsill, digital art");
        } else {
            req.put("max_tokens", 10);
            req.put("temperature", 0);
            req.put("messages", List.of(
                    Map.of("role", "user", "content", "Say hello in one word")
            ));
        }
        try {
            return new ObjectMapper().writeValueAsString(req);
        } catch (Exception e) {
            return "{}";
        }
    }

    /**
     * 根据 api_format 解析测试响应
     */
    @SuppressWarnings("unchecked")
    static String parseTestResponse(String apiFormat, Map<String, Object> respBody) {
        boolean isImage = apiFormat != null && apiFormat.contains("image");
        if (isImage) {
            List<Map<String, Object>> data = (List<Map<String, Object>>) respBody.get("data");
            if (data != null && !data.isEmpty()) {
                Object urlVal = data.get(0).get("url");
                if (urlVal != null) {
                    String s = urlVal.toString();
                    return "图片已生成: " + s.substring(0, Math.min(60, s.length())) + "...";
                }
            }
            return "无图片返回";
        } else {
            List<Map<String, Object>> choices = (List<Map<String, Object>>) respBody.get("choices");
            if (choices != null && !choices.isEmpty()) {
                Map<String, Object> msg = (Map<String, Object>) choices.get(0).get("message");
                if (msg != null) return (String) msg.getOrDefault("content", "无响应");
            }
            return "无响应";
        }
    }

}
