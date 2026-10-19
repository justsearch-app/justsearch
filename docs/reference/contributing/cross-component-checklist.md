---
title: Cross-Component Checklist
type: reference
status: stable
description: "Checklist for features spanning build, shell, backend, and frontend."
---

# Cross-Component Checklist

Use this checklist when implementing features that span multiple components (build system, Tauri shell, Java backend, or frontend).

## Quick Check: Is This Cross-Component?

Does your feature touch files in 2+ of these areas?
- `build.gradle.kts` or `modules/*/build.gradle.kts`
- `modules/shell/src-tauri/`
- `modules/ui/src/main/java/`
- `modules/ui-web/src/`

If yes, use this checklist.

## Build System (Gradle)

- [ ] New files staged to correct location
- [ ] Task dependencies correct (use `dependsOn`, `mustRunAfter`)
- [ ] Clean build tested (`node scripts/lib/tool-invoker.cjs gradle clean build`)
- [ ] Verify staged files exist after build

## Tauri Shell (Rust)

- [ ] Resources bundled in `src-tauri/resources/`
- [ ] First-run copy handles files AND directories (see `copy_dir_recursive`)
- [ ] Unexpected conditions logged at WARN level (not silent)
- [ ] Tests cover new copy/setup logic

## Java Backend

- [ ] Path resolution works in prod AND dev mode
- [ ] Missing files produce WARN-level logs with expected path
- [ ] State visible in debug API (e.g., `/api/ai/runtime/status`)
- [ ] Unit tests for path resolution logic

## Frontend (if applicable)

- [ ] Error states displayed to user
- [ ] Loading states handled
- [ ] API errors surfaced meaningfully

## Before Merge

- [ ] Manual E2E test performed on clean install
- [ ] Smoke test added/updated if critical path (see `scripts/smoke-tests/`)
- [ ] Distribution pipeline verified (build -> bundle -> install -> run)

## Common Pitfalls

| Pitfall | Example | Solution |
|---------|---------|----------|
| Silent failures | `if (file == null) return;` | Log at WARN with expected path |
| Dev-only paths | Works in IDE, fails in distribution | Test with clean JUSTSEARCH_HOME |
| Flat copy | Tauri copies files but not subdirectories | Use `copy_dir_recursive` for dirs |
| Missing task deps | Files not staged before bundle | Add explicit Gradle `dependsOn` |
| `isProd` gate on path resolution | `resolveWorkerLibDir(isProd=false)` skips bundled layout | Don't gate bundled path resolution on `isProd`; check prod layout unconditionally as a fallback |
| CUDA DLL bundling | Env vars missing from Tauri sidecar spawn | When adding new native DLL bundles, set `JUSTSEARCH_ONNXRUNTIME_NATIVE_PATH` and `JUSTSEARCH_GPU_ENABLED` in `lib.rs:spawn_headless_backend()`. Missing env vars cause silent fallback to CPU |
| Extended-length paths from Tauri | `Files.isDirectory()` returns false for `\\?\`-prefixed paths | `resource_dir()` on Windows may return `\\?\`-prefixed paths. Normalize paths (strip `\\?\` prefix or use `canonicalize()`) before passing to Java/JVM APIs |

## Related Documentation

- `scripts/smoke-tests/verify-gpu-bundle.ps1`: Example verification script
- `modules/ui/src/main/java/.../HeadlessApp.java`: GPU auto-selection logging (case study)
