package com.precheck.auth;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Clock;
import java.util.HexFormat;

/**
 * 验证「这个请求确实是 OA 发来的、申请人确实是这个人」。
 *
 * <p><b>算法（与 OA 侧约定，必须完全一致）：</b>
 * <pre>
 *   请求头 X-Precheck-Applicant = percent-encoding 后的姓名（张三 → %E5%BC%A0%E4%B8%89）
 *   请求头 X-Precheck-Timestamp = UNIX 毫秒
 *   请求头 X-Precheck-Signature = HMAC-SHA256(secret, timestamp + "\n" + 原始姓名) 的十六进制小写
 * </pre>
 *
 * <p><b>为什么姓名要 percent-encoding。</b>
 * HTTP 头按规范是 ISO-8859-1，直接塞中文，服务端拿到的是 "å¼ åä¸" 这种乱码，
 * 两边算的签名永远对不上。这是接 OA 时第一个会撞上的坑，所以约定写死在这里。
 * 注意签名算的是<b>解码后的原始姓名</b>——这样不管客户端把同一个字编成大写 %E5 还是小写 %e5，
 * 签名都一样。
 *
 * <p><b>为什么用换行分隔，而不是直接拼。</b>
 * 直接拼会产生歧义：applicant="张三1" + timestamp="76" 和 applicant="张三" + timestamp="176"
 * 拼出同一个串，两套不同的参数得到同一个签名。加上不可能出现在姓名里的换行符就隔断了这种可能
 * （姓名里真的出现换行会被直接拒掉，见 {@link #verify}）。
 *
 * <p><b>为什么时间戳要先转成 long 再拼。</b>
 * 否则 "0176240..." 和 "176240..." 会被当成两个不同的串，OA 侧稍不注意就会全线验签失败。
 * 两边都先归一成十进制数字再算，只要数字相同签名就相同。
 *
 * <p><b>为什么比较要用 {@link MessageDigest#isEqual}。</b>
 * 普通的 String.equals 一发现不同就返回，比较耗时会随"猜对了前几位"而变化，
 * 攻击者可以据此一位一位地把签名试出来（时序攻击）。isEqual 无论哪儿不一样都花同样的时间。
 *
 * <p><b>这个方案证明了什么、没证明什么。</b>
 * 它证明：请求来自持有 secret 的一方，且申请人名字在传输中没有被改。
 * 它<b>不</b>证明：具体是哪个员工操作的、有没有权限代提交。那需要 OA 侧负责——
 * 共享密钥只能证明"来自 OA"，证明不了"是张三点的"。
 */
@Component
public class ApplicantVerifier {

    /** 请求头：申请人姓名（与签名内容必须一致）。 */
    public static final String HEADER_APPLICANT = "X-Precheck-Applicant";
    /** 请求头：UNIX 毫秒时间戳。 */
    public static final String HEADER_TIMESTAMP = "X-Precheck-Timestamp";
    /** 请求头：签名值（十六进制）。 */
    public static final String HEADER_SIGNATURE = "X-Precheck-Signature";

    private static final String ALGORITHM = "HmacSHA256";

    private final AuthProperties props;
    private final Clock clock;

    /**
     * 生产用的构造器。
     *
     * <p>为什么要显式标 {@code @Autowired}：这个类还有一个给单测用的构造器（多传一个 Clock）。
     * Spring 一旦发现有两个以上构造器，就不会自动挑，而是要求其中有一个被标记过——
     * 不标的话启动时会报 "No default constructor found"，这个报错信息跟真实原因差了十万八千里，
     * 值得在这里留一句。
     */
    @Autowired
    public ApplicantVerifier(AuthProperties props) {
        this(props, Clock.systemUTC());
    }

    /** 单元测试用这个构造，把"现在几点"钉住，否则时间窗相关的用例会飘。 */
    ApplicantVerifier(AuthProperties props, Clock clock) {
        this.props = props;
        this.clock = clock;
    }

    /**
     * 验签结果。
     *
     * @param passed   通过与否
     * @param applicant 通过时是可信的申请人；不通过时为 null
     * @param reason   不通过的原因（会写进日志，也可能返回给调用方），通过时为 null
     */
    public record Verdict(boolean passed, String applicant, String reason) {

        static Verdict pass(String applicant) {
            return new Verdict(true, applicant, null);
        }

        static Verdict fail(String reason) {
            return new Verdict(false, null, reason);
        }
    }

    /**
     * 校验请求头里的三个值。
     *
     * <p>这个方法是纯函数（除了读时钟），不碰 request/response，方便单测。
     */
    public Verdict verify(String applicant, String timestamp, String signature) {
        String secret = props.getSecret();
        if (secret == null || secret.isBlank()) {
            // 正常情况下走不到这里（配置类启动就拦了），但万一有人绕过配置直接 new，这里也不能放行。
            return Verdict.fail("服务端未配置共享密钥，无法验签");
        }

        String rawName = (applicant == null) ? "" : applicant.trim();

        // ⚠️ 中文名字必须 percent-encoding 之后再放进请求头。
        // HTTP 头按规范是 ISO-8859-1，"张三" 的 UTF-8 字节到了服务端会被解成 "å¼ åä¸"，
        // 拿这个乱码去算签名，两边永远对不上。约定成"头部传编码值、签名算解码后的原名"，
        // 这样名字里是中文、空格还是别的字符都不影响，任何语言一行函数就能生成。
        String name;
        try {
            name = URLDecoder.decode(rawName, StandardCharsets.UTF_8).trim();
        } catch (IllegalArgumentException e) {
            return Verdict.fail("申请人姓名编码非法（请使用 percent-encoding，如 张三 → %E5%BC%A0%E4%B8%89）");
        }

        if (name.isEmpty()) {
            return Verdict.fail("缺少申请人（" + HEADER_APPLICANT + "）");
        }
        // 姓名里出现换行会让签名串产生歧义，直接拒绝比"猜他想表达什么"安全
        if (name.indexOf('\n') >= 0 || name.indexOf('\r') >= 0) {
            return Verdict.fail("申请人姓名包含非法字符");
        }
        if (name.length() > 64) {
            return Verdict.fail("申请人姓名过长");
        }

        long ts;
        try {
            ts = Long.parseLong((timestamp == null) ? "" : timestamp.trim());
        } catch (NumberFormatException e) {
            return Verdict.fail("缺少或非法的 " + HEADER_TIMESTAMP + "（应为 UNIX 毫秒）");
        }

        long now = clock.millis();
        long toleranceMillis = Math.max(0, props.getToleranceSeconds()) * 1000L;
        if (Math.abs(now - ts) > toleranceMillis) {
            long diffSeconds = Math.abs(now - ts) / 1000L;
            return Verdict.fail("请求已过期（时间差 " + diffSeconds + " 秒，允许 "
                    + props.getToleranceSeconds() + " 秒）—— 请检查两台机器的时钟");
        }

        if (signature == null || signature.isBlank()) {
            return Verdict.fail("缺少签名（" + HEADER_SIGNATURE + "）");
        }

        byte[] expected = sign(secret, ts, name);
        byte[] actual;
        try {
            actual = HexFormat.of().parseHex(signature.trim());
        } catch (IllegalArgumentException e) {
            return Verdict.fail("签名格式非法（应为十六进制）");
        }

        if (!MessageDigest.isEqual(expected, actual)) {
            return Verdict.fail("签名不符 —— 申请人姓名或时间戳被改过，或者双方密钥不一致"
                    + "（注意：请求头里的姓名需要 percent-encoding）");
        }
        return Verdict.pass(name);
    }

    /**
     * 按约定算签名。OA 侧要用同样的算法，所以这个方法公开出来，
     * 单测里也用它来生成"正确签名"。
     *
     * @param ts 归一化后的毫秒时间戳（不是原始的字符串）
     */
    public byte[] sign(String secret, long ts, String applicant) {
        String payload = ts + "\n" + applicant;
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), ALGORITHM));
            return mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException e) {
            // 算法是 JDK 标配，走到这里说明运行环境异常，不该静默降级
            throw new IllegalStateException("无法计算 HMAC-SHA256", e);
        }
    }

    /** 给"生成签名"的场景（测试、参考实现）用的十六进制小写形式。 */
    public String signHex(String secret, long ts, String applicant) {
        return HexFormat.of().formatHex(sign(secret, ts, applicant));
    }
}
