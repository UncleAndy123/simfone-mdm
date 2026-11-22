package com.simfone.mdm.security;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** Cross-checks the response format with tools/simfone_admin.py (same test vector). */
public class OfflineCodeTest {
    private static byte[] hmac(byte[] key, String msg) throws Exception {
        Mac m = Mac.getInstance("HmacSHA256");
        m.init(new SecretKeySpec(key, "HmacSHA256"));
        return m.doFinal(msg.getBytes("UTF-8"));
    }

    @Test
    public void responseMatchesAdminTool() throws Exception {
        // master = 32 x 0x01, deviceId = "TEST-001" -> K_dev; challenge 123456, action release
        byte[] master = new byte[32];
        java.util.Arrays.fill(master, (byte) 1);
        byte[] kdev = hmac(master, "TEST-001");
        String r = OfflineCode.formatResponse(hmac(kdev, "123456|release"));
        assertEquals(10, r.length());
        assertEquals("5825123300", r);  // = python3 tools/simfone_admin.py selftest
        System.out.println("TEST-VECTOR " + r);
    }
}
