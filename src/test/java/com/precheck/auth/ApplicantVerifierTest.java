package com.precheck.auth;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 验签逻辑的测试。
 *
 * <p>这类代码的测试思路和业务规则不一样：业务规则测"该不该拦"，验签要测的是
 * <b>"能不能被绕过"</b>。所以每个用例都是一个攻击动作：改名字、改时间、重放、格式变换。
 */
class ApplicantVerifierTest {

    private static final String SECRET = "s3cret-for-test";
    private static final long NOW = 1762400000000L;   // 2025-11-06T04:53:20Z，钉死
    private static final int TOLERANCE = 300;

    private AuthProperties props;
    private ApplicantVerifier verifier;

    @BeforeEach
    void setUp() {
        props = new AuthProperties();
        props.setEnabled(true);
        props.setSecret(SECRET);
        props.setToleranceSeconds(TOLERANCE);
        verifier = new ApplicantVerifier(props, Clock.fixed(Instant.ofEpochMilli(NOW), ZoneOffset.UTC));
    }

    private String sign(String applicant, long ts) {
        return verifier.signHex(SECRET, ts, applicant);
    }

    @Test
    @DisplayName("签名正确就通过，拿到的申请人是可信的")
    void validSignatureShouldPass() {
        var v = verifier.verify("张三", String.valueOf(NOW), sign("张三", NOW));

        assertTrue(v.passed(), "实际原因：" + v.reason());
        assertEquals("张三", v.applicant());
    }

    @Test
    @DisplayName("把名字改掉，签名就对不上")
    void changedApplicantShouldFail() {
        // 张三截获了李四的签名，想把名字改成自己
        var v = verifier.verify("张三", String.valueOf(NOW), sign("李四", NOW));

        assertFalse(v.passed());
        assertTrue(v.reason().contains("签名不符"), "实际原因：" + v.reason());
    }

    @Test
    @DisplayName("把时间戳改掉，签名就对不上")
    void changedTimestampShouldFail() {
        var v = verifier.verify("张三", String.valueOf(NOW + 1000), sign("张三", NOW));

        assertFalse(v.passed(), "改了时间戳还能过，说明时间戳没进签名");
    }

    @Test
    @DisplayName("两分钟前的请求还能用，五分钟前的就不行")
    void timeWindowShouldBeEnforced() {
        // 2 分钟前：窗口内，正常（网络慢、重试都会造成这个情况，太严会误伤）
        assertTrue(verifier.verify("张三", String.valueOf(NOW - 120_000), sign("张三", NOW - 120_000)).passed(),
                "2 分钟内的请求不该被拒，否则一重试就失败");

        // 10 分钟前：超出 300 秒窗口，按重放处理
        var stale = verifier.verify("张三", String.valueOf(NOW - 600_000), sign("张三", NOW - 600_000));
        assertFalse(stale.passed());
        assertTrue(stale.reason().contains("已过期"), "实际原因：" + stale.reason());
    }

    @Test
    @DisplayName("时间戳在很远的未来也要拒（时钟被改或者伪造）")
    void farFutureTimestampShouldFail() {
        var v = verifier.verify("张三", String.valueOf(NOW + 600_000), sign("张三", NOW + 600_000));

        assertFalse(v.passed());
        assertTrue(v.reason().contains("已过期"), "实际原因：" + v.reason());
    }

    @Test
    @DisplayName("时间戳带前导零也算同一个数，不该因为格式差异全线失败")
    void leadingZerosInTimestampShouldStillVerify() {
        var v = verifier.verify("张三", "0" + NOW, sign("张三", NOW));

        assertTrue(v.passed(), "两端只是写法不同，不该判为伪造，实际原因：" + v.reason());
    }

    @Test
    @DisplayName("签名用大写十六进制也认（不同语言默认大小写不一样）")
    void uppercaseHexShouldPass() {
        String upper = sign("张三", NOW).toUpperCase();
        var v = verifier.verify("张三", String.valueOf(NOW), upper);

        assertTrue(v.passed(), "实际原因：" + v.reason());
    }

    @Test
    @DisplayName("缺签名、缺时间戳、签名不是十六进制：都拒")
    void malformedHeadersShouldFail() {
        assertFalse(verifier.verify("张三", String.valueOf(NOW), null).passed());
        assertFalse(verifier.verify("张三", String.valueOf(NOW), "   ").passed());
        assertFalse(verifier.verify("张三", null, sign("张三", NOW)).passed());
        assertFalse(verifier.verify("张三", "abc", sign("张三", NOW)).passed());
        assertFalse(verifier.verify("张三", String.valueOf(NOW), "not-hex-!!").passed());
    }

    @Test
    @DisplayName("缺申请人直接拒，不能当成空字符串去验")
    void missingApplicantShouldFail() {
        assertFalse(verifier.verify(null, String.valueOf(NOW), sign("", NOW)).passed());
        assertFalse(verifier.verify("   ", String.valueOf(NOW), sign("", NOW)).passed());
    }

    @Test
    @DisplayName("姓名里带换行要拒：否则签名串有歧义，两个不同的名字能算出同一个签名")
    void newlineInApplicantShouldBeRejected() {
        var v = verifier.verify("张\n三", String.valueOf(NOW), sign("张\n三", NOW));

        assertFalse(v.passed(), "签名本身是对的，但姓名里有换行必须拒");
        assertTrue(v.reason().contains("非法字符"), "实际原因：" + v.reason());
    }

    @Test
    @DisplayName("中文姓名做 percent-encoding 后能通过（请求头放不了中文）")
    void percentEncodedChineseShouldPass() {
        // 张三 → %E5%BC%A0%E4%B8%89；签名算的是编码前的原名
        var v = verifier.verify("%E5%BC%A0%E4%B8%89", String.valueOf(NOW), sign("张三", NOW));

        assertTrue(v.passed(), "实际原因：" + v.reason());
        assertEquals("张三", v.applicant(), "解出来的应该还是原名");
    }

    @Test
    @DisplayName("编码大小写不同不影响：%E5 和 %e5 是同一个字")
    void percentEncodingCaseShouldNotMatter() {
        var v = verifier.verify("%e5%bc%a0%e4%b8%89", String.valueOf(NOW), sign("张三", NOW));

        assertTrue(v.passed(), "同一个字的两种写法不该被判成伪造，实际原因：" + v.reason());
    }

    @Test
    @DisplayName("编码本身非法（截断、非十六进制）：拒，并提示怎么编")
    void malformedPercentEncodingShouldFail() {
        var truncated = verifier.verify("%E5%BC", String.valueOf(NOW), sign("张三", NOW));
        assertFalse(truncated.passed());
        assertTrue(truncated.reason().contains("percent-encoding"), "实际原因：" + truncated.reason());

        var notHex = verifier.verify("%ZZ", String.valueOf(NOW), sign("张三", NOW));
        assertFalse(notHex.passed());
    }

    @Test
    @DisplayName("服务端没配密钥时，一律不通过（不存在“空密钥放行”这条路）")
    void blankSecretShouldNeverPass() {
        props.setSecret("");
        // 用空密钥算的签名，谁都能算出来，必须拒
        String sig = HexFormat.of().formatHex("whatever".getBytes());
        var v = verifier.verify("张三", String.valueOf(NOW), sig);

        assertFalse(v.passed());
        assertTrue(v.reason().contains("未配置共享密钥"), "实际原因：" + v.reason());
    }

    @Test
    @DisplayName("开着验签却没配密钥：启动就失败，不许带病运行")
    void enabledWithoutSecretShouldFailFast() {
        AuthProperties bad = new AuthProperties();
        bad.setEnabled(true);
        bad.setSecret("");

        IllegalStateException e = assertThrows(IllegalStateException.class, bad::check);
        assertTrue(e.getMessage().contains("secret"), "报错要指明缺的是什么，实际：" + e.getMessage());
    }

    @Test
    @DisplayName("关掉验签时启动不报错（本机演示模式）")
    void disabledShouldNotFailFast() {
        AuthProperties off = new AuthProperties();
        off.setEnabled(false);
        off.setSecret("");

        off.check();   // 不抛就算过；关闭时只会打 WARN
        assertFalse(off.isEnabled());
    }
}
