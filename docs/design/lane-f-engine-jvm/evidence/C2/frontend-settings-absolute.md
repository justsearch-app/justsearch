# Absolute settings callers (2026-09-14)

C2-6 item3b migrates Settings appearance/interface and context override, Brain mode
and LLM fields, Shell mode, and the public settings domain API. The three mode
controls use enqueueUiModeSettings; independent absolute field intents use a fresh
observation captured after freezing their patch. The public API requires the caller's
observed witness explicitly, so it cannot silently attach a new witness to an old
derived document. Library and legacy contrast migration remain item3c.

Existing transports retain authentication ownership. Every save awaits COMPLETE;
HTTP202 cannot report success. Brain LLM fields update only the submitted keys,
using the first completion's normalized projection when present. Receipt-only
replay does not populate unrelated fields. Blank paths now send empty string to
clear them; null means preserve at the backend. Context/token/GPU numeric inputs
use integer values and existing backend bounds. Shell now reports persistence
failure through its existing notification port instead of swallowing it.

Independent read-only production review is clear at531128d11 plus the four caller
files. Root added five domain/Brain regressions proving retained-base conflict,
receipt-only completion, narrow normalization, and an open row remaining uncommitted.
The worker migrated only five existing surface test fixtures; it reported51 passes
but supplied no retained raw output. Root therefore relies on its own full1468:
488 suites,6,557 tests, zero failures/skips. Existing localhost:3000 connection-refused
stderr is present; it is not live backend evidence. Typecheck1468 and focused
ESLint1469 pass. Root corrected four worker fixture typing errors exposed by1467;
the production1462 notification-option typo was corrected before1463. No validation
or test intent was weakened.

UI capture1466 is **failed proof**: settings/light PNGs were written but measurements
show the Search heading, and five dependent density/search selectors timed out.
The CLI exited zero despite those FAIL lines; exit code is not acceptance. Root is
investigating navigation/fixture ownership and will repair and rerun the captures
in the remaining frontend verification item. Source changes are not yet accepted
as visually or live verified. The command's first invocation1465 also used multiple
positional paths unsupported by --affected;1466 used one valid path.

Exact source hashes, commands and raw report paths are in the adjacent manifest.
Raw evidence is in active lane-f-pr1-verify/tmp, retained through lane acceptance
plus30 days and exported before release. No stage closes here: item3c, no-bypass
retirement, integrated governance/live/model/installed/final-head hosted proof and
later C2/D1/D2/E/F work remain. Merge placement stays F.
