package com.javadropbox.javadropbox.config;

import com.javadropbox.javadropbox.service.FileStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.web.context.WebServerInitializedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/** Says where the app is listening and what it serves, with the port it actually bound. */
@Component
public class StartupBanner {

  private static final Logger log = LoggerFactory.getLogger(StartupBanner.class);

  private final FileStore store;

  public StartupBanner(FileStore store) {
    this.store = store;
  }

  @EventListener
  public void onStarted(WebServerInitializedEvent event) {
    log.info(
        "JavaDropbox is running at http://localhost:{} and serving {}",
        event.getWebServer().getPort(),
        store.description());
  }
}
