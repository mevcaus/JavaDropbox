package com.javadropbox.javadropbox.repository;

import com.javadropbox.javadropbox.model.OneTimeStep;
import java.time.Instant;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.transaction.annotation.Transactional;

public interface OneTimeStepRepository extends JpaRepository<OneTimeStep, String> {

  /**
   * Records that the step called {@code name} is done, unless it already is: two instances
   * finishing it at once both go on.
   */
  @Modifying
  @Transactional
  @Query(
      value =
          "insert into one_time_steps (name, completed_at) values (:name, :now)"
              + " on conflict (name) do nothing",
      nativeQuery = true)
  void recordDone(String name, Instant now);
}
