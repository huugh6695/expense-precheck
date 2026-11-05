package com.precheck.ai;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 大模型相关配置，对应 application.yml 里的 precheck.ai.*
 *
 * <p>把 Key 弄成"可以为空"，是这个设计里很重要的一个决定：
 * 没配 Key 时 aiAvailable() 返回 false，语义规则直接不出结果，
 * 系统退化成纯代码规则——<b>而不是启动失败或者每次请求都报错</b>。
 * 企业环境里，AI 服务不可用是常态，产品必须能带着缺口继续工作。
 */
@Component
@ConfigurationProperties(prefix = "precheck.ai")
public class AiProperties {

    private boolean enabled = false;
    private String baseUrl = "https://api.deepseek.com";
    private String model = "deepseek-chat";
    private String apiKey = "";
    private int timeoutSeconds = 20;

    /** 只有"开关打开 + Key 非空"才算真的可用。 */
    public boolean available() {
        return enabled && apiKey != null && !apiKey.isBlank();
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public String getApiKey() {
        return apiKey;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }

    public int getTimeoutSeconds() {
        return timeoutSeconds;
    }

    public void setTimeoutSeconds(int timeoutSeconds) {
        this.timeoutSeconds = timeoutSeconds;
    }
}
