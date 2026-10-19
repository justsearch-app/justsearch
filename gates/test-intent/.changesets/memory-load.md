---
schema: test-intent.v1
task: t-20261019-f0a7d3
author-role: builder
author-session: cc6d91b2-5314-42fd-867b-dbc2d8a68699
---

# Test-intent entries

All expectations below come from the supplied builder assignment's user-adopted scenarios.
Existing expectations remain intact. Repair evidence includes assertion failures on the starting origin/main (892243645459);
preservation checks also run in a shared baseline clone (external build log in the handover).
Before-state evidence uses isolated shared clones through a temporary git adapter, with no
production-code overlays and no source-repository worktree metadata writes.

```json
{
  "entries": [
    {
      "items": [
        "modules/app-agent/src/test/java/io/justsearch/agent/FileMemoryStoreAvailabilityTest.java"
      ],
      "class": "New",
      "behaviour": "Incomplete, undecryptable and unreadable loads refuse mutations and reads without changing the file; each access retries once; recovery restores all fields and same/new-ID cardinality. A symlink loop is indeterminate existence; removing it proves first-run absence.",
      "sources": [
        {
          "kind": "owner-words",
          "quote": "While unavailable, every mutation is refused and memory.json stays byte-for-byte unchanged.",
          "location": "task t-20261019-f0a7d3 request"
        },
        {
          "kind": "owner-words",
          "quote": "While unavailable, no read presents a partial or empty set as complete.",
          "location": "task t-20261019-f0a7d3 request"
        },
        {
          "kind": "owner-words",
          "quote": "Automatic recovery: the next memory access after the cause is removed loads all memories without a restart, and later mutations then work. A new ID persists N+1; a same-ID replacement persists N. Repeated accesses during the failure retry the load at most once per access; there are no background loops.",
          "location": "task t-20261019-f0a7d3 request"
        },
        {
          "kind": "owner-words",
          "quote": "Key publication before the listener runs: neither a mutation nor a read in that window persists or reports a partial or empty set. Both orderings are exercised with barriers, then a reload compares content. Concurrent lock or unlock completes within a bound, with no deadlock between the key manager's monitor and the store's.",
          "location": "task t-20261019-f0a7d3 request"
        }
      ],
      "evidence": "gates/test-intent/evidence/memory-load-store.json",
      "newChecks": [
        "modules/app-agent/src/test/java/io/justsearch/agent/FileMemoryStoreAvailabilityTest.java#failedPartialLoadRefusesEveryAccessAndRecovers",
        "modules/app-agent/src/test/java/io/justsearch/agent/FileMemoryStoreAvailabilityTest.java#decryptFailureRetriesOncePerAccess",
        "modules/app-agent/src/test/java/io/justsearch/agent/FileMemoryStoreAvailabilityTest.java#indeterminateExistenceDoesNotBecomeFirstRun",
        "modules/app-agent/src/test/java/io/justsearch/agent/FileMemoryStoreAvailabilityTest.java#ioFailureIsUnavailableAndRecovers",
        "modules/app-agent/src/test/java/io/justsearch/agent/FileMemoryStoreAvailabilityTest.java#parseFailureRefusesAndRecovers",
        "modules/app-agent/src/test/java/io/justsearch/agent/FileMemoryStoreAvailabilityTest.java#keyLockDuringReadinessCheckCannotReportEmpty"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/app-services/src/test/java/io/justsearch/app/services/encryption/MemoryUnlockAvailabilityTest.java"
      ],
      "class": "New",
      "behaviour": "Real DataKeyManager transitions reject failed loads and automatically recover. Barriers exercise read-first and write-first access before the listener while unlock owns its monitor, bounded futures catch deadlocks, and disk reload preserves old and new records. Repeated unlock fires no listener.",
      "sources": [
        {
          "kind": "owner-words",
          "quote": "While unavailable, every mutation is refused and memory.json stays byte-for-byte unchanged.",
          "location": "task t-20261019-f0a7d3 request"
        },
        {
          "kind": "owner-words",
          "quote": "While unavailable, no read presents a partial or empty set as complete.",
          "location": "task t-20261019-f0a7d3 request"
        },
        {
          "kind": "owner-words",
          "quote": "Automatic recovery: the next memory access after the cause is removed loads all memories without a restart, and later mutations then work. A new ID persists N+1; a same-ID replacement persists N. Repeated accesses during the failure retry the load at most once per access; there are no background loops.",
          "location": "task t-20261019-f0a7d3 request"
        },
        {
          "kind": "owner-words",
          "quote": "Key publication before the listener runs: neither a mutation nor a read in that window persists or reports a partial or empty set. Both orderings are exercised with barriers, then a reload compares content. Concurrent lock or unlock completes within a bound, with no deadlock between the key manager's monitor and the store's.",
          "location": "task t-20261019-f0a7d3 request"
        },
        {
          "kind": "owner-words",
          "quote": "Every transition into unlocked holds M1-M3: unlock with the passphrase, unlock with the recovery key, setup with existing plaintext memories, and a passphrase change while locked. A repeated unlock while already unlocked does not fire listeners, so it is not a recovery path.",
          "location": "task t-20261019-f0a7d3 request"
        },
        {
          "kind": "owner-words",
          "quote": "Lock, unlock and lock again: no mutation while locked, and nothing written on lock.",
          "location": "task t-20261019-f0a7d3 request"
        }
      ],
      "evidence": "gates/test-intent/evidence/memory-load-services.json",
      "newChecks": [
        "modules/app-services/src/test/java/io/justsearch/app/services/encryption/MemoryUnlockAvailabilityTest.java#publishedKeyBeforeListenerProtectsReadAndWrite",
        "modules/app-services/src/test/java/io/justsearch/app/services/encryption/MemoryUnlockAvailabilityTest.java#writeFirstBeforeListenerLoadsExistingMemories",
        "modules/app-services/src/test/java/io/justsearch/app/services/encryption/MemoryUnlockAvailabilityTest.java#everyUnlockTransitionGuardsFailedLoads"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/app-services/src/test/java/io/justsearch/app/services/conversation/spi/MemoryExtractionConsumerTest.java"
      ],
      "class": "New",
      "behaviour": "Concurrent automatic extraction after a partial load completes without throwing, logs one unavailable warning, preserves bytes, and core_remember fails. A recovery through a different caller starts a fresh log episode.",
      "sources": [
        {
          "kind": "owner-words",
          "quote": "While unavailable, every mutation is refused and memory.json stays byte-for-byte unchanged.",
          "location": "task t-20261019-f0a7d3 request"
        },
        {
          "kind": "owner-words",
          "quote": "While unavailable, no read presents a partial or empty set as complete.",
          "location": "task t-20261019-f0a7d3 request"
        },
        {
          "kind": "owner-words",
          "quote": "A chat whose automatic extraction is refused still completes normally. The refusal is logged once per unavailable episode, a new episode starts after a successful recovery, and repeated or concurrent chat turns do not spam the log.",
          "location": "task t-20261019-f0a7d3 request"
        }
      ],
      "evidence": "gates/test-intent/evidence/memory-load-services.json",
      "newChecks": [
        "modules/app-services/src/test/java/io/justsearch/app/services/conversation/spi/MemoryExtractionConsumerTest.java#failedLoadSkipsExtractionOncePerEpisodeAndToolRefuses"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/app-services/src/test/java/io/justsearch/app/services/HeadAssemblyTest.java"
      ],
      "class": "New",
      "behaviour": "The actual memory backup source and restore sink refuse a partial failed cache (including colliding IDs), preserve the file, recover on next access and retain the skip-existing collision policy.",
      "sources": [
        {
          "kind": "owner-words",
          "quote": "While unavailable, every mutation is refused and memory.json stays byte-for-byte unchanged.",
          "location": "task t-20261019-f0a7d3 request"
        },
        {
          "kind": "owner-words",
          "quote": "While unavailable, no read presents a partial or empty set as complete.",
          "location": "task t-20261019-f0a7d3 request"
        },
        {
          "kind": "owner-words",
          "quote": "Automatic recovery: the next memory access after the cause is removed loads all memories without a restart, and later mutations then work. A new ID persists N+1; a same-ID replacement persists N. Repeated accesses during the failure retry the load at most once per access; there are no background loops.",
          "location": "task t-20261019-f0a7d3 request"
        },
        {
          "kind": "owner-words",
          "quote": "A successful load behaves as today. Preserved: every field (id, kind, content, sourceConversationId, actor, createdAt), legacy-array loading, same-ID replacement, newest-first ordering, forget and clear surviving a restart, the cache rolled back after a persistence failure, and HTTP listing and backup export/restore round trips with ID collisions.",
          "location": "task t-20261019-f0a7d3 request"
        }
      ],
      "evidence": "gates/test-intent/evidence/memory-load-services.json",
      "newChecks": [
        "modules/app-services/src/test/java/io/justsearch/app/services/HeadAssemblyTest.java#memoryBackupRefusesFailedCacheAndRecovers"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/ui/src/test/java/io/justsearch/ui/api/MemoryControllerTest.java"
      ],
      "class": "New",
      "behaviour": "The memory HTTP handlers return 503 with MEMORIES_UNAVAILABLE for list/remember/forget after a partial failed load, without success or memory payloads or disk changes. A later list recovers all stored records.",
      "sources": [
        {
          "kind": "owner-words",
          "quote": "While unavailable, every mutation is refused and memory.json stays byte-for-byte unchanged.",
          "location": "task t-20261019-f0a7d3 request"
        },
        {
          "kind": "owner-words",
          "quote": "While unavailable, no read presents a partial or empty set as complete.",
          "location": "task t-20261019-f0a7d3 request"
        },
        {
          "kind": "owner-words",
          "quote": "Automatic recovery: the next memory access after the cause is removed loads all memories without a restart, and later mutations then work. A new ID persists N+1; a same-ID replacement persists N. Repeated accesses during the failure retry the load at most once per access; there are no background loops.",
          "location": "task t-20261019-f0a7d3 request"
        }
      ],
      "evidence": "gates/test-intent/evidence/memory-load-http.json",
      "newChecks": [
        "modules/ui/src/test/java/io/justsearch/ui/api/MemoryControllerTest.java#failedLoadReturnsUnavailableForListRememberAndForget"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/MemorySurface.locked.test.ts"
      ],
      "class": "New",
      "behaviour": "The browser Memory surface shows unavailable on first opening and on revisiting a failed GET, disables writes, hides stale content and never claims an empty store. Reopening after recovery displays memories.",
      "sources": [
        {
          "kind": "owner-words",
          "quote": "The Memory page in the browser shows that memories are unavailable, both on first opening and on revisiting during the failure, and never shows \"No learned memory yet\". After recovery it shows the memories.",
          "location": "task t-20261019-f0a7d3 request"
        }
      ],
      "evidence": "gates/test-intent/evidence/memory-load-ui.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/views/MemorySurface.locked.test.ts#shows unavailable on first open and revisit, then reloads after recovery",
        "modules/ui-web/src/shell-v0/views/MemorySurface.locked.test.ts#hides a stale list when a revisit fails to load"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/app-agent/src/test/java/io/justsearch/agent/FileMemoryStoreAvailabilityTest.java"
      ],
      "class": "New",
      "behaviour": "A failed persistence rolls back the cache; forget and clear survive a restart with encryption disabled.",
      "sources": [
        {
          "kind": "owner-words",
          "quote": "A successful load behaves as today. Preserved: every field (id, kind, content, sourceConversationId, actor, createdAt), legacy-array loading, same-ID replacement, newest-first ordering, forget and clear surviving a restart, the cache rolled back after a persistence failure, and HTTP listing and backup export/restore round trips with ID collisions.",
          "location": "task t-20261019-f0a7d3 request"
        },
        {
          "kind": "owner-words",
          "quote": "Encryption off: unchanged, including a corrupt file at construction staying fatal and first-run creation when the file is absent.",
          "location": "task t-20261019-f0a7d3 request"
        }
      ],
      "evidence": "gates/test-intent/evidence/memory-load-preservation.json",
      "newChecks": [
        "modules/app-agent/src/test/java/io/justsearch/agent/FileMemoryStoreAvailabilityTest.java#persistenceRollbackAndDeletionSurviveRestart"
      ]
    },
    {
      "items": [
        "modules/app-services/src/test/java/io/justsearch/app/services/HeadAssemblyTest.java"
      ],
      "class": "New",
      "behaviour": "Actual registered memory backup export/restore preserves every record field and skips IDs already present.",
      "sources": [
        {
          "kind": "owner-words",
          "quote": "A successful load behaves as today. Preserved: every field (id, kind, content, sourceConversationId, actor, createdAt), legacy-array loading, same-ID replacement, newest-first ordering, forget and clear surviving a restart, the cache rolled back after a persistence failure, and HTTP listing and backup export/restore round trips with ID collisions.",
          "location": "task t-20261019-f0a7d3 request"
        }
      ],
      "evidence": "gates/test-intent/evidence/memory-load-preservation.json",
      "newChecks": [
        "modules/app-services/src/test/java/io/justsearch/app/services/HeadAssemblyTest.java#memoryBackupRoundTripPreservesFieldsAndCollisions"
      ]
    },
    {
      "items": [
        "modules/ui/src/test/java/io/justsearch/ui/api/MemoryControllerTest.java"
      ],
      "class": "New",
      "behaviour": "HTTP remember replaces an existing ID, listing preserves all authored fields, and restart and deletion preserve the durable result.",
      "sources": [
        {
          "kind": "owner-words",
          "quote": "A successful load behaves as today. Preserved: every field (id, kind, content, sourceConversationId, actor, createdAt), legacy-array loading, same-ID replacement, newest-first ordering, forget and clear surviving a restart, the cache rolled back after a persistence failure, and HTTP listing and backup export/restore round trips with ID collisions.",
          "location": "task t-20261019-f0a7d3 request"
        }
      ],
      "evidence": "gates/test-intent/evidence/memory-load-preservation.json",
      "newChecks": [
        "modules/ui/src/test/java/io/justsearch/ui/api/MemoryControllerTest.java#healthyHttpRoundTripReplacesIdAndSurvivesRestart"
      ]
    }
  ]
}
```

## Acceptance records
