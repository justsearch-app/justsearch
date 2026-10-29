# WP3.8b builder evidence

The official test-intent runner executes 28 individually selected Vitest checks. Every candidate check passes; every check fails by assertion on the exact Engine-only baseline `1a4ec4a551db33f3dc2cb536b5eb437875ef86dd`. No known-good UI revision exists for the newly added controls.

`runner-machine-rules.patch` records the temporary launcher adaptations: owned shared before clone retained under TMPDIR; plain npm ci in its own real dependency folder; `--configLoader runner --maxWorkers=2`. The runner runs in an owned shared candidate clone with the exact working-tree patch and the new test file, so temporary-index/object fingerprinting writes no source repository metadata. No commit was made. Producer, parsing, digest and assertion classification logic are unchanged. Only Vitest runs are requested, with `TZ=UTC`.

Command: `TZ=UTC TMPDIR=<private temporary> node <adapted official runner> --out <this directory>/rules.json --before 1a4ec4a551db33f3dc2cb536b5eb437875ef86dd --before-root <private temporary> --check <each of the receipt's 28 ids>`.

## Per-check before result

- Shell — settings window close semantics (tempdoc 855 §11.1 D3/D4) WP3.8b routes hours to Settings once without replay on Back and Forward: assertion — rule focus destination exists: expected false to be true // Object.is equality
- Shell — settings window close semantics (tempdoc 855 §11.1 D3/D4) WP3.8b routes battery to Settings once without replay on Back and Forward: assertion — rule focus destination exists: expected false to be true // Object.is equality
- WP3.8b rules editor S38.1 S38.7 loads labelled days and native locale times and saves both rules retaining disabled hours: assertion — expected [] to deeply equal [ 'Monday', 'Tuesday', …(5) ]
- WP3.8b rules editor S38.2 announces equal endpoints and retains the refused draft: assertion — control input[type=time] is mounted: expected null not to be null
- WP3.8b rules editor S38.2 announces missing time and retains the refused draft: assertion — control input[type=time] is mounted: expected null not to be null
- WP3.8b rules editor S38.2 refuses enabled hours without days and accepts the retained empty set when off: assertion — control .indexing-days label:nth-child(1) input is mounted: expected null not to be null
- WP3.8b rules editor S38.2 retains the draft and announces Engine 400 without stale replay: assertion — control input[type=time] is mounted: expected null not to be null
- WP3.8b rules editor S38.2 retains the draft and announces Engine 409 without stale replay: assertion — control input[type=time] is mounted: expected null not to be null
- WP3.8b rules editor S38.2 retains the draft and announces Engine 200 without stale replay: assertion — control input[type=time] is mounted: expected null not to be null
- WP3.8b rules editor S38.7 waits for saved rules before editing or applying deferred rule focus: assertion — control #indexing-hours is mounted: expected null not to be null
- WP3.8b rules editor S38.7 keeps read-only rules readable without issuing a save: assertion — control #indexing-hours is mounted: expected null not to be null
- WP3.8b rules editor S38.7 preserves Save focus while pending and sends one unchanged rule pair: assertion — action Save indexing rules is mounted: expected undefined to be defined
- WP3.8b rules editor D4 preserves both rules through approval and uses the approved preparation key: assertion — rules form is mounted: expected null not to be null
- WP3.8b rules editor D4 changing battery preserves an absent schedule until the hours are edited: assertion — control #indexing-battery is mounted: expected null not to be null
- WP3.8b rules editor D6 keeps battery available with unknown power and explains the rule: assertion — control #indexing-battery is mounted: expected null not to be null
- WP3.8b rules editor S38.7 focuses hours in the indexing category: assertion — indexing focus route exists: expected false to be true // Object.is equality
- WP3.8b rules editor S38.7 focuses battery in the indexing category: assertion — indexing focus route exists: expected false to be true // Object.is equality
- WP3.8b policy status and actions D5 PAUSING schedule names the rule with an empty queue and offers its Settings action: assertion — expected '\n      \n        \n          \n     …' to contain 'Outside indexing hours.'
- WP3.8b policy status and actions D5 PAUSED schedule names the rule with an empty queue and offers its Settings action: assertion — expected '\n      \n        \n          \n     …' to contain 'Outside indexing hours.'
- WP3.8b policy status and actions D5 PAUSING on_battery names the rule with an empty queue and offers its Settings action: assertion — expected '\n      \n        \n          \n     …' to contain 'Paused while this computer is running…'
- WP3.8b policy status and actions D5 PAUSED on_battery names the rule with an empty queue and offers its Settings action: assertion — expected '\n      \n        \n          \n     …' to contain 'Paused while this computer is running…'
- WP3.8b policy status and actions D5 orders combined reasons and restricts recovery actions: assertion — expected -1 to be less than -1
- WP3.8b policy status and actions D5 names every folder rule without Waiting for Resume and preserves failure evidence: assertion — expected 'default · Waiting for Resume · 3 rema…' not to contain 'Waiting for Resume'
- WP3.8b policy status and actions S38.5 Resume clears manual only and announces both remaining rules after confirmed status: assertion — expected '\n      \n      Resume indexing\n    …' to contain 'Change hours'
- WP3.8b policy status and actions S38.5 waits for manual-pause removal before announcing it cleared: assertion — expected '' to contain 'Waiting for status'
- WP3.8b policy status and actions D5 does not invoke a focused Resume when manual pause clears leaving hours: assertion — expected 'false' to be 'true' // Object.is equality
- WP3.8b policy status and actions D5 jf-library-surface names schedule holds for queued work: assertion — expected '\n      \n        \n      \n      \n …' to contain 'Outside indexing hours'
- WP3.8b policy status and actions D5 jf-task-list names schedule holds for queued work: assertion — expected '\n      \n        \n          Indexin…' to contain 'Outside indexing hours'
