/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.api;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declares that a {@code String} wire field publishes the ids of {@link #value()} and nothing else,
 * so the generated JSON Schema can state that closed set instead of a bare {@code string}.
 *
 * <p>Some wire fields are enums on the producing side but travel as a {@code String} because the DTO
 * is written by a module that hands over the already-published id (for example {@code
 * AiInstallStatus.PackageStatus.skipCause}, which the install service fills from {@code
 * SkipCause.id()}). Without this marker the schema can only say {@code string}, the generated TS
 * degrades to {@code string | null}, and every consumer's exhaustiveness check over the causes is
 * unenforceable — a fifth backend cause reaches the UI as a silently-unhandled value rather than a
 * compile error.
 *
 * <p>The annotation carries the enum <em>class</em>, never a restated value list: the ids are read
 * off {@code value()}'s constants at generation time (via their {@code id()} accessor when they have
 * one, else the constant name). That keeps the enum the single authority and makes this a
 * projection, not a second copy that can drift from it.
 *
 * <p>Honoured by {@code WireSchemaConfig}, the shared victools configuration behind the {@code
 * SSOT/schemas/} baselines. A field with no such annotation is emitted exactly as before.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.FIELD)
public @interface WireEnumIds {

  /** The enum whose published ids are the field's complete value set. */
  Class<? extends Enum<?>> value();

  /**
   * Whether the empty string is also a legal value — the "unclassified" form a producer writes when
   * it has no enum constant to publish. Declared explicitly because {@code ""} is a value the wire
   * really carries, not an absence, and a schema that omitted it would reject live payloads.
   */
  boolean allowEmpty() default false;
}
