package com.precheck.ledger;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 台账配置（precheck.ledger.*）。
 *
 * <p>默认落在启动目录下的 data/invoice-ledger.json —— 不放进 jar 里，
 * 因为它是运行期数据，会一直长。
 */
@Component
@ConfigurationProperties(prefix = "precheck.ledger")
public class LedgerProperties {

    /** 台账文件路径。相对路径按进程启动目录解析。 */
    private String file = "data/invoice-ledger.json";

    public String getFile() {
        return file;
    }

    public void setFile(String file) {
        this.file = file;
    }
}
