package com.precheck.ledger;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;

/**
 * 发票号 → 哈希 / 脱敏号。
 *
 * <p>单独拎出来是因为它是台账的隐私边界：<b>哪些东西可以落盘、以什么形式落盘</b>，
 * 全部集中在这一个类里，将来要改（比如换成带随机盐、或换成不可逆的口令散列）只改这里。
 */
public final class InvoiceHash {

    /**
     * 固定前缀。
     *
     * <p>它不是真正的"盐"（真正的盐应当每个库随机、跟哈希一起存），作用只是让
     * 这个哈希不能拿去和别处现成的发票号哈希表对撞。想更强的防护就该换成随机盐，
     * 但那会让"同一个号码永远算出同一个哈希"这件事失效，得配合额外的索引字段——先留个说明在这。
     */
    private static final String PREFIX = "precheck-ledger-v1:";

    private InvoiceHash() {
    }

    /** 发票号 → 十六进制 SHA-256。空值返回空串，调用方自己判断。 */
    public static String of(String invoiceNo) {
        if (invoiceNo == null || invoiceNo.isBlank()) {
            return "";
        }
        String normalized = normalize(invoiceNo);
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest((PREFIX + normalized).getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            // JDK 一定有 SHA-256，这里只是让编译器和读者都清楚它不会静默降级
            throw new IllegalStateException("当前 JDK 不支持 SHA-256", e);
        }
    }

    /**
     * 只留后四位，例如 "…2231"。
     *
     * <p>台账里不能存明文，但报告里必须让员工能对上"是不是我手里这张"，
     * 所以留四位——够核对，不足以还原。
     */
    public static String mask(String invoiceNo) {
        if (invoiceNo == null || invoiceNo.isBlank()) {
            return "****";
        }
        String n = normalize(invoiceNo);
        if (n.length() <= 4) {
            return "****";
        }
        return "…" + n.substring(n.length() - 4);
    }

    /**
     * 归一：去掉空格、横杠之类噪声，统一大写。
     *
     * <p>为什么必须归一：同一个号码在 OCR 出来可能带空格（"2694 2000…"），
     * 不归一就会算成两个不同的哈希，"重复报销"就查不出来了。
     */
    private static String normalize(String invoiceNo) {
        return invoiceNo.replaceAll("[^0-9A-Za-z]", "").toUpperCase(Locale.ROOT);
    }
}
