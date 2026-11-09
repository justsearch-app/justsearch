---
schema: test-intent.v1
task: t-20261109-windowfix
author-role: builder
author-session: windowfix-w2-builder
---

# Test-intent entries

The expectations are agent choices under the quoted delegation, not owner-agreed words. Independent acceptance is pending.

```json
{
  "entries": [
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/BrainSurface.picture-availability.test.ts"
      ],
      "class": "New",
      "behaviour": "Deliver checks through the real poller and store with delayed runtime responses: agreement on first load, live model changes, reopen and restart (P1, P2); a read started before a receipt cannot show not available or end the transition, for On, Off, repeated switching, leaving the screen, a second window and a replayed receipt (P5); receipt then offline, failed restart, refusal, interruption and recovery each end truthfully (P6). Each fails by assertion on e3a479db7d and passes on the candidate.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "Issues in the windows (Recommended)",
          "delegationLocation": "task t-20261109-windowfix request",
          "delegationScope": "Fix what can actually be run into in Library and AI Brain; here, the AI Brain picture switch contradicting its sentence.",
          "choice": "Saved On, complete files, no operator override and a confirmed text-only running model read \"On — not available with this answer model\" in the feature state and the sentence. Only a runtime read started after the last picture change can show it or end a transition; every failure path ends truthful.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/windowfix-w2-repair.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/views/BrainSurface.picture-availability.test.ts#P1 agrees in both switch locations and the sentence on first load",
        "modules/ui-web/src/shell-v0/views/BrainSurface.picture-availability.test.ts#P2 updates live through text-only, picture-reading and text-only models without a reload",
        "modules/ui-web/src/shell-v0/views/BrainSurface.picture-availability.test.ts#P2 holds after reopening the surface",
        "modules/ui-web/src/shell-v0/views/BrainSurface.picture-availability.test.ts#P2 holds across an engine restart once the engine reports the model again",
        "modules/ui-web/src/shell-v0/views/BrainSurface.picture-availability.test.ts#P5 a read started before an On receipt cannot show not available or clear the transition",
        "modules/ui-web/src/shell-v0/views/BrainSurface.picture-availability.test.ts#P5 a read started after the receipt does show not available in both presentations",
        "modules/ui-web/src/shell-v0/views/BrainSurface.picture-availability.test.ts#P5 a read started before an Off receipt keeps Off instead of the saved-Off mismatch",
        "modules/ui-web/src/shell-v0/views/BrainSurface.picture-availability.test.ts#P5 repeated switching judges a delayed read against the latest receipt",
        "modules/ui-web/src/shell-v0/views/BrainSurface.picture-availability.test.ts#P5 leaving the screen mid-operation keeps the ordering",
        "modules/ui-web/src/shell-v0/views/BrainSurface.picture-availability.test.ts#P5 a second window that learns the choice from settings ignores a read started before it did",
        "modules/ui-web/src/shell-v0/views/BrainSurface.picture-availability.test.ts#P6 receipt then offline stops claiming the transition and recovers truthfully",
        "modules/ui-web/src/shell-v0/views/BrainSurface.picture-availability.test.ts#P6 receipt then a failed restart ends unconfirmed and recovers with a fresh read",
        "modules/ui-web/src/shell-v0/views/BrainSurface.picture-availability.test.ts#P6 a failed restart that still committed the choice converges on a later settings read",
        "modules/ui-web/src/shell-v0/views/BrainSurface.picture-availability.test.ts#P6 a refused change keeps the saved choice and the next read still reports the model",
        "modules/ui-web/src/shell-v0/views/BrainSurface.picture-availability.test.ts#P6 an interrupted change reports uncertainty, then ends on what a fresh read shows",
        "modules/ui-web/src/shell-v0/views/BrainSurface.picture-availability.test.ts#P6 a reconciled receipt is ordered like a direct one"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/BrainSurface.picture-availability.test.ts"
      ],
      "class": "New",
      "behaviour": "Preservation checks passing before and after: failed, null and offline runtime observations stay unknown (P3); explicit Off, operator overrides, missing files, unknown settings, no saved choice and saved Off with runtime On keep their wording, and no saved preference is rewritten (P4). These checks do not establish repaired behaviour.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "Issues in the windows (Recommended)",
          "delegationLocation": "task t-20261109-windowfix request",
          "delegationScope": "Fix what can actually be run into in Library and AI Brain; here, the AI Brain picture switch contradicting its sentence.",
          "choice": "Saved On, complete files, no operator override and a confirmed text-only running model read \"On — not available with this answer model\" in the feature state and the sentence. Only a runtime read started after the last picture change can show it or end a transition; every failure path ends truthful.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/windowfix-w2-preservation.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/views/BrainSurface.picture-availability.test.ts#P3 a failed runtime read after a confirmed text-only model stays unknown",
        "modules/ui-web/src/shell-v0/views/BrainSurface.picture-availability.test.ts#P3 a null projector report stays unconfirmed, never text-only",
        "modules/ui-web/src/shell-v0/views/BrainSurface.picture-availability.test.ts#P3 an offline engine keeps a retained text-only report from reading as confirmed",
        "modules/ui-web/src/shell-v0/views/BrainSurface.picture-availability.test.ts#P4 'explicit Off' keeps its wording and the saved preference",
        "modules/ui-web/src/shell-v0/views/BrainSurface.picture-availability.test.ts#P4 'operator override Off' keeps its wording and the saved preference",
        "modules/ui-web/src/shell-v0/views/BrainSurface.picture-availability.test.ts#P4 'operator override On' keeps its wording and the saved preference",
        "modules/ui-web/src/shell-v0/views/BrainSurface.picture-availability.test.ts#P4 'missing files' keeps its wording and the saved preference",
        "modules/ui-web/src/shell-v0/views/BrainSurface.picture-availability.test.ts#P4 'unknown settings' keeps its wording and the saved preference",
        "modules/ui-web/src/shell-v0/views/BrainSurface.picture-availability.test.ts#P4 'no saved choice' keeps its wording and the saved preference",
        "modules/ui-web/src/shell-v0/views/BrainSurface.picture-availability.test.ts#P4 'saved Off with runtime On' keeps its wording and the saved preference"
      ],
      "repaired": false
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/state/brainPage.test.ts"
      ],
      "class": "New",
      "behaviour": "The sentence authority states the not-available feature state in the agreed wording instead of denying picture reading. Fails by assertion on e3a479db7d and passes on the candidate.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "Issues in the windows (Recommended)",
          "delegationLocation": "task t-20261109-windowfix request",
          "delegationScope": "Fix what can actually be run into in Library and AI Brain; here, the AI Brain picture switch contradicting its sentence.",
          "choice": "Saved On, complete files, no operator override and a confirmed text-only running model read \"On — not available with this answer model\" in the feature state and the sentence. Only a runtime read started after the last picture change can show it or end a transition; every failure path ends truthful.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/windowfix-w2-repair.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/state/brainPage.test.ts#says picture reading is on but not available with this answer model when the feature state does"
      ],
      "repaired": true
    }
  ]
}
```
