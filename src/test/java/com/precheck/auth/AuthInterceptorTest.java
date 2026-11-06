package com.precheck.auth;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 拦截器的测试：验签不通过时，请求必须被挡在业务代码之前。
 *
 * <p>这里用 {@code MockHttpServletRequest} 而不是启动整个 Spring 容器——
 * 要验证的是"拦不拦、拦了给什么响应"，跟 MVC 怎么路由无关，起容器只会让测试变慢变脆。
 */
class AuthInterceptorTest {

    private static final String SECRET = "s3cret-for-test";
    private static final long NOW = 1762400000000L;

    private AuthProperties props(boolean enabled) {
        AuthProperties p = new AuthProperties();
        p.setEnabled(enabled);
        p.setSecret(SECRET);
        p.setToleranceSeconds(300);
        return p;
    }

    private AuthInterceptor interceptor(AuthProperties props) {
        ApplicantVerifier verifier =
                new ApplicantVerifier(props, Clock.fixed(Instant.ofEpochMilli(NOW), ZoneOffset.UTC));
        return new AuthInterceptor(verifier, props);
    }

    private String sign(ApplicantVerifier v, String applicant, long ts) {
        return v.signHex(SECRET, ts, applicant);
    }

    @Test
    @DisplayName("关闭验签时不拦，但也不给任何可信身份")
    void disabledShouldPassWithoutIdentity() throws Exception {
        AuthProperties off = props(false);
        AuthInterceptor it = interceptor(off);

        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/api/precheck");
        MockHttpServletResponse res = new MockHttpServletResponse();

        assertTrue(it.preHandle(req, res, new Object()));
        assertNull(req.getAttribute(AuthInterceptor.VERIFIED_APPLICANT),
                "关掉验签就绝对不能在 request 里放身份，否则后面的代码会以为这是可信的");
    }

    @Test
    @DisplayName("开着验签但没有请求头：401，且一个字的业务结果都不给")
    void missingHeadersShouldBeRejected() throws Exception {
        AuthInterceptor it = interceptor(props(true));

        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/api/precheck");
        MockHttpServletResponse res = new MockHttpServletResponse();

        assertFalse(it.preHandle(req, res, new Object()), "验签失败必须中断请求");
        assertEquals(401, res.getStatus());
        assertTrue(res.getContentAsString().contains("unauthorized"),
                "实际响应体：" + res.getContentAsString());
    }

    @Test
    @DisplayName("申请人被改动：401")
    void tamperedApplicantShouldBeRejected() throws Exception {
        AuthProperties on = props(true);
        ApplicantVerifier v = new ApplicantVerifier(on, Clock.fixed(Instant.ofEpochMilli(NOW), ZoneOffset.UTC));
        AuthInterceptor it = new AuthInterceptor(v, on);

        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/api/precheck");
        req.addHeader(ApplicantVerifier.HEADER_APPLICANT, "张三");          // 想冒充
        req.addHeader(ApplicantVerifier.HEADER_TIMESTAMP, String.valueOf(NOW));
        req.addHeader(ApplicantVerifier.HEADER_SIGNATURE, sign(v, "李四", NOW));  // 签的是李四

        MockHttpServletResponse res = new MockHttpServletResponse();
        assertFalse(it.preHandle(req, res, new Object()));
        assertEquals(401, res.getStatus());
    }

    @Test
    @DisplayName("验签通过：把可信的申请人放进 request")
    void validHeadersShouldExposeVerifiedApplicant() throws Exception {
        AuthProperties on = props(true);
        ApplicantVerifier v = new ApplicantVerifier(on, Clock.fixed(Instant.ofEpochMilli(NOW), ZoneOffset.UTC));
        AuthInterceptor it = new AuthInterceptor(v, on);

        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/api/precheck");
        req.addHeader(ApplicantVerifier.HEADER_APPLICANT, "张三");
        req.addHeader(ApplicantVerifier.HEADER_TIMESTAMP, String.valueOf(NOW));
        req.addHeader(ApplicantVerifier.HEADER_SIGNATURE, sign(v, "张三", NOW));

        MockHttpServletResponse res = new MockHttpServletResponse();
        assertTrue(it.preHandle(req, res, new Object()));
        assertEquals("张三", req.getAttribute(AuthInterceptor.VERIFIED_APPLICANT));
    }
}
