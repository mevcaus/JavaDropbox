package com.javadropbox.javadropbox.service;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/**
 * What the app is used for, as Micrometer meters under {@code /actuator/metrics}. Each is recorded
 * once the thing it counts has happened: an upload once its transaction has committed, a share link
 * once it has been saved, a file once the service has handed it over to be sent.
 */
@Component
public class UsageMetrics {

  static final String FILES_SERVED = "javadropbox.files.served";
  static final String UPLOAD_SIZE = "javadropbox.uploads.size";
  static final String SHARE_LINKS_CREATED = "javadropbox.share.links.created";

  /** The route a file or folder was served through, the {@code route} tag of files served. */
  public enum Route {
    DOWNLOAD("download"),
    PREVIEW("preview"),
    SHARE_LINK("share-link");

    private final String tag;

    Route(String tag) {
      this.tag = tag;
    }
  }

  private final Counter downloads;
  private final Counter previews;
  private final Counter shareLinkDownloads;
  private final DistributionSummary uploadSize;
  private final Counter shareLinksCreated;

  public UsageMetrics(MeterRegistry registry) {
    downloads = filesServed(registry, Route.DOWNLOAD);
    previews = filesServed(registry, Route.PREVIEW);
    shareLinkDownloads = filesServed(registry, Route.SHARE_LINK);
    uploadSize =
        DistributionSummary.builder(UPLOAD_SIZE)
            .description("Files stored by uploads; the total is the bytes uploaded")
            .baseUnit("bytes")
            .register(registry);
    shareLinksCreated =
        Counter.builder(SHARE_LINKS_CREATED).description("Share links created").register(registry);
  }

  private static Counter filesServed(MeterRegistry registry, Route route) {
    // A range request counts on its own, so a PDF viewer reading a file in parts counts each part.
    return Counter.builder(FILES_SERVED)
        .description("Files and zipped folders served, by the route they were served through")
        .tag("route", route.tag)
        .register(registry);
  }

  public void fileServed(Route route) {
    switch (route) {
      case DOWNLOAD -> downloads.increment();
      case PREVIEW -> previews.increment();
      case SHARE_LINK -> shareLinkDownloads.increment();
    }
  }

  public void fileUploaded(long bytes) {
    uploadSize.record(bytes);
  }

  public void shareLinkCreated() {
    shareLinksCreated.increment();
  }
}
