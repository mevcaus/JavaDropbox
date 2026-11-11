package com.javadropbox.javadropbox.service;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Passes a stream on unchanged while computing the SHA-256 of what was read, so an upload is hashed
 * as it is written rather than read again afterwards.
 */
final class HashingInputStream extends FilterInputStream {

  private final MessageDigest digest;
  private long count;

  HashingInputStream(InputStream in) {
    super(in);
    try {
      digest = MessageDigest.getInstance("SHA-256");
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("Every Java runtime has SHA-256", e);
    }
  }

  @Override
  public int read() throws IOException {
    int b = super.read();
    if (b >= 0) {
      digest.update((byte) b);
      count++;
    }
    return b;
  }

  @Override
  public int read(byte[] buffer, int offset, int length) throws IOException {
    int n = super.read(buffer, offset, length);
    if (n > 0) {
      digest.update(buffer, offset, n);
      count += n;
    }
    return n;
  }

  // Skipped bytes would be missing from the hash.
  @Override
  public long skip(long n) {
    throw new UnsupportedOperationException("A hashed stream is read, not skipped");
  }

  // Rewinding would hash the same bytes twice.
  @Override
  public boolean markSupported() {
    return false;
  }

  @Override
  public synchronized void mark(int readLimit) {
    // Not supported: see markSupported.
  }

  @Override
  public synchronized void reset() throws IOException {
    throw new IOException("A hashed stream can't be rewound");
  }

  /** How many bytes were read. */
  long count() {
    return count;
  }

  /** The SHA-256 of everything read, as 64 lowercase hex digits. Call once, at the end. */
  String hex() {
    return HexFormat.of().formatHex(digest.digest());
  }
}
