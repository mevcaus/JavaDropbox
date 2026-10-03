package com.javadropbox.javadropbox.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

@DisplayName("Preview types")
class PreviewTypeTests {

  @ParameterizedTest(name = "{0} previews as {1}, served as {2}")
  @CsvSource({
    "photo.png, IMAGE, image/png",
    "Photo.JPG, IMAGE, image/jpeg",
    "drawing.svg, IMAGE, image/svg+xml",
    "paper.PDF, PDF, application/pdf",
    "notes.txt, TEXT, text/plain;charset=UTF-8",
    "index.html, TEXT, text/plain;charset=UTF-8",
    "data.json, TEXT, text/plain;charset=UTF-8",
    "Main.java, TEXT, text/plain;charset=UTF-8",
    "my.photo.png, IMAGE, image/png",
    "README, TEXT, text/plain;charset=UTF-8",
    "Dockerfile, TEXT, text/plain;charset=UTF-8",
  })
  void previewableNames(String filename, PreviewType type, String contentType) {
    assertThat(PreviewType.of(filename)).contains(type);
    assertThat(PreviewType.contentType(filename)).isEqualTo(contentType);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {"archive.zip", "movie.mp4", "binary", "txt", "png", "photo.png.exe", "trailing."})
  @DisplayName("other files, including bare extensions with no name, are not previewable")
  void notPreviewable(String filename) {
    assertThat(PreviewType.of(filename)).isEmpty();
    assertThatThrownBy(() -> PreviewType.contentType(filename))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  @DisplayName("is written to JSON in lower case")
  void jsonValueIsLowerCase() {
    assertThat(PreviewType.IMAGE.jsonValue()).isEqualTo("image");
    assertThat(PreviewType.PDF.jsonValue()).isEqualTo("pdf");
    assertThat(PreviewType.TEXT.jsonValue()).isEqualTo("text");
  }
}
