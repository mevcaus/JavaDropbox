package com.javadropbox.javadropbox;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;

/** Real PDFs and Word documents with known text, for the search tests. */
public final class TestDocuments {

  private TestDocuments() {}

  /** A PDF with one page for each of {@code pages}, each holding that line of text. */
  public static byte[] pdf(String... pages) throws IOException {
    try (PDDocument document = new PDDocument();
        ByteArrayOutputStream out = new ByteArrayOutputStream()) {
      PDType1Font font = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
      for (String line : pages) {
        PDPage page = new PDPage();
        document.addPage(page);
        try (PDPageContentStream content = new PDPageContentStream(document, page)) {
          content.beginText();
          content.setFont(font, 12);
          content.newLineAtOffset(72, 700);
          content.showText(line);
          content.endText();
        }
      }
      document.save(out);
      return out.toByteArray();
    }
  }

  /** A .docx with one paragraph for each of {@code paragraphs}. */
  public static byte[] docx(String... paragraphs) throws IOException {
    StringBuilder body = new StringBuilder();
    for (String paragraph : paragraphs) {
      body.append("<w:p><w:r><w:t xml:space=\"preserve\">")
          .append(paragraph)
          .append("</w:t></w:r></w:p>");
    }
    return docxWithBody(body.toString());
  }

  /** A .docx whose {@code <w:body>} is {@code bodyXml}, written as given. */
  public static byte[] docxWithBody(String bodyXml) throws IOException {
    return docxWithDocumentXml(
        "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
            + "<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\">"
            + "<w:body>"
            + bodyXml
            + "</w:body></w:document>");
  }

  /** A .docx whose word/document.xml is {@code xml}, written as given. */
  public static byte[] docxWithDocumentXml(String xml) throws IOException {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
      zip.putNextEntry(new ZipEntry("[Content_Types].xml"));
      zip.write("<Types/>".getBytes(StandardCharsets.UTF_8));
      zip.closeEntry();
      zip.putNextEntry(new ZipEntry("word/document.xml"));
      zip.write(xml.getBytes(StandardCharsets.UTF_8));
      zip.closeEntry();
    }
    return bytes.toByteArray();
  }
}
