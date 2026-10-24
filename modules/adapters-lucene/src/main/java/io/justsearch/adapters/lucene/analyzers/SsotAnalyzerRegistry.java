/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.adapters.lucene.analyzers;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;
import io.justsearch.configuration.JustSearchConfigurationLoader;
import java.io.File;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.core.KeywordAnalyzer;
import org.apache.lucene.analysis.LowerCaseFilter;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.Tokenizer;
import org.apache.lucene.analysis.icu.ICUNormalizer2Filter;
import org.apache.lucene.analysis.icu.segmentation.ICUTokenizer;
import org.apache.lucene.analysis.miscellaneous.PerFieldAnalyzerWrapper;

/** Loads analyzers from SSOT catalogs. */
public final class SsotAnalyzerRegistry {
  private static final ObjectMapper M =
      JsonMapper.builder().enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS).build();
  private static final org.slf4j.Logger log =
      org.slf4j.LoggerFactory.getLogger(SsotAnalyzerRegistry.class);
  private static final String CLASSPATH_ANALYZERS = "/SSOT/catalogs/analyzers.v1.json";
  private static final AtomicBoolean BUNDLED_CATALOG_WARNED = new AtomicBoolean();

  private final Map<String, AnalyzerDefinition> definitionsById;
  private final Map<String, AnalyzerDescriptor> descriptorsById;
  private final Map<String, String> aliasToId;
  private final Map<String, Analyzer> analyzersById = new ConcurrentHashMap<>();
  private final String defaultAnalyzerId;

  public SsotAnalyzerRegistry() {
    this(findRepoRootOrNull());
  }

  SsotAnalyzerRegistry(File repoRoot) {
    JsonNode root = loadAnalyzersCatalog(repoRoot);
    if (repoRoot != null) {
      // Was INFO; demoted to DEBUG because instances were created per gRPC
      // request in some paths, producing log noise (tempdoc 374 sandbox
      // round 2 finding #6). The "loaded from repo" outcome is not
      // user-actionable.
      log.debug("Loaded analyzers catalog from repo path: {}/SSOT/catalogs/analyzers.v1.json", repoRoot.getAbsolutePath());
    } else if (BUNDLED_CATALOG_WARNED.compareAndSet(false, true)) {
      log.warn("Using bundled analyzers catalog (SSOT repo root not found). "
          + "This is expected in packaged deployments but may indicate misconfiguration in development. "
          + "Loaded from classpath: {}", CLASSPATH_ANALYZERS);
    }
    Map<String, AnalyzerDefinition> defs = new LinkedHashMap<>();
    Map<String, AnalyzerDescriptor> descriptors = new LinkedHashMap<>();
    Map<String, String> aliases = new LinkedHashMap<>();
    String fallbackId = null;
    for (JsonNode entry : root.withArray("analyzers")) {
      AnalyzerDefinition def = toDefinition(entry);
      defs.put(def.id(), def);
      descriptors.put(def.id(), toDescriptor(def));
      aliases.put(def.id().toLowerCase(Locale.ROOT), def.id());
      if (!def.locale().isBlank()) {
        aliases.put(def.locale().toLowerCase(Locale.ROOT), def.id());
      }
      aliases.put(def.provider().toLowerCase(Locale.ROOT), def.id());
      if (def.provider().toLowerCase(Locale.ROOT).contains("icu")) {
        aliases.putIfAbsent("icu", def.id());
      }
      if (fallbackId == null && ("*".equals(def.locale()) || def.provider().contains("icu"))) {
        fallbackId = def.id();
      }
    }
    if (fallbackId == null && !defs.isEmpty()) {
      fallbackId = defs.keySet().iterator().next();
    }
    this.definitionsById = Collections.unmodifiableMap(defs);
    this.descriptorsById = Collections.unmodifiableMap(descriptors);
    this.aliasToId = Collections.unmodifiableMap(aliases);
    this.defaultAnalyzerId = Objects.requireNonNull(fallbackId, "default analyzer not found");
  }

  public Set<String> analyzerIds() {
    return definitionsById.keySet();
  }

  public Analyzer analyzerForKey(String key) {
    return analyzersById.computeIfAbsent(resolveId(key), this::createAnalyzer);
  }

  public Analyzer buildPerFieldAnalyzer(Map<String, String> fieldToAnalyzerKey) {
    Analyzer defaultAnalyzer = analyzerForKey(null);
    Map<String, Analyzer> perField =
        fieldToAnalyzerKey.entrySet().stream()
            .filter(e -> e.getValue() != null && !e.getValue().isBlank())
            .collect(
                Collectors.toMap(
                    Map.Entry::getKey,
                    e -> analyzerForKey(e.getValue()),
                    (a, b) -> a,
                    LinkedHashMap::new));
    if (perField.isEmpty()) {
      return defaultAnalyzer;
    }
    return new PerFieldAnalyzerWrapper(defaultAnalyzer, perField);
  }

  public AnalyzerDescriptor descriptor(String id) {
    String resolved = resolveId(id);
    AnalyzerDescriptor descriptor = descriptorsById.get(resolved);
    if (descriptor == null) {
      throw new IllegalArgumentException("Unknown analyzer id: " + id);
    }
    return descriptor;
  }

  private Analyzer createAnalyzer(String analyzerId) {
    AnalyzerDefinition def = definitionsById.get(analyzerId);
    if (def == null) {
      throw new IllegalArgumentException("Unknown analyzer id: " + analyzerId);
    }
    return switch (def.provider()) {
      case "icu" -> createIcuAnalyzer();
      case "keyword" -> new KeywordAnalyzer();
      default -> throw new IllegalStateException("Unsupported analyzer provider: " + def.provider());
    };
  }

  private Analyzer createIcuAnalyzer() {
    // Locale-invariant analysis: ICU tokenization + NFC + lowercase — a pure function of Unicode,
    // never of detected language. The per-locale SynonymGraph path was removed in tempdoc 581 §13
    // (native multilingual, no per-language levers); the language-agnostic-analysis gate forbids
    // its reintroduction.
    return new Analyzer() {
      @Override
      protected TokenStreamComponents createComponents(String fieldName) {
        Tokenizer src = new ICUTokenizer();
        TokenStream stream = new ICUNormalizer2Filter(src);
        stream = new LowerCaseFilter(stream);
        return new TokenStreamComponents(src, stream);
      }
    };
  }

  private AnalyzerDefinition toDefinition(JsonNode node) {
    String id = node.path("id").asText();
    String locale = node.path("locale").asText("*");
    String provider = node.path("provider").asText("icu");
    List<String> components = new ArrayList<>();
    for (JsonNode c : node.withArray("components")) {
      components.add(c.asText());
    }
    String fingerprint = node.path("fingerprint").asText("");
    return new AnalyzerDefinition(id, locale, provider, List.copyOf(components), fingerprint);
  }

  private AnalyzerDescriptor toDescriptor(AnalyzerDefinition def) {
    ObjectNode node = M.createObjectNode();
    node.put("locale", def.locale());
    node.put("provider", def.provider());
    ArrayNode components = node.putArray("components");
    def.components().forEach(components::add);
    if (!def.fingerprint().isBlank()) {
      node.put("fingerprint", def.fingerprint());
    }
    String description;
    try {
      description = M.writeValueAsString(node);
    } catch (Exception e) {
      throw new IllegalStateException("Unable to serialize analyzer descriptor for " + def.id(), e);
    }
    return new AnalyzerDescriptor(def.id(), description);
  }

  private String resolveId(String key) {
    if (key == null || key.isBlank()) {
      return defaultAnalyzerId;
    }
    String normalized = key.toLowerCase(Locale.ROOT);
    if (definitionsById.containsKey(normalized)) {
      return normalized;
    }
    String resolved = aliasToId.get(normalized);
    if (resolved != null) {
      return resolved;
    }
    if (definitionsById.containsKey(key)) {
      return key;
    }
    throw new IllegalArgumentException("Unknown analyzer alias: " + key);
  }

  private static JsonNode loadAnalyzersCatalog(File repoRoot) {
    try {
      if (repoRoot != null) {
        return M.readTree(new File(repoRoot, "SSOT/catalogs/analyzers.v1.json"));
      }
      var stream = SsotAnalyzerRegistry.class.getResourceAsStream(CLASSPATH_ANALYZERS);
      if (stream == null) {
        throw new IllegalStateException("Analyzers catalog not found on classpath: " + CLASSPATH_ANALYZERS);
      }
      try (var in = stream) {
        return M.readTree(in);
      }
    } catch (Exception e) {
      throw new IllegalStateException("Failed to load analyzers catalog", e);
    }
  }

  private static File findRepoRootOrNull() {
    java.nio.file.Path root = JustSearchConfigurationLoader.repoRootStatic();
    return root != null ? root.toFile() : null;
  }

  private record AnalyzerDefinition(
      String id, String locale, String provider, List<String> components, String fingerprint) {}

  public static final class AnalyzerFingerprintingService {
    private static final ObjectMapper FP_MAPPER =
        JsonMapper.builder().enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS).build();

    public String fingerprint(SsotAnalyzerRegistry registry, Set<String> analyzerIds) {
      try {
        List<String> sorted = new ArrayList<>(analyzerIds);
        Collections.sort(sorted);
        ArrayNode array = FP_MAPPER.createArrayNode();
        for (String id : sorted) {
          AnalyzerDescriptor descriptor = registry.descriptor(id);
          ObjectNode node = FP_MAPPER.createObjectNode();
          node.put("id", descriptor.id());
          JsonNode descriptionNode = tryParse(descriptor.description());
          if (descriptionNode == null) {
            node.put("description", descriptor.description());
          } else {
            node.set("description", descriptionNode);
          }
          array.add(node);
        }
        byte[] canonical = canonicalJson(array);
        return hexSha256(canonical);
      } catch (Exception e) {
        throw new IllegalStateException("Failed to fingerprint analyzers", e);
      }
    }

    private static JsonNode tryParse(String json) {
      try {
        return FP_MAPPER.readTree(json);
      } catch (Exception e) {
        return null;
      }
    }

    private static byte[] canonicalJson(JsonNode node) {
      return FP_MAPPER.writeValueAsBytes(node);
    }

    private static String hexSha256(byte[] data) {
      try {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        return HexFormat.of().formatHex(digest.digest(data));
      } catch (NoSuchAlgorithmException e) {
        throw new IllegalStateException(e);
      }
    }
  }
}
