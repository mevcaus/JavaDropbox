package com.javadropbox.javadropbox.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

/**
 * The random tokens in share links and account links. The URL carries the token and the database
 * only its SHA-256 hash, so neither the URL nor the database reveals or rebuilds anything usable.
 */
final class Tokens {

  private static final int TOKEN_BYTES = 32;
  private static final SecureRandom random = new SecureRandom();

  private Tokens() {}

  static String newToken() {
    byte[] bytes = new byte[TOKEN_BYTES];
    random.nextBytes(bytes);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }

  static String hash(String token) {
    try {
      MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
      return HexFormat.of().formatHex(sha256.digest(token.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("Every Java runtime provides SHA-256", e);
    }
  }
}
