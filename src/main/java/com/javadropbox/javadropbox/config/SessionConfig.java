package com.javadropbox.javadropbox.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.BeanClassLoaderAware;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.convert.converter.Converter;
import org.springframework.core.convert.support.GenericConversionService;
import org.springframework.core.serializer.support.DeserializingConverter;
import org.springframework.core.serializer.support.SerializingConverter;
import org.springframework.session.config.SessionRepositoryCustomizer;
import org.springframework.session.jdbc.JdbcIndexedSessionRepository;

/**
 * Sessions are kept in the database by Spring Session JDBC, each attribute as a serialized Java
 * object: the signed-in account's security context ({@link AccountDetails} among it) and its
 * session version. An upgrade that renames or changes one of those classes leaves the stored bytes
 * unreadable. Spring Session would then fail every request that carries such a session, sign-out
 * included, with a 500 until the session expired, and each failed request would keep it from
 * expiring. Here an attribute that cannot be read counts as missing instead, so the request carries
 * on signed out and gets a 401, as if the session had ended.
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

  static Converter<byte[], Object> readOrForget(Converter<byte[], Object> read) {
    return bytes -> {
      try {
        return read.convert(bytes);
      } catch (RuntimeException e) {
        log.warn("Ignored a session attribute that could not be read: {}", e.toString());
        return null;
      }
    };
  }
}
