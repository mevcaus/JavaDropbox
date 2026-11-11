package com.javadropbox.javadropbox.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@DisplayName("Where file and version content is")
class FileContentsTests {

  @Test
  @DisplayName("a version's stored copy is below the version store, by its recorded name")
  void versionKey() {
    assertThat(FileContents.versionKey("12/v3")).isEqualTo(".versions/12/v3");
  }

  @ParameterizedTest
  @ValueSource(strings = {"", "../12/v3", "12/../../x", "12//v3", "12/./v3", "/12/v3", "12/v3/"})
  @DisplayName("a recorded name that would lead outside the version store is refused")
  void versionKeyStaysInTheVersionStore(String name) {
    assertThatThrownBy(() -> FileContents.versionKey(name))
        .isInstanceOf(IllegalStateException.class);
  }
}
