package com.javadropbox.javadropbox.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.javadropbox.javadropbox.TestDocuments;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@DisplayName("Text extraction for search")
class TextExtractorTests {

  @TempDir Path dir;

  private final TextExtractor extractor = new TextExtractor("50MB");

  @Test
  @DisplayName("reads text and source files as UTF-8, replacing what does not decode")
  void readsTextFiles() throws IOException {
    assertThat(extract("notes.md", "# Café\nmenu".getBytes(StandardCharsets.UTF_8)))
        .contains("# Café\nmenu");
    assertThat(extract("Main.java", "class Main {}".getBytes(StandardCharsets.UTF_8)))
        .contains("class Main {}");
    // "Caf\xe9" in Latin-1 is not UTF-8.
    assertThat(extract("old.txt", new byte[] {'C', 'a', 'f', (byte) 0xe9, ' ', 'o', 'k'}))
        .contains("Caf� ok");
  }

  @Test
  @DisplayName("keeps only the first 200,000 characters")
  void keepsTheStartOfLongText() throws IOException {
    String text = "a".repeat(TextExtractor.MAX_CHARS) + " beyond the limit";

    assertThat(extract("long.log", text.getBytes(StandardCharsets.UTF_8)))
        .hasValueSatisfying(
            extracted ->
                assertThat(extracted).hasSize(TextExtractor.MAX_CHARS).doesNotContain("beyond"));
  }

  @Test
  @DisplayName("reads the text of every page of a PDF")
  void readsPdfs() throws IOException {
    assertThat(extract("report.PDF", TestDocuments.pdf("First page", "Second page")))
        .hasValueSatisfying(
            text -> assertThat(text).contains("First page").contains("Second page"));
  }

  @Test
  @DisplayName("reads a Word document's paragraphs, tabs and breaks, but not deleted text")
  void readsWordDocuments() throws IOException {
    byte[] docx =
        TestDocuments.docxWithBody(
            "<w:p><w:r><w:t>One</w:t></w:r><w:r><w:tab/><w:t>two</w:t></w:r></w:p>"
                + "<w:p><w:r><w:t>Three</w:t><w:br/><w:t>four</w:t></w:r></w:p>"
                + "<w:p><w:del><w:r><w:delText>removed</w:delText></w:r></w:del></w:p>"
                + "<w:p><w:r><w:instrText>PAGE</w:instrText></w:r></w:p>");

    assertThat(extract("letter.docx", docx)).contains("One\ttwo\nThree\nfour\n\n\n");
  }

  @Test
  @DisplayName("does not resolve entities a Word document defines for itself")
  void ignoresEntities() throws IOException {
    Path secret = Files.writeString(dir.resolve("secret.txt"), "top secret");
    byte[] docx =
        TestDocuments.docxWithDocumentXml(
            "<?xml version=\"1.0\"?><!DOCTYPE d [<!ENTITY leak SYSTEM \""
                + secret.toUri()
                + "\">]><w:document"
                + " xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\">"
                + "<w:body><w:p><w:r><w:t>&leak;</w:t></w:r></w:p></w:body></w:document>");
    Path file = Files.write(dir.resolve("trap.docx"), docx);

    try {
      assertThat(extractor.extract(file, "trap.docx", docx.length))
          .hasValueSatisfying(text -> assertThat(text).doesNotContain("top secret"));
    } catch (IOException refused) {
      // Refusing the document outright is as good.
    }
  }

  @Test
  @DisplayName("finds no text in other kinds of file, nor in files over the size limit")
  void skipsOtherFiles() throws IOException {
    assertThat(extract("photo.png", new byte[] {(byte) 0x89, 'P', 'N', 'G'})).isEmpty();
    assertThat(extract("archive.zip", new byte[] {'P', 'K', 3, 4})).isEmpty();
    assertThat(extract("no-extension", "plain words".getBytes(StandardCharsets.UTF_8))).isEmpty();

    TextExtractor small = new TextExtractor("10B");
    Path file = Files.writeString(dir.resolve("big.txt"), "more than ten bytes");
    assertThat(small.extract(file, "big.txt", Files.size(file))).isEmpty();

    TextExtractor namesOnly = new TextExtractor("0");
    Path tiny = Files.writeString(dir.resolve("tiny.txt"), "x");
    assertThat(namesOnly.extract(tiny, "tiny.txt", Files.size(tiny))).isEmpty();
  }

  @Test
  @DisplayName("fails on a file that is not what its extension says")
  void failsOnDamagedFiles() throws IOException {
    Path pdf = Files.writeString(dir.resolve("fake.pdf"), "not a PDF at all");
    Path docx = Files.writeString(dir.resolve("fake.docx"), "not a zip either");

    assertThatThrownBy(() -> extractor.extract(pdf, "fake.pdf", Files.size(pdf)))
        .isInstanceOf(IOException.class);
    assertThatThrownBy(() -> extractor.extract(docx, "fake.docx", Files.size(docx)))
        .isInstanceOf(IOException.class);
  }

  @Test
  @DisplayName("a negative size limit stops startup")
  void negativeLimitIsRefused() {
    assertThatThrownBy(() -> new TextExtractor("-1MB")).isInstanceOf(IllegalStateException.class);
  }

  private Optional<String> extract(String name, byte[] content) throws IOException {
    Path file = Files.write(dir.resolve(name), content);
    return extractor.extract(file, name, content.length);
  }
}
