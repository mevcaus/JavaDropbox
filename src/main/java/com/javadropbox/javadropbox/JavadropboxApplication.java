package com.javadropbox.javadropbox;

import java.util.Arrays;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class JavadropboxApplication {

  static final String DIRECTORY_PROPERTY = "javadropbox.serving.directory";

  public static void main(String[] args) {
    SpringApplication.run(JavadropboxApplication.class, withDirectoryShorthand(args));
  }

  /**
   * The serving directory is the {@code javadropbox.serving.directory} property, so it can come
   * from the environment ({@code JAVADROPBOX_SERVING_DIRECTORY}) like any other setting. Two
   * shorthands are kept from earlier versions: {@code --directory=/path} anywhere, or a bare path
   * as the first argument.
   */
  static String[] withDirectoryShorthand(String[] args) {
    String[] result = Arrays.copyOf(args, args.length);
    for (int i = 0; i < result.length; i++) {
      if (result[i].startsWith("--directory=")) {
        result[i] = "--" + DIRECTORY_PROPERTY + "=" + result[i].substring("--directory=".length());
      }
    }
    if (result.length > 0 && !result[0].startsWith("-")) {
      result[0] = "--" + DIRECTORY_PROPERTY + "=" + result[0];
    }
    return result;
  }
}
