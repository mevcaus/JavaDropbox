package com.javadropbox.javadropbox.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.io.IOException;
import java.util.Collections;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.BeanClassLoaderAware;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.convert.converter.Converter;
import org.springframework.core.convert.support.GenericConversionService;
import org.springframework.core.serializer.support.DeserializingConverter;
import org.springframework.core.serializer.support.SerializingConverter;
import org.springframework.session.config.SessionRepositoryCustomizer;
import org.springframework.session.jdbc.JdbcIndexedSessionRepository;
import org.springframework.session.web.http.SessionRepositoryFilter;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Sessions are kept in the database by Spring Session JDBC, each attribute as a serialized Java
 * object: the signed-in account's security context ({@link AccountDetails} among it) and its
 * session version. An upgrade that renames or changes one of those classes leaves the stored bytes
 * unreadable. Spring Session would then fail every request that carries such a session, sign-out
 * included, with a 500, and each failed request would keep it from expiring. Here such a session
 * ends instead: the request carries on signed out and gets a 401, and the browser signs in again.
 */
@Configuration
class SessionConfig implements BeanClassLoaderAware {

  private static final Logger log = LoggerFactory.getLogger(SessionConfig.class);

  private ClassLoader classLoader;

  @Override
  public void setBeanClassLoader(ClassLoader classLoader) {
    this.classLoader = classLoader;
  }

  // Customizers run after Spring Session has set up the repository, so this replaces its own
  // conversion service, which serializes the same way but throws on bytes it cannot read.
  @Bean
  SessionRepositoryCustomizer<JdbcIndexedSessionRepository>
      unreadableSessionAttributesAreMissing() {
    GenericConversionService conversions = new GenericConversionService();
    conversions.addConverter(Object.class, byte[].class, new SerializingConverter());
    conversions.addConverter(
        byte[].class, Object.class, readOrForget(new DeserializingConverter(classLoader)));
    return repository -> repository.setConversionService(conversions);
  }

  /** Right after Spring Session has loaded the request's session, before anything reads it. */
  @Bean
  FilterRegistrationBean<EndUnreadableSessions> endUnreadableSessions() {
    FilterRegistrationBean<EndUnreadableSessions> registration =
        new FilterRegistrationBean<>(new EndUnreadableSessions());
    registration.setOrder(SessionRepositoryFilter.DEFAULT_ORDER + 1);
    return registration;
  }

  // The attribute's name is only known to the filter below, which logs it.
  static Converter<byte[], Object> readOrForget(Converter<byte[], Object> read) {
    return bytes -> {
      try {
        return read.convert(bytes);
      } catch (RuntimeException e) {
        log.debug("A session attribute could not be read", e);
        return null;
      }
    };
  }

  /**
   * Ends a session that has an attribute that could not be read: a stored attribute is never null
   * otherwise, since Spring Session removes one that is set to null.
   */
  static class EndUnreadableSessions extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(
        HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
        throws ServletException, IOException {
      HttpSession session = request.getSession(false);
      if (session != null) {
        for (String name : Collections.list(session.getAttributeNames())) {
          if (session.getAttribute(name) == null) {
            log.warn("Ended a session whose {} attribute could not be read", name);
            session.invalidate();
            break;
          }
        }
      }
      filterChain.doFilter(request, response);
    }
  }
}
