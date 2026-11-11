package com.javadropbox.javadropbox.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Hashing a stream as it is read")
class HashingInputStreamTests {

  @Test
  @DisplayName("what passes through is unchanged, counted, and hashed with SHA-256")
  void hashesWhatIsRead() throws IOException {
    byte[] content = "abc".getBytes(StandardCharsets.UTF_8);
    try (HashingInputStream in = new HashingInputStream(new ByteArrayInputStream(content))) {
      assertThat(in.read()).isEqualTo('a');
      byte[] rest = new byte[8];
      assertThat(in.read(rest, 0, 8)).isEqualTo(2);
      assertThat(in.read()).isEqualTo(-1);

      assertThat(in.count()).isEqualTo(3);
      // SHA-256("abc"), from FIPS 180-2.
      assertThat(in.hex())
          .isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
    }
  }

  @Test
  @DisplayName("an empty stream has the SHA-256 of nothing")
  void emptyStream() throws IOException {
    try (HashingInputStream in = new HashingInputStream(new ByteArrayInputStream(new byte[0]))) {
      assertThat(in.read()).isEqualTo(-1);
      assertThat(in.hex())
          .isEqualTo("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855");
    }
  }

  @Test
  @DisplayName("skipping and rewinding are refused, since the hash would miss or repeat bytes")
  void skipIsRefused() throws IOException {
    try (HashingInputStream in = new HashingInputStream(new ByteArrayInputStream(new byte[4]))) {
      assertThatThrownBy(() -> in.skip(2)).isInstanceOf(UnsupportedOperationException.class);
      assertThat(in.markSupported()).isFalse();
      in.mark(10);
      assertThatThrownBy(in::reset).isInstanceOf(IOException.class);
    }
  }
}
