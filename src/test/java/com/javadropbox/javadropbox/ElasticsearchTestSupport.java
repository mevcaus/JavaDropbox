package com.javadropbox.javadropbox;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.testcontainers.elasticsearch.ElasticsearchContainer;

/**
 * The Elasticsearch the tests run on: one container for the whole run, started the first time a
 * test asks for it, with an index of its own for each test class or context that uses it.
 */
public final class ElasticsearchTestSupport {

  // The release line of the client the app uses.
  static final String IMAGE = "docker.elastic.co/elasticsearch/elasticsearch:8.18.6";

  private static ElasticsearchContainer shared;
  private static final AtomicInteger indexes = new AtomicInteger();

  private ElasticsearchTestSupport() {}

  /** The service's URL, starting it first if need be. */
  public static synchronized String url() {
    if (shared == null) {
      ElasticsearchContainer elasticsearch =
          new ElasticsearchContainer(IMAGE)
              .withEnv("xpack.security.enabled", "false")
              .withEnv("ES_JAVA_OPTS", "-Xms512m -Xmx512m")
              // A nearly full disk would otherwise stop it taking writes, which the app waits out.
              .withEnv("cluster.routing.allocation.disk.threshold_enabled", "false")
              .withStartupTimeout(Duration.ofMinutes(3));
      elasticsearch.start();
      shared = elasticsearch;
    }
    return "http://" + shared.getHttpHostAddress();
  }

  /** A name for an index no other test uses. */
  public static String newIndex() {
    return "test-" + indexes.incrementAndGet();
  }

  /** Points a context at the shared service, with an index of its own. */
  static void register(DynamicPropertyRegistry registry) {
    register(registry, ElasticsearchTestSupport::url);
  }

  /** Points a context at the service at {@code url}, with an index of its own. */
  static void register(DynamicPropertyRegistry registry, Supplier<Object> url) {
    String index = newIndex();
    registry.add("javadropbox.search.backend", () -> "elasticsearch");
    registry.add("javadropbox.search.elasticsearch.url", url);
    registry.add("javadropbox.search.elasticsearch.index", () -> index);
  }
}
