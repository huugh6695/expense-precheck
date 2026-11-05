package com.precheck.ai;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 用 JDK 自带的 HttpClient 调 OpenAI 兼容接口（这里配的是 DeepSeek）。
 *
 * <p>为什么不用 LangChain / Spring AI：这里只需要"发一个 POST、取 content"，
 * 引一整套框架反而多一层要理解的东西。JDK 11 起 HttpClient 就内置了，零依赖。
 *
 * <p>三个必须做的事：
 * <ol>
 *   <li><b>temperature = 0</b>：检查类任务要稳定，同样的材料不能今天查出问题明天查不出；</li>
 *   <li><b>response_format = json_object</b>：逼模型只输出 JSON，省掉解析自由文本的痛苦；</li>
 *   <li><b>超时 + 不抛异常</b>：模型慢或挂了，预检不能跟着卡死。</li>
 * </ol>
 */
@Component
public class DeepSeekClient implements AiClient {

    private static final Logger log = LoggerFactory.getLogger(DeepSeekClient.class);

    private final AiProperties props;
    private final JsonMapper mapper = JsonMapper.builder().build();
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    public DeepSeekClient(AiProperties props) {
        this.props = props;
    }

    @Override
    public boolean available() {
        return props.available();
    }

    @Override
    public Optional<String> complete(String systemPrompt, String userPrompt) {
        if (!available()) {
            return Optional.empty();
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", props.getModel());
        body.put("temperature", 0);
        body.put("response_format", Map.of("type", "json_object"));
        body.put("messages", List.of(
                Map.of("role", "system", "content", systemPrompt),
                Map.of("role", "user", "content", userPrompt)
        ));

        try {
            String json = mapper.writeValueAsString(body);
            HttpRequest request = HttpRequest.newBuilder(
                            URI.create(props.getBaseUrl() + "/chat/completions"))
                    .timeout(Duration.ofSeconds(props.getTimeoutSeconds()))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + props.getApiKey())
                    .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
                    .build();

            HttpResponse<String> response =
                    http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

            if (response.statusCode() != 200) {
                // 注意：这里绝不打印 Key，也不打印完整请求体
                log.warn("模型返回 {}，本次语义检查跳过：{}", response.statusCode(), cut(response.body()));
                return Optional.empty();
            }
            return extractContent(response.body());
        } catch (Exception e) {
            // 网络超时、DNS 失败、JSON 解析失败……全都归到这里，统一降级
            log.warn("调用模型失败，本次语义检查跳过：{}", e.toString());
            return Optional.empty();
        }
    }

    /**
     * 从响应里取 choices[0].message.content。
     *
     * <p>这里刻意用 Map 手工取而不是定义一堆 DTO：字段少、路径固定，
     * 手工取反而更抗上游加字段（多出来的字段直接忽略）。
     */
    @SuppressWarnings("unchecked")
    private Optional<String> extractContent(String body) {
        try {
            Map<String, Object> root = mapper.readValue(body, Map.class);
            Object choices = root.get("choices");
            if (!(choices instanceof List<?> list) || list.isEmpty()) {
                return Optional.empty();
            }
            if (!(list.get(0) instanceof Map<?, ?> first)) {
                return Optional.empty();
            }
            Object message = first.get("message");
            if (!(message instanceof Map<?, ?> msg)) {
                return Optional.empty();
            }
            Object content = msg.get("content");
            return (content instanceof String s && !s.isBlank()) ? Optional.of(s) : Optional.empty();
        } catch (Exception e) {
            log.warn("解析模型响应失败：{}", e.toString());
            return Optional.empty();
        }
    }

    private String cut(String text) {
        if (text == null) {
            return "";
        }
        return text.length() > 200 ? text.substring(0, 200) + "…" : text;
    }
}
