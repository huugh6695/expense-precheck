package com.precheck.web;

/**
 * 记入台账的结果。
 *
 * @param recorded 新记入几张
 * @param skipped  已经在台账里、跳过的张数（幂等）
 * @param ignored  没有发票号、没法记的张数（解析失败或手工填的附件）
 * @param location 台账文件位置，方便运维找到
 */
public record LedgerCommitResponse(int recorded, int skipped, int ignored, String location) {
}
