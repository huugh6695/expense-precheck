package com.precheck.web;

import com.precheck.service.PrecheckService;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/**
 * 对外只有两个接口，够用了。
 *
 * <p>POST /api/precheck —— 提交申请单 + 附件，返回补正清单
 * <p>GET  /api/precheck/sample —— 返回内置样例的结果，用于"一键体验"（演示时不怕解析翻车）
 *
 * <p>注意 payload 走 @RequestPart（JSON 分片）而不是 @RequestPart 之外的字段拼装：
 * 申请单是个嵌套结构（有明细数组），拼成 form 字段会很乱。
 */
@RestController
@RequestMapping("/api")
public class PrecheckController {

    private final PrecheckService service;

    public PrecheckController(PrecheckService service) {
        this.service = service;
    }

    @PostMapping(value = "/precheck", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public PrecheckResponse precheck(
            @RequestPart("payload") PrecheckRequest payload,
            @RequestPart(value = "files", required = false) List<MultipartFile> files
    ) {
        return service.precheck(payload, files == null ? List.of() : files);
    }

    @GetMapping("/precheck/sample")
    public PrecheckResponse sample() {
        return service.sample();
    }
}
