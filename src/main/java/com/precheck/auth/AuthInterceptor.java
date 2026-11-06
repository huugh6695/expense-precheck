package com.precheck.auth;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.nio.charset.StandardCharsets;

/**
 * 把所有 {@code /api/**} 请求拦下来验签，验不过直接 401，一个字的预检结果都不给。
 *
 * <p><b>为什么是整体拒绝，而不是"降级成身份未知照常预检"。</b>
 * 因为台账的价值全在"可信"两个字。放行一次不可信的请求，台账里就多一条假名字的记录，
 * 而这条记录以后会拿去拦别人（"这张票张三报过"）。一次妥协污染的是长期数据，
 * 比当场报错贵得多。
 *
 * <p><b>为什么用拦截器而不是在 Controller 里各写一遍。</b>
 * 现在有 4 个接口，将来会有更多。验签写在拦截器里，新加接口默认就是被保护的——
 * 这叫"默认安全"。反过来（每个接口自己记得调验签）迟早会漏一个。
 */
@Component
public class AuthInterceptor implements HandlerInterceptor {

    private static final Logger log = LoggerFactory.getLogger(AuthInterceptor.class);

    /** 验签通过后，把可信的申请人放进 request attribute，Controller 从这里取。 */
    public static final String VERIFIED_APPLICANT = "precheck.verifiedApplicant";

    private final ApplicantVerifier verifier;
    private final AuthProperties props;

    public AuthInterceptor(ApplicantVerifier verifier, AuthProperties props) {
        this.verifier = verifier;
        this.props = props;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws Exception {

        // 关掉验签是"本机演示模式"，此时不拦，但也不会往 request 里放任何身份
        // ——Controller 拿不到可信身份，就会用请求体里的值，前端横幅会明确告诉用户这不作数。
        if (!props.isEnabled()) {
            return true;
        }

        ApplicantVerifier.Verdict verdict = verifier.verify(
                request.getHeader(ApplicantVerifier.HEADER_APPLICANT),
                request.getHeader(ApplicantVerifier.HEADER_TIMESTAMP),
                request.getHeader(ApplicantVerifier.HEADER_SIGNATURE));

        if (verdict.passed()) {
            request.setAttribute(VERIFIED_APPLICANT, verdict.applicant());
            return true;
        }

        log.warn("验签失败：{} {} —— {}", request.getMethod(), request.getRequestURI(), verdict.reason());
        reject(response, verdict.reason());
        return false;
    }

    /**
     * 返回 401。
     *
     * <p>手写 JSON 而不是走序列化框架：只有两个字段、内容全是服务端自己写死的字符串，
     * 没有拼接用户输入，不存在注入问题。这样也不必为了一个错误响应去依赖具体的 Jackson 版本
     * （Boot 4 换成了 Jackson 3，tools.jackson 包名，少沾一点少一个坑）。
     */
    private void reject(HttpServletResponse response, String reason) throws Exception {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write("{\"error\":\"unauthorized\",\"reason\":\"" + reason + "\"}");
        response.getWriter().flush();
    }
}
