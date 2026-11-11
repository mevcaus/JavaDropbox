package com.javadropbox.javadropbox.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/** Work the app has done once and must not do again, such as taking in an upgrade's files. */
@Entity
@Table(name = "one_time_steps")
public class OneTimeStep {

  @Id
  @Column(length = 100)
  private String name;

  @Column(nullable = false)
  private Instant completedAt;

  protected OneTimeStep() {}

  public OneTimeStep(String name, Instant completedAt) {
    this.name = name;
    this.completedAt = completedAt;
  }

  public String getName() {
    return name;
  }

  public Instant getCompletedAt() {
    return completedAt;
  }
}
