package com.javadropbox.javadropbox.config;

import java.util.Locale;
import java.util.Set;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.context.EnvironmentAware;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * Which search index the app uses, as {@code javadropbox.search.backend} says: {@code lucene} (the
 * default), inside the app, or {@code elasticsearch}, a service several app servers can share. Each
 * is a bean of its own, there only when picked.
 */
@Configuration
public class SearchConfig implements BeanFactoryPostProcessor, EnvironmentAware {

  public static final String BACKEND = "javadropbox.search.backend";

  private static final Set<String> BACKENDS = Set.of("lucene", "elasticsearch");

  private Environment environment;

  @Override
  public void setEnvironment(Environment environment) {
    this.environment = environment;
  }

  // Checked before any bean is made, rather than leaving the app to fail for want of an index
  // (whichever bean needs one first), so that the error says why.
  @Override
  public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) {
    check(environment.getProperty(BACKEND, "lucene"));
  }

  static void check(String backend) {
    if (!BACKENDS.contains(backend.toLowerCase(Locale.ROOT))) {
      throw new IllegalStateException(
          BACKEND + " must be lucene or elasticsearch, not \"" + backend + "\"");
    }
  }
}
