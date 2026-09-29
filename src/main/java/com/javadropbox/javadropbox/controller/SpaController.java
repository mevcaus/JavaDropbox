package com.javadropbox.javadropbox.controller;

import io.swagger.v3.oas.annotations.Hidden;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * Serves the single-page app for its client-side routes, so a browser that opens or refreshes one
 * of them gets the app rather than a 404. The built app lives in {@code static/} (see the
 * Dockerfile); in development Vite serves it instead and this is never reached.
 */
@Hidden
@Controller
public class SpaController {

  /**
   * Client-side routes defined in frontend/src/App.jsx. SecurityConfig lets them through without a
   * session; keep the mapping below in step (annotations cannot reference an array constant).
   */
  public static final String[] ROUTES = {"/", "/login", "/setup", "/dashboard"};

  @GetMapping({"/", "/login", "/setup", "/dashboard"})
  public String app() {
    return "forward:/index.html";
  }
}
