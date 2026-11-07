# Configuration Module

> **The Gatekeeper**: This module is the **only** place in the codebase that performs SSOT auto-discovery. Low-level libraries should never scan the filesystem for configuration—they should accept pre-loaded POJOs from this module.

## Purpose

This module enforces **Inversion of Control** for JustSearch configuration:

| Old Way (Anti-Pattern) | New Way (This Module) |
|------------------------|----------------------|
| Every module scans for `SSOT/` | Only `JustSearchConfigurationLoader` scans |
| Hardcoded dimension fallbacks (`384`) | Dimension from `FieldCatalogDef.vectorDimension()` |
| Environment variables scattered everywhere | `EnvRegistry` centralizes all env access |
| Tests inherit production config | `FieldCatalogDef.forTesting(dim)` for hermetic tests |

---

## Key Classes

### 1. `JustSearchConfigurationLoader`

The canonical implementation for finding and loading SSOT artifacts.

**Resolution Order:**
1. Explicit path via system property or environment variable
2. Repository layout (developer mode): traverse up from CWD looking for `SSOT/`
3. Classpath resources (production mode): embedded in JAR

**Usage:**
```java
// Application bootstrap (entry points like HeadlessApp, IndexerWorker)
JustSearchConfigurationLoader loader = new JustSearchConfigurationLoader();
FieldCatalogDef catalog = loader.loadFieldCatalog();

// Pass to library (library never auto-discovers)
FieldMapper mapper = new FieldMapper(catalog);
LuceneIndexRuntime runtime = IndexRuntimeFactory.createRuntime(catalog);
```

**Static Helper:**
```java
// For classes that just need the repo root path
Path repoRoot = JustSearchConfigurationLoader.repoRootStatic();
```

### 2. `FieldCatalogDef`

Immutable POJO representing `SSOT/catalogs/fields.v1.json`. No Lucene dependencies—just pure data.

**Key Methods:**
```java
catalog.fields()           // List<FieldDef> - all field definitions
catalog.field("vector")    // FieldDef - lookup by ID
catalog.vectorDimension()  // Integer - vector dimension (e.g., 768)
```

**For Testing:**
```java
// Creates a minimal catalog with 4-dim vectors (fast, hermetic)
FieldCatalogDef testCatalog = FieldCatalogDef.forTesting(4);

// For integration tests that need production-sized vectors
FieldCatalogDef testCatalog = FieldCatalogDef.forTesting(768);
```

### 3. `EnvRegistry`

Type-safe enum for all environment variables and system properties.

**Resolution Order:** System property → Environment variable → Default

**Usage:**
```java
// Get path (null if not set)
Path dataDir = EnvRegistry.DATA_DIR.getPath();

// Get with default
int port = EnvRegistry.API_PORT.getInt(8080);
boolean prod = EnvRegistry.PROD_MODE.getBoolean(false);

// Check if explicitly set
if (EnvRegistry.LLM_MODEL_PATH.isSet()) { ... }
```

**Registered Variables:**

| Enum | System Property | Env Variable | Purpose |
|------|-----------------|--------------|---------|
| `DATA_DIR` | `justsearch.data.dir` | `JUSTSEARCH_DATA_DIR` | Root data directory |
| `SSOT_PATH` | `justsearch.ssot.path` | `JUSTSEARCH_SSOT_PATH` | Override SSOT location |
| `FIELD_CATALOG` | `justsearch.fieldCatalog` | `JUSTSEARCH_FIELD_CATALOG` | Override field catalog path |
| `API_PORT` | `justsearch.api.port` | `JUSTSEARCH_API_PORT` | HTTP API port |
| `PROD_MODE` | `justsearch.prod` | `JUSTSEARCH_PROD` | Production mode flag |
| `LLM_ENABLED` | `justsearch.llm.enabled` | `JUSTSEARCH_LLM_ENABLED` | Enable LLM features |
| `LLM_MODEL_PATH` | `justsearch.llm.model_path` | `JUSTSEARCH_LLM_MODEL_PATH` | LLM model path |

---

## Design Principles

### 1. Libraries Are "Dumb"

Low-level modules (`adapters-lucene`, `ai-bridge`, etc.) **never** scan the filesystem for configuration:

```java
// ❌ WRONG - Library auto-discovers
public class FieldMapper {
    public FieldMapper() {
        this.catalog = findSsotAndParse(); // BAD!
    }
}

// ✅ CORRECT - Library accepts injected config
public class FieldMapper {
    public FieldMapper(FieldCatalogDef catalog) {
        this.catalog = Objects.requireNonNull(catalog);
    }
}
```

### 2. Entry Points Are "Smart"

Application entry points (`HeadlessApp`, `IndexerWorker`, CLI) are responsible for:
1. Creating `JustSearchConfigurationLoader`
2. Loading configuration
3. Injecting it into libraries

### 3. Tests Define Their Own Config

Unit tests should never accidentally inherit production configuration:

```java
// ✅ Unit test - hermetic, fast, deterministic
@Test
void testFieldMapping() {
    FieldCatalogDef catalog = FieldCatalogDef.forTesting(4); // 4-dim vectors
    FieldMapper mapper = new FieldMapper(catalog);
    // ... test logic ...
}

// ✅ Integration test - explicitly loads production config
@Test
void testProductionSchema() {
    FieldCatalogDef catalog = new JustSearchConfigurationLoader().loadFieldCatalog();
    assertNotNull(catalog.vectorDimension());
    assertEquals(768, catalog.vectorDimension());
}
```

---

## Architecture Context

```
┌──────────────────────────────────────────────────────────────┐
│                    Application Entry Points                   │
│  (HeadlessApp, IndexerWorker, CLI)                           │
│                           │                                   │
│           ┌───────────────▼───────────────┐                   │
│           │  JustSearchConfigurationLoader │◄── SMART LAYER   │
│           │  (SSOT discovery + loading)    │                   │
│           └───────────────┬───────────────┘                   │
│                           │                                   │
│              ┌────────────▼────────────┐                      │
│              │     FieldCatalogDef     │◄── PURE DATA (POJO)  │
│              │     (injected down)     │                      │
│              └────────────┬────────────┘                      │
└───────────────────────────┼──────────────────────────────────┘
                            │
            ┌───────────────▼───────────────┐
            │      Low-Level Libraries       │◄── DUMB LAYER
            │  (adapters-lucene, ai-bridge)  │
            │  (accept config, never scan)   │
            └───────────────────────────────┘
```

---

## Related Documentation

- [Environment Variables & System Properties](../../docs/reference/configuration/environment-variables.md) - Supported keys and resolution precedence
- [Runtime Configuration Ownership Matrix](../../docs/reference/configuration/runtime-config-ownership-matrix.md) - Configuration readers, owners, and lifecycle stages
- [`SSOT/catalogs/fields.v1.json`](../../SSOT/catalogs/fields.v1.json) - The production field catalog

