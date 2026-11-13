package com.javadropbox.javadropbox.config;

import com.javadropbox.javadropbox.service.SearchIndex;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Locale;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.actuate.health.Status;
import org.springframework.stereotype.Component;

/**
 * The {@code search} component of {@code /actuator/health}: which backend the index is on and, when
 * a search service has kept it from taking changes or answering searches, what did, until one gets
 * through again. That is {@code DEGRADED}, not {@code DOWN}: everything but search still works, so
 * the app as a whole stays {@code UP}, and a load balancer keeps sending it requests. Spring Boot
 * leaves a status it doesn't rank out of the overall one.
 *
 * <p>The same, as a number for alerts, is the {@value #AVAILABLE} gauge in {@code
 * /actuator/metrics}: 1 while search works, 0 while it is degraded.
 */
@Component
public class SearchHealthIndicator implements HealthIndicator {

  static final Status DEGRADED = new Status("DEGRADED", "Search is unavailable");
  static final String AVAILABLE = "javadropbox.search.available";

  private final SearchIndex index;
  private final String backend;

  SearchHealthIndicator(
      SearchIndex index,
      @Value("${" + SearchConfig.BACKEND + ":lucene}") String backend,
      MeterRegistry registry) {
    this.index = index;
    this.backend = backend.toLowerCase(Locale.ROOT);
    Gauge.builder(AVAILABLE, index, i -> i.problem().isEmpty() ? 1 : 0)
        .description("1 while search works, 0 while the search service keeps it from working")
        .register(registry);
  }

  @Override
  public Health health() {
    return index
        .problem()
        .map(problem -> Health.status(DEGRADED).withDetail("problem", problem))
        .orElseGet(Health::up)
        .withDetail("backend", backend)
        .build();
  }
}
