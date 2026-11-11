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
import org.springframework.context.ApplicationListener;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.convert.converter.Converter;
import org.springframework.core.convert.support.GenericConversionService;
import org.springframework.core.serializer.support.DeserializingConverter;
import org.springframework.core.serializer.support.SerializingConverter;
import org.springframework.security.authentication.event.AuthenticationSuccessEvent;
import org.springframework.session.FlushMode;
import org.springframework.session.config.SessionRepositoryCustomizer;
import org.springframework.session.jdbc.JdbcIndexedSessionRepository;
import org.springframework.session.web.http.SessionRepositoryFilter;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Sessions are kept in the database by Spring Session JDBC, each attribute as a serialized Java
 * object: the signed-in account's security context, {@link AccountDetails} among it. An upgrade
 * that renames or changes one of those classes leaves the stored bytes unreadable. Spring Session
 * would then fail every request that carries such a session, sign-out included, with a 500, and
 * each failed request would keep it from expiring. Here such a session ends instead: the request
 * carries on signed out and gets a 401, and the browser signs in again.
 *
 * <p>A session is also written as soon as something is put in it, not when the response is sent
 * (FlushMode.IMMEDIATE): a sign-in has to be in the database before it checks that its account
 * didn't change meanwhile (see AccountSessions). That is set here rather than as
 * spring.session.jdbc.flush-mode, so no setting can turn it off. Other requests still write their
 * session once each, when they first load it, which is in {@link EndUnreadableSessions}, ahead of
 * Spring Security and outside any transaction of the app's: a write inside one would take a second
 * connection, and could wait on a lock that transaction holds.
 */
@Configuration
class SessionConfig implements BeanClassLoaderAware {

  private static final Logger log = LoggerFactory.getLogger(SessionConfig.class);

  /**
   * Spring Session's query for deleting expired sessions, on its schedule and at each sign-in, in
   * batches and skipping sessions another transaction has locked: a sign-in doesn't wait on another
   * sign-in's cleanup or on a change ending an account's sessions, and can't deadlock with either.
   * (Deleting a session's attributes can still wait a moment on a request saving them.) A backlog,
   * the demo's after a quiet spell, goes a thousand at a time. The sessions are deleted by primary
   * key from an array, so the delete uses that index however many have expired.
   */
  static final String DELETE_EXPIRED_SESSIONS =
      "DELETE FROM %TABLE_NAME% WHERE PRIMARY_ID = ANY(ARRAY(SELECT PRIMARY_ID FROM %TABLE_NAME%"
          + " WHERE EXPIRY_TIME < ? LIMIT 1000 FOR UPDATE SKIP LOCKED))";

  private ClassLoader classLoader;

  @Override
  public void setBeanClassLoader(ClassLoader classLoader) {
    this.classLoader = classLoader;
  }

  // Customizers run after Spring Session has set up the repository, so this replaces its own
  // conversion service, which serializes the same way but throws on bytes it cannot read, and its
  // flush mode, whatever the settings say.
  @Bean
  SessionRepositoryCustomizer<JdbcIndexedSessionRepository> customizeSessionRepository() {
    GenericConversionService conversions = new GenericConversionService();
    conversions.addConverter(Object.class, byte[].class, new SerializingConverter());
    conversions.addConverter(
        byte[].class, Object.class, readOrForget(new DeserializingConverter(classLoader)));
    return repository -> {
      repository.setConversionService(conversions);
      repository.setFlushMode(FlushMode.IMMEDIATE);
      repository.setDeleteSessionsByExpiryTimeQuery(DELETE_EXPIRED_SESSIONS);
    };
  }

  /**
   * Deletes the sessions that have expired at each sign-in, as well as on Spring Session's own
   * schedule (spring.session.jdbc.cleanup-cron). A sign-in is the only thing that adds a session,
   * so this keeps the table to about the sessions in use even where the schedule can't be counted
   * on: the demo is suspended whenever it's idle, and turns its schedule off. Every way of signing
   * in publishes this event.
   */
  @Bean
  ApplicationListener<AuthenticationSuccessEvent> deleteExpiredSessionsAtSignIn(
      JdbcIndexedSessionRepository sessionRepository) {
    return event -> deleteExpiredSessions(sessionRepository);
  }

  // A failure is only logged: it must not refuse a sign-in that has nothing wrong with it.
  static void deleteExpiredSessions(JdbcIndexedSessionRepository sessionRepository) {
    try {
      sessionRepository.cleanUpExpiredSessions();
    } catch (RuntimeException e) {
      log.warn("Could not delete the expired sessions", e);
    }
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
