package com.precheck.web;

import com.precheck.auth.AuthInterceptor;
import com.precheck.ledger.FileInvoiceHistory;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 台账写入接口。
 *
 * <p>只有一个端点，而且刻意做成"显式调用"——不在预检里顺手写台账。
 * 预检是只读的检查动作，员工可能反复点五次；把检查当提交，台账立刻变成垃圾场，
 * 之后每个人的发票都会被"自己以前报过"拦住。
 */
@RestController
@RequestMapping("/api/ledger")
public class LedgerController {

    private final FileInvoiceHistory history;

    public LedgerController(FileInvoiceHistory history) {
        this.history = history;
    }

    @PostMapping("/commit")
    public LedgerCommitResponse commit(@RequestBody LedgerCommitRequest req, HttpServletRequest request) {

        // 和预检接口同一个道理：验签通过时，台账里记的名字只能来自签名。
        // 台账是"谁哪天报了哪张票"的长期记录，名字一旦可伪造，以后拿去拦别人的记录也就不可信了。
        Object verified = request.getAttribute(AuthInterceptor.VERIFIED_APPLICANT);
        String applicant = (verified instanceof String name) ? name : req.applicant();

        int recorded = 0;
        int skipped = 0;
        int ignored = 0;

        for (LedgerCommitRequest.Item item : req.items()) {
            if (item == null || item.invoiceNo() == null || item.invoiceNo().isBlank()) {
                // 没认出号码的附件记不了，如实计数，别假装成功
                ignored++;
                continue;
            }
            boolean added = history.record(item.invoiceNo(), applicant,
                    item.amount(), item.invoiceDate());
            if (added) {
                recorded++;
            } else {
                skipped++;
            }
        }

        return new LedgerCommitResponse(recorded, skipped, ignored, history.location().toString());
    }
}
