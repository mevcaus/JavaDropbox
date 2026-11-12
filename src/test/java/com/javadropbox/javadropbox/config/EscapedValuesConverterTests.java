package com.javadropbox.javadropbox.config;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.LoggingEvent;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Escaping the values in log lines")
class EscapedValuesConverterTests {

  private final Logger logger = new LoggerContext().getLogger("test");
  private final EscapedValuesConverter converter = new EscapedValuesConverter();

  private String line(String message, Object... values) {
    return converter.convert(
        new LoggingEvent(Logger.class.getName(), logger, Level.INFO, message, null, values));
  }

  @Test
  @DisplayName("a line break in a value is escaped, so it can't start a line of its own")
  void lineBreaksInValuesAreEscaped() {
    assertThat(line("{} made \"{}\" an admin", "ada", "bob\nINFO forged\r\n"))
        .isEqualTo("ada made \"bob\\nINFO forged\\r\\n\" an admin");
  }

  @Test
  @DisplayName("other control characters and Unicode line separators are escaped too")
  void otherControlCharactersAreEscaped() {
    assertThat(line("name {}", "a\u0000b\u001b[31mc d e\u0085f"))
        .isEqualTo("name a\\u0000b\\u001b[31mc\\u2028d\\u2029e\\u0085f");
  }

  @Test
  @DisplayName("the message's own line breaks, and tabs anywhere, are kept as written")
  void messageTextIsKept() {
    assertThat(line("\n{}\n code:\n\n     {}\n", "-----", "AB\tCD"))
        .isEqualTo("\n-----\n code:\n\n     AB\tCD\n");
  }

  @Test
  @DisplayName("values with nothing to escape are formatted as SLF4J would, arrays and nulls too")
  void plainValuesAreUnchanged() {
    assertThat(line("{} {} {} {}", "ada", 3, null, new int[] {1, 2}))
        .isEqualTo("ada 3 null [1, 2]");
    assertThat(line("{} and {}", List.of("a\nb"), "c")).isEqualTo("[a\\nb] and c");
    assertThat(line("no values")).isEqualTo("no values");
  }

  @Test
  @DisplayName("an exception passed last is left for the stack trace, not put in the message")
  void throwableIsNotPartOfTheMessage() {
    assertThat(line("could not move {}", "a\nb", new IllegalStateException("x\ny")))
        .isEqualTo("could not move a\\nb");
  }
}
