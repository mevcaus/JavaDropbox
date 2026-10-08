package com.javadropbox.javadropbox.repository;

import com.javadropbox.javadropbox.model.FileHistory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface FileHistoryRepository extends JpaRepository<FileHistory, Long> {

  /** One page of an account's history. */
  @Query(
      value = "select h from FileHistory h where h.user.id = :userId",
      countQuery = "select count(h) from FileHistory h where h.user.id = :userId")
  Page<FileHistory> findByUser(Long userId, Pageable pageable);
}
