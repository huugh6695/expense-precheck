package com.precheck.ledger;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 台账的测试。
 *
 * <p>用 {@link TempDir} 而不是内存假实现，是因为这里真正要验证的恰恰是
 * <b>"写了能不能读回来"</b>——文件格式、原子替换、幂等这些，假实现测不出来。
 */
class FileInvoiceHistoryTest {

    private static final String NO = "26942000000996000001";

    private FileInvoiceHistory historyIn(Path dir) {
        LedgerProperties props = new LedgerProperties();
        props.setFile(dir.resolve("data/invoice-ledger.json").toString());
        return new FileInvoiceHistory(props);
    }

    @Test
    @DisplayName("记一笔就能查回来，台账里存的是脱敏号不是明文")
    void recordThenLookup(@TempDir Path dir) throws IOException {
        FileInvoiceHistory history = historyIn(dir);

        assertTrue(history.record(NO, "张三", new BigDecimal("149.00"), LocalDate.of(2026, 9, 5)));

        Map<String, InvoiceHistory.Entry> hit = history.lookup(List.of(NO));
        assertEquals(1, hit.size());
        assertEquals("张三", hit.get(NO).applicant());
        assertEquals("…0001", hit.get(NO).maskedNo(), "台账里只能留后四位");

        String raw = Files.readString(history.location(), StandardCharsets.UTF_8);
        assertFalse(raw.contains(NO), "明文发票号绝对不能落盘，实际文件：" + raw);
        assertTrue(raw.contains(InvoiceHash.of(NO)), "应该存的是哈希");
    }

    @Test
    @DisplayName("同一张票记两次：第二次返回 false，文件里只有一条")
    void recordIsIdempotent(@TempDir Path dir) throws IOException {
        FileInvoiceHistory history = historyIn(dir);

        assertTrue(history.record(NO, "张三", new BigDecimal("149.00"), LocalDate.of(2026, 9, 5)));
        assertFalse(history.record(NO, "张三", new BigDecimal("149.00"), LocalDate.of(2026, 9, 5)),
                "重复点「确认提交」不该写出第二条");

        String raw = Files.readString(history.location(), StandardCharsets.UTF_8);
        int first = raw.indexOf(InvoiceHash.of(NO));
        assertEquals(first, raw.lastIndexOf(InvoiceHash.of(NO)), "文件里只该出现一次这个哈希");
    }

    @Test
    @DisplayName("台账文件不存在：查出来是空的，不报错")
    void missingFileIsEmpty(@TempDir Path dir) {
        assertTrue(historyIn(dir).lookup(List.of(NO)).isEmpty());
    }

    @Test
    @DisplayName("号码带空格也要认出是同一张票")
    void normalizeSpaces(@TempDir Path dir) {
        FileInvoiceHistory history = historyIn(dir);
        history.record(NO, "张三", new BigDecimal("149.00"), LocalDate.of(2026, 9, 5));

        // PDF 抽出来的号码中间可能被塞进空格，不归一就会算成两个不同的票
        String withSpaces = "2694 2000 0009 9600 0001";
        assertEquals(1, history.lookup(List.of(withSpaces)).size(),
                "带空格的号码应命中同一条记录");
    }

    @Test
    @DisplayName("重启后（新实例）还能读到之前记的票")
    void survivesRestart(@TempDir Path dir) {
        historyIn(dir).record(NO, "张三", new BigDecimal("149.00"), LocalDate.of(2026, 9, 5));

        // 换一个实例，相当于重启进程
        assertEquals(1, historyIn(dir).lookup(List.of(NO)).size());
    }
}
