package com.moxa.monitor.common;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/** 摘要工具: 告警 ID 生成沿用迁移前的 md5(device_id + ":" + rule_name) 前 16 位 */
public final class HashUtil {

    private static final char[] HEX = "0123456789abcdef".toCharArray();

    private HashUtil() {
    }

    /** 等价于 Python hashlib.md5(raw.encode()).hexdigest() */
    public static String md5Hex(String raw) {
        byte[] digest = digest("MD5", raw);
        return toHex(digest);
    }

    /** 迁移前的 alert_id 取十六进制前 16 位 */
    public static String alertId(String deviceId, String ruleName) {
        return md5Hex(deviceId + ":" + ruleName).substring(0, 16);
    }

    private static byte[] digest(String algorithm, String raw) {
        try {
            MessageDigest md = MessageDigest.getInstance(algorithm);
            return md.digest(raw.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(algorithm + " 不可用", ex);
        }
    }

    private static String toHex(byte[] bytes) {
        char[] out = new char[bytes.length * 2];
        for (int i = 0; i < bytes.length; i++) {
            int v = bytes[i] & 0xFF;
            out[i * 2] = HEX[v >>> 4];
            out[i * 2 + 1] = HEX[v & 0x0F];
        }
        return new String(out);
    }
}
