package com.precheck.ledger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * 台账的落地实现：一个 JSON 文件。
 *
 * <p>为什么不是数据库：这套东西是"装一个 jar 就能跑"的形态，为了存几百条发票号
 * 去装一个 MySQL，会把部署成本抬高一个数量级。文件足够，而且台账本来就是可替换的一层——
 * 将来量大了、要多台机器共享了，换成 MySQL 只要另写一个 {@link InvoiceHistory} 实现。
 *
 * <p>几个刻意的处理：
 * <ul>
 *   <li><b>读失败不抛异常</b>：台账坏了也不能让预检崩，这一轮按"没有历史"处理并写日志。
 *       宁可漏报一次重复，也不能让员工连材料都交不上去。</li>
 *   <li><b>先写临时文件再原子替换</b>：写到一半断电，不会留下半个 JSON 把台账彻底读废。</li>
 *   <li><b>方法加 synchronized</b>：同一进程里两个员工同时提交时，不会互相覆盖。</li>
 * </ul>
 */
@Component
public class FileInvoiceHistory implements InvoiceHistory {

    private static final Logger log = LoggerFactory.getLogger(FileInvoiceHistory.class);

    private final Path file;
    private final JsonMapper json = JsonMapper.builder().build();

    public FileInvoiceHistory(LedgerProperties props) {
        this.file = Path.of(props.getFile()).toAbsolutePath();
    }

    @Override
    public synchronized Map<String, Entry> lookup(Collection<String> invoiceNos) {
        if (invoiceNos == null || invoiceNos.isEmpty()) {
            return Map.of();
        }
        // 只读一次文件，按哈希建索引，再回填每个号码
        Map<String, Entry> byHash = new HashMap<>();
        for (Entry e : load().entries()) {
            byHash.put(e.hash(), e);
        }

        Map<String, Entry> hit = new HashMap<>();
        for (String no : new LinkedHashSet<>(invoiceNos)) {
            if (no == null || no.isBlank()) {
                continue;
            }
            Entry e = byHash.get(InvoiceHash.of(no));
            if (e != null) {
                hit.put(no, e);
            }
        }
        return hit;
    }

    @Override
    public synchronized boolean record(String invoiceNo, String applicant,
                                       BigDecimal amount, LocalDate invoiceDate) {
        if (invoiceNo == null || invoiceNo.isBlank()) {
            return false;
        }
        String hash = InvoiceHash.of(invoiceNo);

        List<Entry> entries = new ArrayList<>(load().entries());
        if (entries.stream().anyMatch(e -> hash.equals(e.hash()))) {
            return false;   // 幂等：重复点"确认提交"不会写出第二条
        }

        entries.add(new Entry(hash, InvoiceHash.mask(invoiceNo), applicant, amount,
                invoiceDate, LocalDate.now()));
        save(entries);
        return true;
    }

    /** 台账文件的实际位置，返回给页面显示，方便运维找到它。 */
    public Path location() {
        return file;
    }

    private LedgerFile load() {
        if (!Files.exists(file)) {
            return new LedgerFile(List.of());
        }
        try (InputStream in = Files.newInputStream(file)) {
            LedgerFile data = json.readValue(in, LedgerFile.class);
            return (data == null) ? new LedgerFile(List.of()) : data;
        } catch (Exception e) {
            // 台账坏了不能让整次预检崩掉，降级成"这一轮没有历史"
            log.warn("台账读取失败，本次按空台账处理（{}）：{}", file, e.getMessage());
            return new LedgerFile(List.of());
        }
    }

    private void save(List<Entry> entries) {
        try {
            Path dir = file.getParent();
            Files.createDirectories(dir);
            Path temp = Files.createTempFile(dir, "ledger-", ".tmp");
            Files.writeString(temp, json.writeValueAsString(new LedgerFile(entries)), StandardCharsets.UTF_8);
            try {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                // 有些文件系统（尤其网络盘）不支持原子移动，退回普通覆盖
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (Exception e) {
            throw new IllegalStateException("写入台账失败：" + e.getMessage(), e);
        }
    }

    /**
     * 台账文件的最外层结构。
     *
     * <p>包一层对象而不是裸数组：将来要加"版本号""最后清理时间"这些字段，
     * 不用改文件格式，老文件也还能读。
     */
    public record LedgerFile(List<Entry> entries) {
        public LedgerFile {
            entries = (entries == null) ? List.of() : List.copyOf(entries);
        }
    }
}
