package com.javadropbox.javadropbox.controller;

import com.javadropbox.javadropbox.dto.FileHistoryDto;
import com.javadropbox.javadropbox.repository.FileHistoryRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import org.springframework.data.domain.Sort;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
@Tag(name = "History", description = "Endpoints for viewing file history")
public class HistoryApi {

  private final FileHistoryRepository fileHistoryRepository;

  public HistoryApi(FileHistoryRepository fileHistoryRepository) {
    this.fileHistoryRepository = fileHistoryRepository;
  }

  @GetMapping("/history")
  @Operation(
      summary = "Get file history",
      description = "Returns the history of file operations. Requires authentication.")
  public List<FileHistoryDto> getHistory() {
    return fileHistoryRepository.findAll(Sort.by(Sort.Direction.DESC, "timestamp")).stream()
        .map(FileHistoryDto::fromEntity)
        .toList();
  }
}
