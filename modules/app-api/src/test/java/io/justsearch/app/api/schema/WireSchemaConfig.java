package io.justsearch.app.api.schema;

import com.fasterxml.classmate.ResolvedType;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.github.victools.jsonschema.generator.CustomDefinition;
import com.github.victools.jsonschema.generator.FieldScope;
import com.github.victools.jsonschema.generator.OptionPreset;
import com.github.victools.jsonschema.generator.SchemaGenerationContext;
import com.github.victools.jsonschema.generator.SchemaGenerator;
import com.github.victools.jsonschema.generator.SchemaGeneratorConfigBuilder;
import com.github.victools.jsonschema.generator.SchemaKeyword;
import com.github.victools.jsonschema.generator.SchemaVersion;
import com.github.victools.jsonschema.module.jackson.JacksonModule;
import com.github.victools.jsonschema.module.jackson.JacksonOption;
import io.justsearch.agent.api.registry.I18nKey;
import io.justsearch.agent.api.registry.NamespacedId;
import io.justsearch.agent.api.registry.Nullable;
import io.justsearch.agent.api.registry.PreciseWire;
import io.justsearch.app.api.WireEnumIds;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Tempdoc 564 — the single, precise victools configuration for record→JSON-Schema generation.
 *
 * <p>Per the 564 design, the JSON Schema is the canonical contract projection of the Java records;
 * it must be <em>faithful</em> (the shape the FE actually sees) <em>and</em> <em>precise</em>
 * (typed, not the all-optional/untyped default §11 flagged). This helper centralizes the config so
 * every schema-gen test emits the same precise output:
 *
 * <ul>
 *   <li>{@code RESPECT_JSONPROPERTY_ORDER} + {@code RESPECT_JSONPROPERTY_REQUIRED} (already on);
 *   <li>value-class records ({@link NamespacedId} / {@link I18nKey}, {@code @JsonValue} → bare
 *       string on the wire) emit a string schema, not victools' default empty object;
 *   <li><b>typed map-values</b> — the precision fix: {@code Map<String,V>} emits
 *       {@code {"type":"object","additionalProperties": <schema of V>}} (recursively, so
 *       {@code Map<String,Map<String,Long>>} → nested object → integer), where victools' default
 *       loses the value type to a bare {@code {"type":"object"}}.
 * </ul>
 *
 * <p>The faithfulness (no proto3-style wrappers — a bare nested map stays a bare nested map) is
 * inherent to JSON Schema; this helper adds the missing <em>precision</em>.
 */
@SuppressWarnings("removal")
public final class WireSchemaConfig {

  private WireSchemaConfig() {}

  /** A {@link SchemaGenerator} configured for precise, faithful wire-record schemas. */
  public static SchemaGenerator generator() {
    JacksonModule jacksonModule =
        new JacksonModule(
            JacksonOption.RESPECT_JSONPROPERTY_ORDER,
            JacksonOption.RESPECT_JSONPROPERTY_REQUIRED,
            // Enums serialize via their @JsonValue method (e.g. StageId → "query-understanding",
            // StageStatus → "executed"), NOT the Java constant name. Emit the *wire* values so the
            // schema's enum list matches the actual JSON.
            JacksonOption.FLATTENED_ENUMS_FROM_JSONVALUE);
    SchemaGeneratorConfigBuilder builder =
        new SchemaGeneratorConfigBuilder(SchemaVersion.DRAFT_2020_12, OptionPreset.PLAIN_JSON)
            .with(jacksonModule);
    builder.forTypesInGeneral().withCustomDefinitionProvider(WireSchemaConfig::valueClassDefinition);
    builder.forTypesInGeneral().withCustomDefinitionProvider(WireSchemaConfig::typedMapDefinition);
    builder.forFields().withNullableCheck(WireSchemaConfig::isNullableOnWire);
    // Tempdoc 560 §4c: precision — populate `required` for PreciseWire types (closes the 564 §7.2
    // all-optional gap, opt-in so non-registry baselines stay byte-identical).
    builder.forFields().withRequiredCheck(WireSchemaConfig::isRequiredOnWire);
    // A String field that carries an enum's published ids states the closed set (see WireEnumIds).
    builder.forFields().withInstanceAttributeOverride(WireSchemaConfig::injectWireEnumIds);
    return new SchemaGenerator(builder.build());
  }

  /**
   * Narrows a {@link WireEnumIds}-marked {@code String} field from a bare string to the closed set
   * of its enum's published ids, so the schema (and the FE type/Zod generated from it) can state
   * which values the wire actually carries.
   *
   * <p>A nullable field becomes {@code anyOf: [{string + enum}, {null}]} rather than an {@code enum}
   * containing {@code null}: {@code gen-wire-schema-types.mjs} renders a nullable branch as
   * {@code z.enum([...]).nullable()}, while a null INSIDE the enum list would emit
   * {@code z.enum([..., null])} — not a legal Zod enum. Same accepted values, a validator that runs.
   */
  private static void injectWireEnumIds(
      ObjectNode node, FieldScope field, SchemaGenerationContext context) {
    WireEnumIds marker = field.getAnnotationConsideringFieldAndGetter(WireEnumIds.class);
    if (marker == null) {
      return;
    }
    List<String> ids = publishedIds(marker.value());
    if (marker.allowEmpty()) {
      ids.add("");
    }
    String typeKey = SchemaKeyword.TAG_TYPE.forVersion(SchemaVersion.DRAFT_2020_12);
    String enumKey = SchemaKeyword.TAG_ENUM.forVersion(SchemaVersion.DRAFT_2020_12);
    // The `type` keyword is not in the collected member attributes at override time (victools
    // applies nullability to the type schema afterwards), so ask the same predicate the rest of
    // this config uses rather than inspecting the node — otherwise an explicit `type: string`
    // here would silently DROP the null the field is allowed to carry.
    boolean nullable = Boolean.TRUE.equals(isNullableOnWire(field));

    ObjectNode stringBranch = context.getGeneratorConfig().createObjectNode();
    stringBranch.put(typeKey, "string");
    ArrayNode values = context.getGeneratorConfig().createArrayNode();
    ids.forEach(values::add);
    stringBranch.set(enumKey, values);

    node.remove(typeKey);
    node.remove(enumKey);
    if (!nullable) {
      node.setAll(stringBranch);
      return;
    }
    ObjectNode nullBranch = context.getGeneratorConfig().createObjectNode();
    nullBranch.put(typeKey, "null");
    ArrayNode anyOf = context.getGeneratorConfig().createArrayNode();
    anyOf.add(stringBranch);
    anyOf.add(nullBranch);
    node.set(SchemaKeyword.TAG_ANYOF.forVersion(SchemaVersion.DRAFT_2020_12), anyOf);
  }

  /**
   * The wire ids of an enum's constants: its {@code id()} accessor when it publishes one (the
   * convention in this codebase, e.g. {@code SkipCause.id()} → {@code "user-declined"}), else the
   * constant name. Read from the enum itself so the schema projects the enum rather than restating
   * it.
   */
  private static List<String> publishedIds(Class<? extends Enum<?>> enumClass) {
    Method idAccessor;
    try {
      idAccessor = enumClass.getMethod("id");
    } catch (NoSuchMethodException e) {
      idAccessor = null;
    }
    List<String> ids = new ArrayList<>();
    for (Enum<?> constant : enumClass.getEnumConstants()) {
      if (idAccessor == null) {
        ids.add(constant.name());
        continue;
      }
      try {
        ids.add(String.valueOf(idAccessor.invoke(constant)));
      } catch (ReflectiveOperationException e) {
        throw new IllegalStateException("cannot read " + enumClass.getName() + ".id()", e);
      }
    }
    return ids;
  }

  /**
   * Faithful nullability: Jackson emits an explicit {@code null} for a null reference field unless
   * the declaring record (or the field) carries {@code @JsonInclude(NON_NULL)}, in which case the
   * field is <em>omitted</em> (absent, not null). So a field is nullable on the wire iff it is a
   * reference type whose declaring record does not strip nulls. Marking it nullable makes the schema
   * permit the {@code null} the FE actually receives (e.g. {@code nextCursor}, {@code
   * filterNormalization} on {@code KnowledgeSearchResponse}); primitives are never null.
   */
  private static Boolean isNullableOnWire(FieldScope field) {
    if (field.getType().getErasedType().isPrimitive()) {
      return Boolean.FALSE;
    }
    // @JsonInclude(NON_NULL) (field or declaring class) → omitted when null → present implies
    // non-null.
    if (hasJsonIncludeNonNull(field)) {
      return Boolean.FALSE;
    }
    if (isPreciseType(field)) {
      // Tempdoc 560 §4c: on a PreciseWire type only an Optional<> or @Nullable component is nullable
      // (present-as-null); a plain, non-Optional, non-@Nullable reference is asserted non-null.
      return isOptionalType(field) || hasNullable(field) ? Boolean.TRUE : Boolean.FALSE;
    }
    // Legacy permissive default (non-PreciseWire surfaces): any non-NON_NULL reference is nullable.
    return Boolean.TRUE;
  }

  /**
   * Tempdoc 560 §4c precision: a {@link PreciseWire} component is wire-{@code required} iff it is NOT
   * omitted-when-null. An {@code Optional<>} with no {@code @JsonInclude(NON_NULL)} serializes its
   * empty value as {@code null} (present, not absent); a collection serializes as {@code []}
   * (present). Only {@code @JsonInclude(NON_NULL)} makes a field absent. Non-PreciseWire types keep
   * the permissive (never-forced-required) default so their committed baselines stay byte-identical.
   */
  private static Boolean isRequiredOnWire(FieldScope field) {
    if (!isPreciseType(field)) {
      return Boolean.FALSE;
    }
    return hasJsonIncludeNonNull(field) ? Boolean.FALSE : Boolean.TRUE;
  }

  /** True iff a field/class {@code @JsonInclude(NON_NULL)} omits this field when its value is null. */
  private static boolean hasJsonIncludeNonNull(FieldScope field) {
    JsonInclude classInclude =
        field.getDeclaringType().getErasedType().getAnnotation(JsonInclude.class);
    if (classInclude != null && classInclude.value() == JsonInclude.Include.NON_NULL) {
      return true;
    }
    JsonInclude fieldInclude = field.getAnnotationConsideringFieldAndGetter(JsonInclude.class);
    return fieldInclude != null && fieldInclude.value() == JsonInclude.Include.NON_NULL;
  }

  /** True iff the field's declaring record opts into precise required/non-null schema emission. */
  private static boolean isPreciseType(FieldScope field) {
    return PreciseWire.class.isAssignableFrom(field.getDeclaringType().getErasedType());
  }

  /** True iff the component carries the {@link Nullable} present-as-null marker. */
  private static boolean hasNullable(FieldScope field) {
    return field.getAnnotationConsideringFieldAndGetter(Nullable.class) != null;
  }

  /**
   * True iff the field's declared (source) type is {@code Optional<…>}. Read from the raw {@link
   * Field}'s generic type so it holds even though victools unwraps {@code Optional} to its value
   * type before the nullable/required checks see {@code field.getType()}.
   */
  private static boolean isOptionalType(FieldScope field) {
    Field raw = field.getRawMember();
    return raw.getGenericType() instanceof ParameterizedType pt
        && pt.getRawType() == Optional.class;
  }

  /**
   * {@code @JsonValue} value-class records serialize as bare strings; victools' default emits an
   * empty object, so override them to the true string contract.
   */
  private static CustomDefinition valueClassDefinition(
      ResolvedType javaType, SchemaGenerationContext context) {
    Class<?> erased = javaType.getErasedType();
    if (NamespacedId.class.isAssignableFrom(erased)) {
      ObjectNode node = context.getGeneratorConfig().createObjectNode();
      node.put(SchemaKeyword.TAG_TYPE.forVersion(SchemaVersion.DRAFT_2020_12), "string");
      node.put(
          SchemaKeyword.TAG_PATTERN.forVersion(SchemaVersion.DRAFT_2020_12),
          "^(core|vendor\\.[a-z][a-z0-9-]*)\\.[a-z][a-z0-9-]*$");
      return new CustomDefinition(node);
    }
    if (erased == I18nKey.class) {
      ObjectNode node = context.getGeneratorConfig().createObjectNode();
      node.put(SchemaKeyword.TAG_TYPE.forVersion(SchemaVersion.DRAFT_2020_12), "string");
      node.put(SchemaKeyword.TAG_LENGTH_MIN.forVersion(SchemaVersion.DRAFT_2020_12), 1);
      return new CustomDefinition(node);
    }
    // JDK value types Jackson serializes as a bare string (toString/URI), NOT as an object with
    // getters — victools' default treats them as objects, which mismatches the wire. The live-fixture
    // probe caught EffectivePolicy.PolicySource.path (a java.nio.file.Path → "file:///…") this way.
    if (java.nio.file.Path.class.isAssignableFrom(erased)
        || erased == java.net.URI.class
        || erased == java.io.File.class) {
      ObjectNode node = context.getGeneratorConfig().createObjectNode();
      node.put(SchemaKeyword.TAG_TYPE.forVersion(SchemaVersion.DRAFT_2020_12), "string");
      return new CustomDefinition(node);
    }
    return null;
  }

  /**
   * Emit typed map-values: a {@code Map<String,V>} becomes
   * {@code {"type":"object","additionalProperties": <schema of V>}}. The value schema is generated
   * recursively, so a nested map ({@code Map<String,Map<String,Long>>}) yields a nested typed
   * object. Raw maps (no value type arg) fall through to victools' default bare object.
   */
  private static CustomDefinition typedMapDefinition(
      ResolvedType javaType, SchemaGenerationContext context) {
    if (!Map.class.isAssignableFrom(javaType.getErasedType())) {
      return null;
    }
    ResolvedType valueType = context.getTypeContext().getTypeParameterFor(javaType, Map.class, 1);
    if (valueType == null) {
      return null;
    }
    ObjectNode node = context.getGeneratorConfig().createObjectNode();
    node.put(SchemaKeyword.TAG_TYPE.forVersion(SchemaVersion.DRAFT_2020_12), "object");
    node.set(
        SchemaKeyword.TAG_ADDITIONAL_PROPERTIES.forVersion(SchemaVersion.DRAFT_2020_12),
        context.createDefinitionReference(valueType));
    return new CustomDefinition(
        node, CustomDefinition.DefinitionType.INLINE, CustomDefinition.AttributeInclusion.NO);
  }
}
