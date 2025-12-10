package com.javadropbox.javadropbox.repository;

import com.javadropbox.javadropbox.model.FileHistory;
import org.springframework.data.jpa.repository.JpaRepository;

public interface FileHistoryRepository extends JpaRepository<FileHistory, Long> {
}
