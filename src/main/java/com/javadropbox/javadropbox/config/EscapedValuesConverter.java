package com.javadropbox.javadropbox.config;

import ch.qos.logback.classic.pattern.MessageConverter;
import ch.qos.logback.classic.spi.ILoggingEvent;
import org.slf4j.helpers.MessageFormatter;

/**
 * A log line's message with every line break and other control character in the values it was given
 * escaped, so a username or a file name can't start a line that looks like one of the app's own.
 * The message's own text is kept as written, line breaks and all. Stands in for {@code %m} in every
 * log line (logback-spring.xml).
 */
public class EscapedValuesConverter extends MessageConverter {

  @Override
  public String convert(ILoggingEvent event) {
    Object[] values = event.getArgumentArray();
    if (values == null || values.length == 0) {
      return event.getFormattedMessage();
    }
    Object[] escaped = values.clone();
    boolean changed = false;
    for (int i = 0; i < escaped.length; i++) {
      if (escaped[i] == null || escaped[i] instanceof Throwable) {
        continue;
      }
      String text = String.valueOf(escaped[i]);
      String safe = escape(text);
      if (!safe.equals(text)) {
        escaped[i] = safe;
        changed = true;
      }
    }
    return changed
        ? MessageFormatter.arrayFormat(event.getMessage(), escaped).getMessage()
        : event.getFormattedMessage();
  }

  /** {@code text} with line breaks and other control characters written as Java escapes. */
  static String escape(String text) {
    StringBuilder out = null;
    for (int i = 0; i < text.length(); i++) {
      char c = text.charAt(i);
      String replacement = replacement(c);
      if (replacement != null && out == null) {
        out = new StringBuilder(text.length() + 8).append(text, 0, i);
      }
      if (out != null) {
        out.append(replacement != null ? replacement : String.valueOf(c));
      }
    }
    return out == null ? text : out.toString();
  }

  private static String replacement(char c) {
    return switch (c) {
      case '\n' -> "\\n";
      case '\r' -> "\\r";
      case '\t' -> null;
      default ->
          Character.isISOControl(c) || c == ' ' || c == ' '
              ? String.format("\\u%04x", (int) c)
              : null;
    };
  }
}
