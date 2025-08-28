package com.fluxbeam.app.security;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;

public class WebhookSignatureVerifier {

  public static boolean verify(byte[] body, String header, String secret) {
    if (header == null || !header.startsWith("sha256=")) {
      return false;
    }
    String expected = header.substring(7);
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
      byte[] digest = mac.doFinal(body);
      StringBuilder sb = new StringBuilder();
      for (byte b : digest) {
        sb.append(String.format("%02x", b));
      }
      return slowEquals(sb.toString(), expected);
    } catch (Exception e) {
      return false;
    }
  }

  private static boolean slowEquals(String a, String b) {
    if (a.length() != b.length()) {
      return false;
    }
    int result = 0;
    for (int i = 0; i < a.length(); i++) {
      result |= a.charAt(i) ^ b.charAt(i);
    }
    return result == 0;
  }
}
