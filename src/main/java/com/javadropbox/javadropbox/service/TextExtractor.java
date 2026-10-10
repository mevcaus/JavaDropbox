package com.javadropbox.javadropbox.service;

import com.javadropbox.javadropbox.model.PreviewType;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Optional;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.io.IOUtils;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.unit.DataSize;

/**
 * The text in a file's contents, for the search index. Text and source files (the kinds {@link
 * PreviewType} shows as text), PDFs and Word documents ({@code .docx}) have some; anything else is
 * found by its name only.
 *
 * <p>Only the first {@link #MAX_CHARS} characters are kept, and files larger than {@code
 * javadropbox.search.max-file-size} are not opened at all, so one huge file can neither hold up
 * indexing for long nor fill the heap.
 */
@Component
public class TextExtractor {

  /** The most text kept from one file: about 80 pages. */
  public static final int MAX_CHARS = 200_000;

  // A .docx is a zip, and its text a compressed XML document; reading stops here however much the
  // entry claims to inflate to.
  private static final long MAX_DOCUMENT_XML_BYTES = 100L * 1024 * 1024;

  private static final XMLInputFactory XML = XMLInputFactory.newFactory();

  static {
    // A document's own DTD could point at files on the server or expand without end.
    XML.setProperty(XMLInputFactory.SUPPORT_DTD, false);
    XML.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
  }

  private final long maxFileSize;

  public TextExtractor(@Value("${javadropbox.search.max-file-size:50MB}") String maxFileSize) {
    this.maxFileSize = DataSize.parse(maxFileSize.trim()).toBytes();
    if (this.maxFileSize < 0) {
      throw new IllegalStateException(
          "javadropbox.search.max-file-size must be 0 or more, not " + maxFileSize);
    }
  }

  /**
   * Whether {@link #extract} would read a file called {@code name}, which is {@code size} bytes
   * long: whether it is a kind that has text, and small enough.
   */
  public boolean reads(String name, long size) {
    return size <= maxFileSize && kind(name) != null;
  }

  /**
   * The text of the file at {@code file}, called {@code name}, which is {@code size} bytes long.
   *
   * @return the text, or empty if this kind of file has none or it is too large to read
   * @throws IOException if the file could not be read, or is not what its extension says
   */
  public Optional<String> extract(Path file, String name, long size) throws IOException {
    if (!reads(name, size)) {
      return Optional.empty();
    }
    return Optional.of(
        switch (kind(name)) {
          case TEXT -> text(file);
          case PDF -> pdf(file);
          case DOCX -> docx(file);
        });
  }

  private enum Kind {
    TEXT,
    PDF,
    DOCX
  }

  private static Kind kind(String name) {
    if (PreviewType.of(name).orElse(null) == PreviewType.TEXT) {
      return Kind.TEXT;
    }
    String lower = name.toLowerCase(Locale.ROOT);
    if (lower.endsWith(".pdf")) {
      return Kind.PDF;
    }
    if (lower.endsWith(".docx")) {
      return Kind.DOCX;
    }
    return null;
  }

  // Undecodable bytes become U+FFFD rather than failing the file: a Latin-1 text file still has
  // its ASCII words found.
  private static String text(Path file) throws IOException {
    CharsetDecoder decoder =
        StandardCharsets.UTF_8
            .newDecoder()
            .onMalformedInput(CodingErrorAction.REPLACE)
            .onUnmappableCharacter(CodingErrorAction.REPLACE);
    try (Reader reader = new InputStreamReader(Files.newInputStream(file), decoder)) {
      StringBuilder text = new StringBuilder();
      char[] buffer = new char[8192];
      int read;
      while (text.length() < MAX_CHARS
          && (read = reader.read(buffer, 0, Math.min(buffer.length, MAX_CHARS - text.length())))
              >= 0) {
        text.append(buffer, 0, read);
      }
      return text.toString();
    }
  }

  // A page at a time, so reading stops once there is enough. Streams are cached in temporary files
  // rather than the heap, which a large PDF could otherwise fill.
  private static String pdf(Path file) throws IOException {
    try (PDDocument document =
        Loader.loadPDF(file.toFile(), IOUtils.createTempFileOnlyStreamCache())) {
      PDFTextStripper stripper = new PDFTextStripper();
      StringBuilder text = new StringBuilder();
      for (int page = 1; page <= document.getNumberOfPages() && text.length() < MAX_CHARS; page++) {
        stripper.setStartPage(page);
        stripper.setEndPage(page);
        text.append(stripper.getText(document));
      }
      return truncate(text);
    }
  }

  private static String docx(Path file) throws IOException {
    try (ZipFile zip = new ZipFile(file.toFile())) {
      ZipEntry body = zip.getEntry("word/document.xml");
      if (body == null) {
        throw new IOException("Not a Word document: it has no word/document.xml");
      }
      try (InputStream xml = limited(zip.getInputStream(body), MAX_DOCUMENT_XML_BYTES)) {
        return wordText(xml);
      }
    }
  }

  /**
   * The text of a WordprocessingML body: what its {@code <w:t>} runs hold, with paragraphs on lines
   * of their own. Matched by local name, so Strict OOXML's other namespace reads the same. Deleted
   * text and field codes are in elements of their own ({@code delText}, {@code instrText}) and left
   * out.
   */
  static String wordText(InputStream xml) throws IOException {
    StringBuilder text = new StringBuilder();
    XMLStreamReader reader = null;
    try {
      reader = XML.createXMLStreamReader(xml);
      boolean inText = false;
      while (reader.hasNext() && text.length() < MAX_CHARS) {
        switch (reader.next()) {
          case XMLStreamConstants.START_ELEMENT -> {
            switch (reader.getLocalName()) {
              case "t" -> inText = true;
              case "tab" -> text.append('\t');
              case "br", "cr" -> text.append('\n');
              default -> {}
            }
          }
          case XMLStreamConstants.CHARACTERS, XMLStreamConstants.CDATA -> {
            if (inText) {
              text.append(reader.getText());
            }
          }
          case XMLStreamConstants.END_ELEMENT -> {
            switch (reader.getLocalName()) {
              case "t" -> inText = false;
              case "p" -> text.append('\n');
              default -> {}
            }
          }
          default -> {}
        }
      }
    } catch (XMLStreamException e) {
      // Cut short by the size limit, or damaged part-way: what came before is still the text.
      if (text.isEmpty()) {
        throw new IOException("Could not read the document's text", e);
      }
    } finally {
      if (reader != null) {
        try {
          reader.close();
        } catch (XMLStreamException e) {
          // Nothing left to read from it either way.
        }
      }
    }
    return truncate(text);
  }

  private static String truncate(StringBuilder text) {
    return text.length() > MAX_CHARS ? text.substring(0, MAX_CHARS) : text.toString();
  }

  /** {@code in}, ending after {@code limit} bytes. */
  private static InputStream limited(InputStream in, long limit) {
    return new FilterInputStream(in) {
      private long remaining = limit;

      @Override
      public int read() throws IOException {
        if (remaining <= 0) {
          return -1;
        }
        int b = super.read();
        if (b >= 0) {
          remaining--;
        }
        return b;
      }

      @Override
      public int read(byte[] buffer, int offset, int length) throws IOException {
        if (remaining <= 0) {
          return -1;
        }
        int read = super.read(buffer, offset, (int) Math.min(length, remaining));
        if (read > 0) {
          remaining -= read;
        }
        return read;
      }
    };
  }
}
