package com.javadropbox.javadropbox.controller;

import com.javadropbox.javadropbox.dto.FileHistoryDto;
import com.javadropbox.javadropbox.model.FileHistory;
import com.javadropbox.javadropbox.repository.FileHistoryRepository;
import org.springframework.data.domain.Sort;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api")
public class HistoryApi {

    private final FileHistoryRepository fileHistoryRepository;

    public HistoryApi(FileHistoryRepository fileHistoryRepository) {
        this.fileHistoryRepository = fileHistoryRepository;
    }

    @GetMapping("/history")
    public List<FileHistoryDto> getHistory() {
        return fileHistoryRepository.findAll(Sort.by(Sort.Direction.DESC, "timestamp"))
                .stream()
                .map(FileHistoryDto::fromEntity)
                .toList();
    }
}
