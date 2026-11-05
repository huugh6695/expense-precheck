package com.precheck.service;

import com.precheck.facts.Facts;
import com.precheck.parse.InvoiceParseResult;
import com.precheck.parse.InvoiceParser;
import com.precheck.rule.Finding;
import com.precheck.rule.PrecheckEngine;
import com.precheck.rule.PrecheckReport;
import com.precheck.web.PrecheckRequest;
import com.precheck.web.PrecheckResponse;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import tools.jackson.databind.json.JsonMapper;

import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 把"页面提交的东西"变成"一次预检"。
 *
 * <p>整条链路：上传文件 → 临时落地 → 解析成发票事实 → 套上手工修正 →
 * 组装 Facts → 跑规则 → 包装成页面好渲染的结果。
 */
@Service
public class PrecheckService {

    private final InvoiceParser invoiceParser;
    private final PrecheckEngine engine;
    private final JsonMapper jsonMapper = JsonMapper.builder().build();

    public PrecheckService(InvoiceParser invoiceParser, PrecheckEngine engine) {
        this.invoiceParser = invoiceParser;
        this.engine = engine;
    }

    public PrecheckResponse precheck(PrecheckRequest req, List<MultipartFile> files) {
        List<String> parseWarnings = new ArrayList<>();
        List<PrecheckResponse.AttachmentView> views = new ArrayList<>();
        List<Facts.Attachment> attachments = new ArrayList<>();

        int index = 1;
        for (MultipartFile file : files) {
            if (file == null || file.isEmpty()) {
                continue;
            }
            String fileName = file.getOriginalFilename() == null ? "未命名文件" : file.getOriginalFilename();

            if (!fileName.toLowerCase().endsWith(".pdf")) {
                parseWarnings.add(fileName + "：只支持 PDF，已跳过");
                continue;
            }

            Path temp = null;
            try {
                temp = Files.createTempFile("invoice-", ".pdf");
                try (InputStream in = file.getInputStream()) {
                    Files.copy(in, temp, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                }

                InvoiceParseResult parsed = invoiceParser.parse(temp, "a" + index++);
                Facts.Attachment attachment = withOriginalName(parsed.attachment(), fileName);
                attachment = applyOverride(attachment, req, fileName);

                parsed.warnings().forEach(w -> parseWarnings.add(fileName + "：" + w));
                attachments.add(attachment);
                views.add(new PrecheckResponse.AttachmentView(
                        attachment.fileName(), attachment.kind(), attachment.invoiceNo(), attachment.amount(),
                        attachment.date(), attachment.seller(),
                        parsed.warnings().stream().map(w -> fileName + "：" + w).toList()));
            } catch (Exception e) {
                parseWarnings.add(fileName + "：读取失败（" + e.getMessage() + "），请手工填写金额");
            } finally {
                if (temp != null) {
                    try {
                        Files.deleteIfExists(temp);
                    } catch (Exception ignored) {
                        // 临时文件删不掉不影响结果，不打扰用户
                    }
                }
            }
        }

        Facts facts = new Facts(toApplicationInfo(req), attachments,
                req.lineItems().stream()
                        .map(li -> new Facts.LineItem(li.desc(), li.amount()))
                        .toList());

        return toResponse(engine.run(facts), views, parseWarnings);
    }

    /** 样例数据：不用上传任何文件，点一下就能看到完整效果。演示时的救命按钮。 */
    public PrecheckResponse sample() {
        try (InputStream in = getClass().getResourceAsStream("/samples/sample-reimburse.json")) {
            if (in == null) {
                throw new IllegalStateException("样例数据缺失：/samples/sample-reimburse.json");
            }
            Facts facts = jsonMapper.readValue(in, Facts.class);
            List<PrecheckResponse.AttachmentView> views = facts.attachments().stream()
                    .map(a -> new PrecheckResponse.AttachmentView(
                            a.fileName(), a.kind(), a.invoiceNo(), a.amount(), a.date(), a.seller(), List.of()))
                    .toList();
            return toResponse(engine.run(facts), views, List.of());
        } catch (Exception e) {
            throw new IllegalStateException("读取样例数据失败：" + e.getMessage(), e);
        }
    }

    private Facts.ApplicationInfo toApplicationInfo(PrecheckRequest req) {
        return new Facts.ApplicationInfo(req.type(), req.applicant(), req.declaredAmount(),
                req.reason(), req.startDate(), req.endDate(), req.category());
    }

    /** 解析器只知道临时文件的随机名，这里换回用户上传时的原始文件名，报告里才能指认。 */
    private Facts.Attachment withOriginalName(Facts.Attachment a, String fileName) {
        return a.withFileName(fileName);
    }

    private Facts.Attachment applyOverride(Facts.Attachment a, PrecheckRequest req, String fileName) {
        BigDecimal manual = req.overrides().stream()
                .filter(o -> o.fileName() != null && o.fileName().equals(fileName))
                .map(PrecheckRequest.FileOverride::amount)
                .filter(v -> v != null)
                .findFirst()
                .orElse(null);
        return (manual == null) ? a : a.withAmount(manual);
    }

    private PrecheckResponse toResponse(PrecheckReport report,
                                        List<PrecheckResponse.AttachmentView> views,
                                        List<String> parseWarnings) {
        List<PrecheckResponse.FindingView> findings = new ArrayList<>();
        for (Finding f : report.findings()) {
            findings.add(PrecheckResponse.FindingView.of(f));
        }
        return new PrecheckResponse(
                report.passed(), report.blockCount(), report.warnCount(), report.infoCount(),
                buildSummary(report), findings, views, parseWarnings);
    }

    private String buildSummary(PrecheckReport report) {
        if (report.blockCount() == 0 && report.warnCount() == 0) {
            return "材料齐全，金额也能对上，可以提交审批。";
        }
        if (report.blockCount() == 0) {
            return "没有必须补的材料，但有 " + report.warnCount() + " 条建议核对，确认无误即可提交。";
        }
        return "发现 " + report.blockCount() + " 个必须补的问题"
                + (report.warnCount() > 0 ? "、" + report.warnCount() + " 条建议核对" : "")
                + "。补齐后再提交，可以少跑一趟。";
    }
}
