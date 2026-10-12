package com.javadropbox.javadropbox;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.springframework.boot.test.util.TestPropertyValues;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.test.context.ContextConfigurationAttributes;
import org.springframework.test.context.ContextCustomizer;
import org.springframework.test.context.ContextCustomizerFactory;
import org.springframework.test.context.MergedContextConfiguration;
import org.springframework.test.context.TestContextAnnotationUtils;
import org.springframework.test.context.TestContextAnnotationUtils.AnnotationDescriptor;
import org.springframework.util.FileSystemUtils;

/**
 * Gives each test class marked {@link TempServingDirectory} its serving directory. Listed in
 * src/test/resources/META-INF/spring.factories.
 *
 * <p>Spring caches a context for every distinct configuration and reuses it for later test classes
 * with the same one. The customizer it makes is equal only for the one test class, so that class
 * gets a context, and so a folder, of its own. The folder is made when the context starts and goes
 * with it, rather than when the class finishes, since the context outlives its class in the cache.
 */
class TempServingDirectoryContextCustomizerFactory implements ContextCustomizerFactory {

  @Override
  public ContextCustomizer createContextCustomizer(
      Class<?> testClass, List<ContextConfigurationAttributes> configAttributes) {
    AnnotationDescriptor<TempServingDirectory> marked =
        TestContextAnnotationUtils.findAnnotationDescriptor(testClass, TempServingDirectory.class);
    return marked == null ? null : new Customizer(marked.getDeclaringClass());
  }

  private record Customizer(Class<?> testClass) implements ContextCustomizer {

    @Override
    public void customizeContext(
        ConfigurableApplicationContext context, MergedContextConfiguration mergedConfig) {
      Path folder;
      try {
        folder = Files.createTempDirectory("javadropbox-" + testClass.getSimpleName() + "-");
      } catch (IOException e) {
        throw new UncheckedIOException(e);
      }
      TestPropertyValues.of("javadropbox.serving.directory=" + folder).applyTo(context);
      context.addApplicationListener(
          event -> {
            if (event instanceof ContextClosedEvent) {
              FileSystemUtils.deleteRecursively(folder.toFile());
            }
          });
    }
  }
}
