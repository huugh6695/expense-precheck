package com.precheck.web;

import com.precheck.auth.AuthProperties;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 让前端问一句"现在验不验签"。
 *
 * <p>为什么需要这个接口：验签关掉的时候，页面上那个"申请人"输入框是可以随便填的，
 * 台账里的名字也就不作数。这件事必须让用户在界面上看得见——
 * 一份看起来正常、实际上记着假名字的台账，比明确报错危险得多。
 *
 * <p>这个接口不需要验签（它不含任何业务数据），所以在 WebConfig 里被排除了。
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthProperties props;

    public AuthController(AuthProperties props) {
        this.props = props;
    }

    @GetMapping("/status")
    public Status status() {
        return new Status(props.isEnabled());
    }

    /**
     * @param enabled true = 已接入 OA 验签，申请人由系统带入；
     *                false = 本机演示模式，申请人由用户在页面上填，记录不可作为追责依据
     */
    public record Status(boolean enabled) {
    }
}
