package com.javadropbox.javadropbox.controller;

import com.javadropbox.javadropbox.dto.SearchResults;
import com.javadropbox.javadropbox.service.SearchService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.io.IOException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Tag(name = "Search", description = "Find files and folders by name and by the text inside them")
public class SearchController {

  private final SearchService searchService;

  public SearchController(SearchService searchService) {
    this.searchService = searchService;
  }

  @GetMapping("/api/search")
  @Operation(
      summary = "Search files",
      description =
          "Items below the folder at path (the root when empty) with every word of q in their"
              + " name or their text, best match first, with the passage of text that matched."
              + " Case and accents are ignored, and the last word also matches the start of a"
              + " longer one. The text of text and source files, PDFs and Word documents is"
              + " searched. indexing is true until the index has caught up after a restart.")
  public SearchResults search(
      @RequestParam String q,
      @RequestParam(defaultValue = "") String path,
      @RequestParam(defaultValue = "50") int limit)
      throws IOException {
    return searchService.search(q, path, limit);
  }
}
