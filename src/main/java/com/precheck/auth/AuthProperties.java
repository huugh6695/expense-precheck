package com.precheck.auth;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 接入约定配置（precheck.auth.*）。
 *
 * <p>背景：这套工具当作 OA 的插件用，申请人必须由 OA 传进来，不能让员工自己在页面上填——
 * 否则 R5 的"这张票别人报过"就形同虚设，改个名字就绕过去了。
 */
@Component
@ConfigurationProperties(prefix = "precheck.auth")
public class AuthProperties {

    private static final Logger log = LoggerFactory.getLogger(AuthProperties.class);

    /**
     * 验签开关。
     *
     * <p>默认 false，是为了"克隆下来就能跑、就能演示"。但这个默认值本身有风险，
     * 所以关闭时每次启动都会打一条醒目的 WARN，前端也会挂横幅——
     * <b>允许降级，不允许默默降级</b>，这是整套系统一以贯之的原则。
     */
    private boolean enabled = false;

    /** 与 OA 约定的共享密钥。开着验签却留空会直接启动失败，见下面的校验。 */
    private String secret = "";

    /**
     * 时间戳允许的偏移（秒）。默认 5 分钟。
     *
     * <p>为什么需要它：签名本身没有"过期"概念，同一个请求头可以被无限次重放。
     * 加上时间窗，截获的请求两分钟后就失效了。代价是两台机器的时钟不能差太多。
     */
    private long toleranceSeconds = 300;

    /**
     * 启动自检：开着验签却没配密钥，直接不许启动。
     *
     * <p>这是故意的"失败得越早越好"。如果这里放行，服务会带着一个空密钥跑起来，
     * 空密钥算出来的 HMAC 谁都能算，等于完全没有验签——但日志上看一切正常，
     * 谁也不会发现。宁可起不来，也不能假装验过了。
     */
    @PostConstruct
    void check() {
        if (enabled && (secret == null || secret.isBlank())) {
            throw new IllegalStateException(
                    "precheck.auth.enabled=true 但没有配置 precheck.auth.secret。"
                            + "请用环境变量注入与 OA 约定的共享密钥（PRECHECK_AUTH_SECRET），"
                            + "或者把 precheck.auth.enabled 设为 false（仅限本机演示）。");
        }
        if (!enabled) {
            log.warn("⚠️ 验签已关闭 —— 申请人取自请求体，员工可以随便填，历史台账不可作为追责依据。"
                    + "生产环境请设置 precheck.auth.enabled=true 并配置 secret。");
        }
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getSecret() {
        return secret;
    }

    public void setSecret(String secret) {
        this.secret = secret;
    }

    public long getToleranceSeconds() {
        return toleranceSeconds;
    }

    public void setToleranceSeconds(long toleranceSeconds) {
        this.toleranceSeconds = toleranceSeconds;
    }
}
