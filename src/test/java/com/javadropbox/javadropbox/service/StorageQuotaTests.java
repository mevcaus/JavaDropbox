package com.javadropbox.javadropbox.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Storage quota settings")
class StorageQuotaTests {

  // It used to cap the whole serving directory. Ignoring it would leave a server without the cap
  // its owner set, so the app says what to do instead.
  @Test
  @DisplayName("the removed server-wide cap stops the app from starting, and says what replaced it")
  void removedCapIsRefused() {
    assertThatThrownBy(() -> new StorageQuota(null, null, null, "50MB"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining(StorageQuota.REMOVED_CAP)
        .hasMessageContaining("quota");
  }
}
