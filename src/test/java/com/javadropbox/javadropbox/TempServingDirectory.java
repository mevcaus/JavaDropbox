package com.javadropbox.javadropbox;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Runs a test class on a Spring context of its own, whose serving directory is a new, empty
 * temporary folder, deleted when the context closes. The class reads where it is with
 * {@code @Value("${javadropbox.serving.directory}")}, and empties it between tests ({@link
 * TestStorage#empty}) if they would otherwise see each other's files.
 *
 * <p>Without it, a test class shares {@code build/test-storage} with every other class that runs on
 * the same configuration. See {@link TempServingDirectoryContextCustomizerFactory} for how it
 * works.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@interface TempServingDirectory {}
