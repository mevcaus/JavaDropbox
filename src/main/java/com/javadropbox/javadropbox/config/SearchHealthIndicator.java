package com.javadropbox.javadropbox.config;

import com.javadropbox.javadropbox.service.SearchIndex;
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
 */
@Component
public class SearchHealthIndicator implements HealthIndicator {

  static final Status DEGRADED = new Status("DEGRADED", "Search is unavailable");

  private final SearchIndex index;
  private final String backend;

  SearchHealthIndicator(
      SearchIndex index, @Value("${" + SearchConfig.BACKEND + ":lucene}") String backend) {
    this.index = index;
    this.backend = backend.toLowerCase(Locale.ROOT);
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
