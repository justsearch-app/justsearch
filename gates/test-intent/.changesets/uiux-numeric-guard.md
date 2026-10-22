---
schema: test-intent.v1
task: t-20261019-bd718f
author-role: builder
author-session: uiux-numeric-guard-nx3a
---

# NX3a literal table (recorded before implementation)

Observed start 2026-10-22 13:10:33 UTC; estimate 1–2 h; return by 16:10:33 UTC
(the assignment's 3 h deadline). Before-state e9f080f32.

Source (agent-drafted, owner-adopted), clarification NX3a:
“Before reading numbers on either side, normalise compatibility forms (Unicode NFKC: full-width digits and punctuation such as "：", "，", "．" become their plain forms) and the Arabic separators (U+066B ARABIC DECIMAL SEPARATOR is a decimal point, U+066C ARABIC THOUSANDS SEPARATOR is a group separator). Then NX3 applies unchanged: "1٫5" is the decimal 1.5 (5 is not in it), "1：30" is a time and is skipped, "5，000" is the grouped 5000.”
“A grouped number is read the same whatever follows it after whitespace: "1,200 5kg bags" contains 1200 (and a non-plain 5kg).”

This table overrides the historical NX3 rows for Arabic/full-width separators and
compatibility characters (including superscript digits, which NFKC turns into digits).
Eligible means the existing scorer verdict remains eligible, never a granted match.

| ID | Claim literal | Passage literal | Claim handling | Eligible |
| --- | --- | --- | --- | --- |
| NG1/NG3/NG7 | The voltage measured 5 volts. | The voltage measured 1٫5 volts. | plain-checked: 5 | Reject |
| NG1/NG3/NG7 | The voltage measured 1.5 volts. | The voltage measured 1٫5 volts. | plain-checked: 1.5 | Accept |
| NG1/NG3/NG7 | The voltage measured ١٫٥ volts. | The voltage measured 1.5 volts. | plain-checked: 1.5 | Accept |
| NG1/NG3/NG7 | The voltage measured ١٫٥ volts. | The voltage measured 5 volts. | plain-checked: 1.5 | Reject |
| NG1/NG3/NG7 | The voltage measured 5 volts. | The voltage measured ١٫٥ volts. | plain-checked: 5 | Reject |
| NG1/NG3/NG7 | The voltage measured 5 volts. | The voltage measured ٫5 volts. | plain-checked: 5 | Reject |
| NG1/NG3/NG7 | The voltage measured 5 volts. | The voltage measured １．５ volts. | plain-checked: 5 | Reject |
| NG1/NG3/NG7 | The voltage measured １．５ volts. | The voltage measured 1.5 volts. | plain-checked: 1.5 | Accept |
| NG1/NG3/NG7 | The voltage measured １．５ volts. | The voltage measured 5 volts. | plain-checked: 1.5 | Reject |
| NG1/NG3/NG7 | The voltage measured 1.5 volts. | The voltage measured １．５ volts. | plain-checked: 1.5 | Accept |
| NG1/NG3/NG7 | The meeting starts at 1：30. | No number here. | skipped | Accept |
| NG1/NG3/NG7 | The meeting starts at １：３０. | No number here. | skipped | Accept |
| NG1/NG3/NG7 | The shipment contains 5，000 bags. | The shipment contains 5000 bags. | plain-checked: 5000 | Accept |
| NG1/NG3/NG7 | The shipment contains 5，000 bags. | No number here. | plain-checked: 5000 | Reject |
| NG1/NG3/NG7 | The shipment contains ٥٬٠٠٠ bags. | The shipment contains 5000 bags. | plain-checked: 5000 | Accept |
| NG1/NG3/NG7 | The shipment contains 5000 bags. | The shipment contains ٥٬٠٠٠ bags. | plain-checked: 5000 | Accept |
| NG1/NG3/NG7 | The shipment contains 5000 bags. | The shipment contains 5，000 bags. | plain-checked: 5000 | Accept |
| NG1/NG3/NG7 | The shipment contains 200 bags. | The shipment contains 1٬200 bags. | plain-checked: 200 | Reject |
| NG1/NG3/NG7 | The shipment contains 1200 bags. | The shipment contains 1,200 5kg bags. | plain-checked: 1200 | Accept |
| NG1/NG3/NG7 | The shipment contains 1200 bags. | The shipment contains 1,200 50kg bags. | plain-checked: 1200 | Accept |
| NG1/NG3/NG7 | The shipment contains 1200 bags. | The shipment contains 1,200 500kg bags. | plain-checked: 1200 | Accept |
| NG1/NG3/NG7 | The shipment contains 1200 bags. | The shipment contains 1 200 5kg bags. | plain-checked: 1200 | Accept |
| NG1/NG3/NG7 | The shipment contains 1200 bags. | The shipment contains 1 200 5kg bags. | plain-checked: 1200 | Accept |
| NG1/NG3/NG7 | The shipment contains 1200 bags. | The shipment contains 1 200 5kg bags. | plain-checked: 1200 | Accept |
| NG1/NG3/NG7 | The shipment contains 1200 bags. | The shipment contains 1٬200 5kg bags. | plain-checked: 1200 | Accept |
| NG1/NG3/NG7 | The shipment contains 1200 bags. | The shipment contains １，２００ 5kg bags. | plain-checked: 1200 | Accept |
| NG1/NG3/NG7 | The shipment contains 1,200 5kg bags. | The shipment contains 1200 bags. | plain-checked: 1200 | Accept |
| NG1/NG3/NG7 | The shipment contains 1,200 5kg bags. | No number here. | plain-checked: 1200 | Reject |
| NG1/NG3/NG7 | The shipment contains 200 bags. | The shipment contains 1,200 5kg bags. | plain-checked: 200 | Reject |
| NG1/NG3/NG7 | The shipment contains 1200 bags. | The shipment contains 1,200.5 5kg bags. | plain-checked: 1200 | Reject |
| NG1/NG3/NG7 | The shipment contains 1200.5 bags. | The shipment contains 1,200.5 5kg bags. | plain-checked: 1200.5 | Accept |
| NG1/NG3/NG7 | The value is 5². | The value is 52. | plain-checked: 52 | Accept |
| NG1/NG3/NG7 | The value is 5². | The value is 5. | plain-checked: 52 | Reject |
| NG3/NG7 | The value is ١٬٥٠٠. | The value is 1500. | plain-checked: 1500 | Accept |
| NG3/NG7 | The value is 1 500. | The value is 1500. | plain-checked: 1500 | Accept |
| NG3/NG7 | The value is 1 500. | The value is 1500. | plain-checked: 1500 | Accept |
| NG3/NG7 | The value is 1․5. | The value is 1.5. | plain-checked: 1.5 | Accept |
| NG1/NG3/NG7 | The voltage is ١٫٥. | The values are 1 and 5. | plain-checked: 1.5 | Reject |
| NG1/NG3/NG7 | القيمة ١٫٥. | Values are 1, 2, 3, 5 and -5 and 5%. | plain-checked: 1.5 | Reject |

# NX3 literal table (recorded before implementation)

Started 2026-10-22 11:16 CEST; estimate 2–4 h; return at 17:16 CEST (the brief’s 6 h limit).
Before 062011106. Full quantity/sign/date checking is shelved: a future separate task would
first evaluate maintained Java recognizers and their shipping licenses.

Source: NX3 (owner decision, 2026-10-22): “The guard checks plain numbers only.”
“A number touching anything else (a letter, unit, currency sign, percent, degree sign, plus or minus, slash, dash, colon, another digit group, a second decimal point) is not plain: it is skipped. It never withholds a verdict and never counts as checked.”
“The passage side is generous: a claim's plain number counts as present when the passage contains that same value as a whole number anywhere, whatever touches it there (in a unit, range, date, currency, sign or percent).”
Eligible means the numeric guard permits scoring's existing verdict, never that the pair is automatically verified.

| ID | Claim literal | Passage literal | Claim handling | Eligible |
| --- | --- | --- | --- | --- |
| NG1 | Vespera Observatory opened in 2099. | Vespera Observatory opened in 1883. | plain-checked: 2099 | Reject |
| NG1 | Vespera Observatory opened in 1923. | Vespera Observatory opened in 1883. | plain-checked: 1923 | Reject |
| NG3 | The value is 99. | The value is 2099. | plain-checked: 99 | Reject |
| NG3 | The value is 1.5. | The value is 15. | plain-checked: 1.5 | Reject |
| NG3 | The value is 1,200. | The value is 1200. | plain-checked: 1200 | Accept |
| NG7 | افتتح المرصد في ٢٠٩٩. | Opened in 1883. | plain-checked: 2099 | Reject |
| X1 | The rate is %-5. | The rate is 5. | skipped | Accept |
| X1 | Oran %-5. | Oran 5. | skipped | Accept |
| X1 | The rate is % -5. | The rate is -5. | skipped | Accept |
| X2 | The value is 0x10. | The values are 0 and 10. | skipped | Accept |
| X3 | The opening date is 21/10/2026. | The opening date is 21/10/2026. | skipped | Accept |
| X3 | The accepted range is 5kg-10kg. | The accepted range is 5kg-10kg. | skipped | Accept |
| X3 | The area is measured in m². | The area is measured in m². | skipped | Accept |
| X3 | Vespera Observatory opened in 1883. | Vespera Observatory opened in 1883. Admission costs $5. | plain-checked: 1883 | Accept |
| X4 | Admission costs $5. | Admission costs $5. | skipped | Accept |
| X4 | The meeting starts at 1:30. | The meeting starts at 1:30. | skipped | Accept |
| X4 | The installed version is v1.2.3. | The installed version is v1.2.3. | skipped | Accept |
| Y1 | The accepted range is -10 kg. | The accepted range is 5 kg -10 kg. | skipped | Accept |
| Y2 | The price is 15. | The price is 1$5. | plain-checked: 15 | Reject |
| Y3 | The value is 1. | The value is 1.2.3e4. | plain-checked: 1 | Reject |
| Y3 | The value is 1. | The value is 1:30e4. | plain-checked: 1 | Accept (whole number beside colon) |
| Y4 | The value is 5kg%. | The value is 5. | skipped | Accept |
| Y4 | The value is 5. | The value is 5kg%. | plain-checked: 5 | Accept |

Boundary interpretation: apply the explicit whitespace-boundary definition. The touching-only
exclusion skips `5kg`, `$5`, `5%` and `5-10`; whitespace-separated `5 kg`, `$ 5`, `5 %`
and `5 - 10` retain their standalone digit runs. The builder asked for clarification;
this is the stated formal-rule interpretation pending any correction, not an additional quantity recognizer.

| ID | Claim literal | Passage literal | Claim handling | Eligible |
| --- | --- | --- | --- | --- |
| Y1 | The accepted range is 5 kg - 10 kg. | The accepted range is 5 kg - 10 kg. | plain-checked: 5, 10 | Accept |
| Y1 | The accepted range is 5 kg - 10 kg. | The accepted range is 5 and 10 kg. | plain-checked: 5, 10 | Accept |
| NG4 | `15. literal` | The value is 15. | plain-checked: 15 (code, not a list ordinal) | Accept |
| GN1/NG3 | The value is 15... | The value is 15. | plain-checked: 15 | Accept |
| NG3 | The value is 5 kg. | No number here. | plain-checked: 5 | Reject |
| NG3 | The value is $ 5. | No number here. | plain-checked: 5 | Reject |
| NG3 | The value is 5 %. | No number here. | plain-checked: 5 | Reject |
| NG3 | The label is 5: good. | No number here. | plain-checked: 5 | Reject |
| NG3 | The range is 5 - 10. | Only 5. | plain-checked: 5, 10 | Reject |
| NG3 | The value is 12,34. | No number here. | skipped | Accept |
| NG3 | The values are 1 2. | No number here. | skipped | Accept |

The old NX2/NX1/D8 tables below are historical. NX3 replaces their form decisions wherever
they conflict. Compatible numeric-free, reference, threshold, scored-count and reselection
expectations retain their existing intent; the old sign/quantity/unsupported-form vetoes do not.




Additional literal NX3 pairs:

| ID | Claim literal | Passage literal | Claim handling | Eligible |
| --- | --- | --- | --- | --- |
| NX3 | The value is %-5. | No number here. | skipped | Accept |
| NX3 | The value is % -5. | No number here. | skipped | Accept |
| NX3 | The value is %5. | No number here. | skipped | Accept |
| NX3 | The value is 0x10. | No number here. | skipped | Accept |
| NX3 | The value is 0b101. | No number here. | skipped | Accept |
| NX3 | The value is 5kg-10kg. | No number here. | skipped | Accept |
| NX3 | The value is 21/10/2026. | No number here. | skipped | Accept |
| NX3 | The value is 2026-10-21. | No number here. | skipped | Accept |
| NX3 | The value is 21.10.2026. | No number here. | skipped | Accept |
| NX3 | The value is $5. | No number here. | skipped | Accept |
| NX3 | The value is -$5. | No number here. | skipped | Accept |
| NX3 | The value is $-5. | No number here. | skipped | Accept |
| NX3 | The value is 5€. | No number here. | skipped | Accept |
| NX3 | The value is 1:30. | No number here. | skipped | Accept |
| NX3 | The value is v1.2.3. | No number here. | skipped | Accept |
| NX3 | The value is 1.2.3e4. | No number here. | skipped | Accept |
| NX3 | The value is 1e3. | No number here. | skipped | Accept |
| NX3 | The value is 5kg%. | No number here. | skipped | Accept |
| NX3 | The value is -15. | No number here. | skipped | Accept |
| NX3 | The value is +15. | No number here. | skipped | Accept |
| NX3 | The value is 15%. | No number here. | skipped | Accept |
| NX3 | The value is 5°. | No number here. | skipped | Accept |
| NX3 | The value is .5. | No number here. | skipped | Accept |
| NX3 | The value is -.5. | No number here. | skipped | Accept |
| NX3 | The value is +.5%. | No number here. | skipped | Accept |
| NX3 | The value is 1/2. | No number here. | skipped | Accept |
| NX3 | The value is 1..5. | No number here. | skipped | Accept |
| NX3 | The value is 1_500. | No number here. | skipped | Accept |
| NX3 | The value is 5th. | No number here. | skipped | Accept |
| NX3 | The value is 5². | No number here. | skipped | Accept |
| NX3 | The value is ١٫٥. | No number here. | skipped | Accept |
| NX3 | The value is 値は１５です. | No number here. | skipped | Accept |
| NX3 | The value is m². | No number here. | skipped | Accept |
| NX3 | The value is cm³. | No number here. | skipped | Accept |
| NX3 | The value is 0.5. | The value is .5. | plain-checked: 0.5 | Accept |
| NX3 | The value is 15. | The value is -15. | plain-checked | Accept |
| NX3 | The value is 15. | The value is 15%. | plain-checked | Accept |
| NX3 | The value is 5. | The value is 5kg%. | plain-checked | Accept |
| NX3 | The value is 5. | The value is $5. | plain-checked | Accept |
| NX3 | The value is 10. | The range is 5kg-10kg. | plain-checked | Accept |
| NX3 | The value is 21. | The date is 21/10/2026. | plain-checked | Accept |
| NX3 | The value is 1. | The time is 1:30e4. | plain-checked | Accept |
| NX3 | The value is 10. | The value is 0x10. | plain-checked | Accept |
| NX3 | The value is 1200. | The value is $1,200%. | plain-checked | Accept |
| NX3 | The value is 99. | The value is 2099. | plain-checked | Reject |
| NX3 | The value is 15. | The value is 1.5. | plain-checked | Reject |
| NX3 | The value is 1.5. | The value is 15. | plain-checked | Reject |
| NX3 | The price is 15. | The price is 1$5. | plain-checked | Reject |
| NX3 | The value is 1. | The value is 1.2.3e4. | plain-checked | Reject |
| NX3 | The value is 5. | The value is .5. | plain-checked | Reject |
| NX3 | The value is 200. | The value is 1,200. | plain-checked | Reject |
| NX3 | Opened in 1883 with a rate of 5kg%. | Opened in 18830. | plain-checked | Reject |
| NG3/NG7 | The value is 15. | The value is 15. | plain-checked: 15 | Accept |
| NG3/NG7 | The value is 0015. | The value is 15. | plain-checked: 15 | Accept |
| NG3/NG7 | The value is 1.5. | The value is 1.5. | plain-checked: 1.5 | Accept |
| NG3/NG7 | The value is 1.50. | The value is 1.50. | plain-checked: 1.50 | Accept |
| NG3/NG7 | The value is 1,500. | The value is 1500. | plain-checked: 1500 | Accept |
| NG3/NG7 | The value is 1 500. | The value is 1500. | plain-checked: 1500 | Accept |
| NG3/NG7 | The value is 1 500. | The value is 1500. | plain-checked: 1500 | Accept |
| NG3/NG7 | The value is 1 500. | The value is 1500. | plain-checked: 1500 | Accept |
| NG3/NG7 | The value is 1,500,000. | The value is 1500000. | plain-checked: 1500000 | Accept |
| NG3/NG7 | The value is ١٥. | The value is 15. | plain-checked: 15 | Accept |
| NG3/NG7 | The value is １５. | The value is 15. | plain-checked: 15 | Accept |
| NG3/NG7 | The value is 𝟙𝟝. | The value is 15. | plain-checked: 15 | Accept |

# NX2 repair contract recorded before tests and implementation

Started 2026-10-21 20:24 CEST; estimate 1–2 hours; return for a decision at
2026-10-22 00:24 CEST. Baseline d36738d92. NX1 and NX2 are **agent choice**:
orchestrator decisions under the user's delegation, not owner words.

NX2 supersedes historical NX1 table rows only for currency, colon parts,
three-part slash dates, dotted strings with at least two dots, unit ranges,
and unit exponents. Superseded rows below retain their inputs with current NX2 expectations and
the prior NX1 rejection noted; the NX2 table is the current oracle for those forms. All other rows remain required.
Only an undecided claim withholds. An undecided passage form supplies no
values; its presence cannot veto separate decided supporting values.

| Sentence (literal) | Passage (literal) | Eligible | Decision |
| --- | --- | --- | --- |
| The rate is %-5. | The rate is 5. | Reject | NX2 / NG1–NG3, NG7 |
| Oran %-5. | Oran 5. | Reject | NX2 / NG1–NG3, NG7 |
| The rate is % -5. | The rate is -5. | Reject | NX2 / NG1–NG3, NG7 |
| The rate is %5. | The rate is 5. | Reject | NX2 / NG1–NG3, NG7 |
| The rate is %-5. | The rate is %-5. | Reject | NX2 / NG1–NG3, NG7 |
| The rate is % -5. | The rate is % -5. | Reject | NX2 / NG1–NG3, NG7 |
| The value is 0x10. | The values are 0 and 10. | Reject | NX2 / NG1–NG3, NG7 |
| The value is 0x10. | The value is 0x10. | Reject | NX2 / NG1–NG3, NG7 |
| The value is 0. | The value is 0x10. | Reject | NX2 / NG1–NG3, NG7 |
| The value is 10. | The value is 0x10. | Reject | NX2 / NG1–NG3, NG7 |
| The value is 1883 and 0x10. | The values are 1883, 0 and 10. | Reject | NX2 / NG1–NG3, NG7 |
| The date is 21/10/2026. | The parts are 21 and 2026. | Reject | NX2 / NG1–NG3, NG7 |
| The date is 21.10.2026. | The parts are 21 and 2026. | Reject | NX2 / NG1–NG3, NG7 |
| The fraction is 3/4. | The parts are 3 and 4. | Reject | NX2 / NG1–NG3, NG7 |
| The interval is 5kg-10kg. | The value is 5 kg. | Reject | NX2 / NG1–NG3, NG7 |
| The price is $5. | The price is 15. | Reject | NX2 / NG1–NG3, NG7 |
| The price is $-5. | The price is 5. | Reject | NX2 / NG1–NG3, NG7 |
| The time is 1:30. | The value is 1. | Reject | NX2 / NG1–NG3, NG7 |
| The version is v1.2.3. | The values are 1 and 2. | Reject | NX2 / NG1–NG3, NG7 |
| The value is 1.5. | The values are 1 and 5. | Reject | NX2 / NG1–NG3, NG7 |
| Opened in 1883. | Opened in 18830; the value is 0x10. | Reject | NX2 / NG1–NG3, NG7 |
| The area is 5m². | The area is 50 m². | Reject | NX2 / NG1–NG3, NG7 |
| The date is 21/10/2026. | The date is 21/10/2026. | Accept | NX2 / NG1–NG3, NG7 |
| The date is 21/10/2026. | The parts are 21, 10 and 2026. | Accept | NX2 / NG1–NG3, NG7 |
| The date is 2026-10-21. | The parts are 2026, 10 and 21. | Accept | NX2 / NG1–NG3, NG7 |
| The date is 21.10.2026. | The parts are 21, 10 and 2026. | Accept | NX2 / NG1–NG3, NG7 |
| The date is ٢٠٢٦/١٠/٢١. | The parts are 2026, 10 and 21. | Accept | NX2 / NG1–NG3, NG7 |
| The interval is 5kg-10kg. | The interval is 5kg-10kg. | Accept | NX2 / NG1–NG3, NG7 |
| The interval is 5kg-10kg. | The values are 5 and 10. | Accept | NX2 / NG1–NG3, NG7 |
| The interval is 5–10 kg. | The values are 5 and 10. | Accept | NX2 / NG1–NG3, NG7 |
| The interval is 5°-10°. | The values are 5 and 10. | Accept | NX2 / NG1–NG3, NG7 |
| The price is $5. | The price is $5. | Accept | NX2 / NG1–NG3, NG7 |
| The price is $5. | The value is 5. | Accept | NX2 / NG1–NG3, NG7 |
| The price is 5 €. | The value is 5. | Accept | NX2 / NG1–NG3, NG7 |
| The price is € 5. | The value is 5. | Accept | NX2 / NG1–NG3, NG7 |
| The price is -$5. | The value is -5. | Accept | NX2 / NG1–NG3, NG7 |
| The price is $-5. | The value is -5. | Accept | NX2 / NG1–NG3, NG7 |
| The time is 1:30. | The time is 1:30. | Accept | NX2 / NG1–NG3, NG7 |
| The time is 1:30. | The parts are 1 and 30. | Accept | NX2 / NG1–NG3, NG7 |
| The version is v1.2.3. | The version is v1.2.3. | Accept | NX2 / NG1–NG3, NG7 |
| The version is v1.2.3. | The parts are 1, 2 and 3. | Accept | NX2 / NG1–NG3, NG7 |
| The value is 1.2.3. | The parts are 1, 2 and 3. | Accept | NX2 / NG1–NG3, NG7 |
| The area is 5m². | The value is 5. | Accept | NX2 / NG1–NG3, NG7 |
| Opened in 1883. | Opened in 1883; admission costs $5. | Accept | NX2 / NG1–NG3, NG7 |
| Opened in 1883. | Opened in 1883; the value is 0x10. | Accept | NX2 / NG1–NG3, NG7 |
| Opened in 1883. | The value is 0x10; opened in 1883. | Accept | NX2 / NG1–NG3, NG7 |
| Opened in 1883. | Opened in 1883; the fraction is 3/4. | Accept | NX2 / NG1–NG3, NG7 |
| Opened in 1883. | Opened in 1883; the rate is %-5. | Accept | NX2 / NG1–NG3, NG7 |
| Opened in 1883. | Opened in 1883; the value is 1e3. | Accept | NX2 / NG1–NG3, NG7 |
| Opened in 1883. | Opened in 1883; the value is --5. | Accept | NX2 / NG1–NG3, NG7 |
| The value is 5. | The value is 0x10; a separate value is 5. | Accept | NX2 / NG1–NG3, NG7 |
| The value is 5. | The rate is %-5; a separate value is 5. | Accept | NX2 / NG1–NG3, NG7 |
| The area is measured in m². | Some passage. | Accept | NX2 / GN1 |
| Volume is measured in cm³. | Some passage. | Accept | NX2 / GN1 |

Additional NX2 boundary cases recorded before their checks:

| Sentence (literal) | Passage (literal) | Eligible | Decision |
| --- | --- | --- | --- |
| The interval is 5 kg–10 kg. | The values are 5 and 10. | Accept | NX2 |
| The interval is 10 - 20. | The values are 10 and 20. | Accept | NX2 |
| The interval is 10– 20. | The values are 10 and 20. | Accept | NX2 |
| The interval is -5 - -3. | The values are -5 and -3. | Accept | NX2 |
| Opened in 1883. | The value is 0x10,1883. | Accept | NX2 |
| Opened in 1883. | The value is 1883,0x10. | Accept | NX2 |
| Opened in 1883. | The value is 0x10 1883. | Accept | NX2 |
| Opened in 1883. | The value is 1883 0x10. | Accept | NX2 |
| The date is 1/234/567. | The parts are 1, 234 and 567. | Accept | NX2 |
| The value is 0b101. | The values are 0 and 101. | Reject | NX2 |
| The value is 101. | The value is 0b101. | Reject | NX2 |
| The rate is ％-٥. | The rate is 5. | Reject | NX2 |
| The value is 1. | The value is 1e3. | Reject | NX2 |
| The value is 3. | The value is 1e3. | Reject | NX2 |
| The value is 1. | The value is 1/2. | Reject | NX2 |
| The value is 2. | The value is 1/2. | Reject | NX2 |

NX2 replacements for every superseded NX1 row (the other rows remain unchanged):

| Sentence (literal) | Passage (literal) | Eligible | Decision |
| --- | --- | --- | --- |
| The value is $5. | The value is 1, 2, 3, 5, 10, 21, 500 and -5 and 5% and -3. | Accept | NX2 supersedes NX1 |
| The value is $5. | The value is $5. | Accept | NX2 supersedes NX1 |
| The value is 5. | The value is $5. | Accept | NX2 supersedes NX1 |
| The value is -$5. | The value is 1, 2, 3, 5, 10, 21, 500 and -5 and 5% and -3. | Accept | NX2 supersedes NX1 |
| The value is -$5. | The value is -$5. | Accept | NX2 supersedes NX1 |
| The value is 5. | The value is -$5. | Reject | NX2 supersedes NX1 |
| The value is $-5. | The value is 1, 2, 3, 5, 10, 21, 500 and -5 and 5% and -3. | Accept | NX2 supersedes NX1 |
| The value is $-5. | The value is $-5. | Accept | NX2 supersedes NX1 |
| The value is 5. | The value is $-5. | Reject | NX2 supersedes NX1 |
| The value is 5€. | The value is 1, 2, 3, 5, 10, 21, 500 and -5 and 5% and -3. | Accept | NX2 supersedes NX1 |
| The value is 5€. | The value is 5€. | Accept | NX2 supersedes NX1 |
| The value is 5. | The value is 5€. | Accept | NX2 supersedes NX1 |
| The value is € 5. | The value is 1, 2, 3, 5, 10, 21, 500 and -5 and 5% and -3. | Accept | NX2 supersedes NX1 |
| The value is € 5. | The value is € 5. | Accept | NX2 supersedes NX1 |
| The value is 5. | The value is € 5. | Accept | NX2 supersedes NX1 |
| The value is 1:5. | The value is 1, 2, 3, 5, 10, 21, 500 and -5 and 5% and -3. | Accept | NX2 supersedes NX1 |
| The value is 1:5. | The value is 1:5. | Accept | NX2 supersedes NX1 |
| The value is 5. | The value is 1:5. | Accept | NX2 supersedes NX1 |
| The value is 1.2.3. | The value is 1, 2, 3, 5, 10, 21, 500 and -5 and 5% and -3. | Accept | NX2 supersedes NX1 |
| The value is 1.2.3. | The value is 1.2.3. | Accept | NX2 supersedes NX1 |
| The value is 5. | The value is 1.2.3. | Reject | NX2 supersedes NX1 |
| The value is 5kg-10kg. | The value is 1, 2, 3, 5, 10, 21, 500 and -5 and 5% and -3. | Accept | NX2 supersedes NX1 |
| The value is 5kg-10kg. | The value is 5kg-10kg. | Accept | NX2 supersedes NX1 |
| The value is 5. | The value is 5kg-10kg. | Accept | NX2 supersedes NX1 |
| The value is 5°-10°. | The value is 1, 2, 3, 5, 10, 21, 500 and -5 and 5% and -3. | Accept | NX2 supersedes NX1 |
| The value is 5°-10°. | The value is 5°-10°. | Accept | NX2 supersedes NX1 |
| The value is 5. | The value is 5°-10°. | Accept | NX2 supersedes NX1 |

D8 percentage spacing on range endpoints, recorded before adding preservation checks:

| Sentence (literal) | Passage (literal) | Eligible | Decision |
| --- | --- | --- | --- |
| The interval is 5 %-10%. | The values are 5% and 10%. | Accept | NX2 / D8 percentage spacing |
| The interval is 5 %--10%. | The values are 5% and -10%. | Accept | NX2 / D8 percentage spacing |

# NX1 repair contract recorded before implementation

Repair started 2026-10-21 14:38 CEST. Estimate 1–2 h; return for a decision
at 18:38 CEST (twice the upper estimate). Baseline: 3ec058b1e.

NX1 supersedes the historical explicit-plus rejection below: a plus sign is no
sign. Minus signs and percentage notation remain significant (F1). Leading
decimal points are normalized to a zero integer part, including normalized
non-Latin decimal digits. D8 still treats each range endpoint and date part
as a value. En/em dashes between numbers separate endpoints; parentheses and
adjacent sentence punctuation do not invent a sign. Slash-separated three-part
dates retain their parts; two-part slash notation is ambiguous with fractions.

Historical NX1 rationale (NX2 supersedes the currency, parts and unit-range
withholding described here): the sweep found no agreed equivalence for currency notation, separated/repeated
or dangling signs, repeated/prefix percentages, per-mille notation, other grouping
or decimal marks, repeated decimal points, exponents, fractions, lexical ordinals,
superscripts, powers, or standalone dash variants. NG2/NX1 withhold these forms
even against identical notation; no subvalue may support another claim. Currency
is withheld rather than guessing whether an intervening sign is unary. Ordinary
unit suffixes remain ignored under D8. The table lists each discovered form.

| Sentence (literal) | Passage (literal) | Eligible | Decision |
| --- | --- | --- | --- |
| The value is .5. | The value is 5. | Reject | NX1/F1 |
| The value is -.5. | The value is 5. | Reject | NX1/F1 |
| The value is +.5%. | The value is 5%. | Reject | NX1/F1 |
| The value is .5. | The value is -.5. | Reject | NX1/F1 |
| The value is .5%. | The value is .5. | Reject | NX1/F1 |
| The value is −.5. | The value is 5. | Reject | NX1/F1 |
| The value is .٥. | The value is 5. | Reject | NX1/F1 |
| The value is -.５%. | The value is 5%. | Reject | NX1/F1 |
| The value is .𝟝. | The value is 5. | Reject | NX1/F1 |
| The value is .5-.7. | The value is 5 and 7. | Reject | NX1/F1 |
| The value is -.5--.7. | The value is 5 and 7. | Reject | NX1/F1 |
| The value is 10-.5. | The value is 10 and 5. | Reject | NX1/F1 |
| The value is 5. | The value is 5%%. | Reject | NX1/F1 |
| The value is 5. | The value is .5. | Reject | NX1/F1 |
| The value is 5. | The value is -.5. | Reject | NX1/F1 |
| The value is 5%. | The value is +.5%. | Reject | NX1/F1 |
| The value is -.5. | The value is .5. | Reject | NX1/F1 |
| The value is .5. | The value is .5%. | Reject | NX1/F1 |
| The value is 5. | The value is −.5. | Reject | NX1/F1 |
| The value is 5. | The value is .٥. | Reject | NX1/F1 |
| The value is 5%. | The value is -.５%. | Reject | NX1/F1 |
| The value is 5. | The value is .𝟝. | Reject | NX1/F1 |
| The value is 5 and 7. | The value is .5-.7. | Reject | NX1/F1 |
| The value is 5 and 7. | The value is -.5--.7. | Reject | NX1/F1 |
| The value is 10 and 5. | The value is 10-.5. | Reject | NX1/F1 |
| The value is 5%%. | The value is 5. | Reject | NX1/F1 |
| The value is .5. | The value is 0.5. | Accept | NX1/D8 |
| The value is +.5%. | The value is 0.5%. | Accept | NX1/D8 |
| The value is -.5. | The value is -0.5. | Accept | NX1/D8 |
| The value is +15. | The value is 15. | Accept | NX1/D8 |
| The value is +000.5. | The value is .5. | Accept | NX1/D8 |
| The value is −.٥. | The value is -0.5. | Accept | NX1/D8 |
| The value is .𝟝. | The value is 0.5. | Accept | NX1/D8 |
| The value is -15. | The value is −15. | Accept | D8/F1 |
| The value is 15 %. | The value is 15%. | Accept | D8/F1 |
| The value is 0015 kg. | The value is 15 m. | Accept | D8/F1 |
| The value is 1,500. | The value is 1500. | Accept | D8/F1 |
| The value is 1 500. | The value is 1500. | Accept | D8/F1 |
| The value is 1 500. | The value is 1500. | Accept | D8/F1 |
| The value is 1 500. | The value is 1500. | Accept | D8/F1 |
| The value is 12,34. | The value is 12 and 34. | Accept | D8/F1 |
| The value is 10-20. | The value is 10 and 20. | Accept | D8/F1 |
| The value is 10–20. | The value is 10 and 20. | Accept | D8/F1 |
| The value is 10—20. | The value is 10 and 20. | Accept | D8/F1 |
| The value is 2026-10-21. | The value is 2026 and 10 and 21. | Accept | D8/F1 |
| The value is 2026/10/21. | The value is 2026 and 10 and 21. | Accept | D8/F1 |
| The value is (15), [15, 20]; 15!. | The value is 15 and 20. | Accept | D8/F1 |
| The value is ١٥. | The value is 15. | Accept | D8/F1 |
| The value is １５. | The value is 15. | Accept | D8/F1 |
| The value is 𝟙𝟝. | The value is 15. | Accept | D8/F1 |
| The value is --5. | The value is 1, 2, 3, 5, 10, 21, 500 and -5 and 5% and -3. | Reject | NG2/NX1 (unagreed) |
| The value is --5. | The value is --5. | Reject | NG2/NX1 (unagreed) |
| The value is 5. | The value is --5. | Reject | NG2/NX1 (unagreed) |
| The value is ++5. | The value is 1, 2, 3, 5, 10, 21, 500 and -5 and 5% and -3. | Reject | NG2/NX1 (unagreed) |
| The value is ++5. | The value is ++5. | Reject | NG2/NX1 (unagreed) |
| The value is 5. | The value is ++5. | Reject | NG2/NX1 (unagreed) |
| The value is +-5. | The value is 1, 2, 3, 5, 10, 21, 500 and -5 and 5% and -3. | Reject | NG2/NX1 (unagreed) |
| The value is +-5. | The value is +-5. | Reject | NG2/NX1 (unagreed) |
| The value is 5. | The value is +-5. | Reject | NG2/NX1 (unagreed) |
| The value is -+5. | The value is 1, 2, 3, 5, 10, 21, 500 and -5 and 5% and -3. | Reject | NG2/NX1 (unagreed) |
| The value is -+5. | The value is -+5. | Reject | NG2/NX1 (unagreed) |
| The value is 5. | The value is -+5. | Reject | NG2/NX1 (unagreed) |
| The value is - .5. | The value is 1, 2, 3, 5, 10, 21, 500 and -5 and 5% and -3. | Reject | NG2/NX1 (unagreed) |
| The value is - .5. | The value is - .5. | Reject | NG2/NX1 (unagreed) |
| The value is 5. | The value is - .5. | Reject | NG2/NX1 (unagreed) |
| The value is + .5. | The value is 1, 2, 3, 5, 10, 21, 500 and -5 and 5% and -3. | Reject | NG2/NX1 (unagreed) |
| The value is + .5. | The value is + .5. | Reject | NG2/NX1 (unagreed) |
| The value is 5. | The value is + .5. | Reject | NG2/NX1 (unagreed) |
| The value is 5-. | The value is 1, 2, 3, 5, 10, 21, 500 and -5 and 5% and -3. | Reject | NG2/NX1 (unagreed) |
| The value is 5-. | The value is 5-. | Reject | NG2/NX1 (unagreed) |
| The value is 5. | The value is 5-. | Reject | NG2/NX1 (unagreed) |
| The value is 5+. | The value is 1, 2, 3, 5, 10, 21, 500 and -5 and 5% and -3. | Reject | NG2/NX1 (unagreed) |
| The value is 5+. | The value is 5+. | Reject | NG2/NX1 (unagreed) |
| The value is 5. | The value is 5+. | Reject | NG2/NX1 (unagreed) |
| The value is 5 % %. | The value is 1, 2, 3, 5, 10, 21, 500 and -5 and 5% and -3. | Reject | NG2/NX1 (unagreed) |
| The value is 5 % %. | The value is 5 % %. | Reject | NG2/NX1 (unagreed) |
| The value is 5. | The value is 5 % %. | Reject | NG2/NX1 (unagreed) |
| The value is 5%%. | The value is 1, 2, 3, 5, 10, 21, 500 and -5 and 5% and -3. | Reject | NG2/NX1 (unagreed) |
| The value is 5%%. | The value is 5%%. | Reject | NG2/NX1 (unagreed) |
| The value is 5. | The value is 5%%. | Reject | NG2/NX1 (unagreed) |
| The value is %5. | The value is 1, 2, 3, 5, 10, 21, 500 and -5 and 5% and -3. | Reject | NG2/NX1 (unagreed) |
| The value is %5. | The value is %5. | Reject | NG2/NX1 (unagreed) |
| The value is 5. | The value is %5. | Reject | NG2/NX1 (unagreed) |
| The value is $5. | The value is 1, 2, 3, 5, 10, 21, 500 and -5 and 5% and -3. | Accept | NX2 (supersedes NX1 Reject) |
| The value is $5. | The value is $5. | Accept | NX2 (supersedes NX1 Reject) |
| The value is 5. | The value is $5. | Accept | NX2 (supersedes NX1 Reject) |
| The value is -$5. | The value is 1, 2, 3, 5, 10, 21, 500 and -5 and 5% and -3. | Accept | NX2 (supersedes NX1 Reject) |
| The value is -$5. | The value is -$5. | Accept | NX2 (supersedes NX1 Reject) |
| The value is 5. | The value is -$5. | Reject | NG2/NX1 (unagreed) |
| The value is $-5. | The value is 1, 2, 3, 5, 10, 21, 500 and -5 and 5% and -3. | Accept | NX2 (supersedes NX1 Reject) |
| The value is $-5. | The value is $-5. | Accept | NX2 (supersedes NX1 Reject) |
| The value is 5. | The value is $-5. | Reject | NG2/NX1 (unagreed) |
| The value is 5€. | The value is 1, 2, 3, 5, 10, 21, 500 and -5 and 5% and -3. | Accept | NX2 (supersedes NX1 Reject) |
| The value is 5€. | The value is 5€. | Accept | NX2 (supersedes NX1 Reject) |
| The value is 5. | The value is 5€. | Accept | NX2 (supersedes NX1 Reject) |
| The value is € 5. | The value is 1, 2, 3, 5, 10, 21, 500 and -5 and 5% and -3. | Accept | NX2 (supersedes NX1 Reject) |
| The value is € 5. | The value is € 5. | Accept | NX2 (supersedes NX1 Reject) |
| The value is 5. | The value is € 5. | Accept | NX2 (supersedes NX1 Reject) |
| The value is 5‰. | The value is 1, 2, 3, 5, 10, 21, 500 and -5 and 5% and -3. | Reject | NG2/NX1 (unagreed) |
| The value is 5‰. | The value is 5‰. | Reject | NG2/NX1 (unagreed) |
| The value is 5. | The value is 5‰. | Reject | NG2/NX1 (unagreed) |
| The value is 5‱. | The value is 1, 2, 3, 5, 10, 21, 500 and -5 and 5% and -3. | Reject | NG2/NX1 (unagreed) |
| The value is 5‱. | The value is 5‱. | Reject | NG2/NX1 (unagreed) |
| The value is 5. | The value is 5‱. | Reject | NG2/NX1 (unagreed) |
| The value is 1'500. | The value is 1, 2, 3, 5, 10, 21, 500 and -5 and 5% and -3. | Reject | NG2/NX1 (unagreed) |
| The value is 1'500. | The value is 1'500. | Reject | NG2/NX1 (unagreed) |
| The value is 5. | The value is 1'500. | Reject | NG2/NX1 (unagreed) |
| The value is 1’500. | The value is 1, 2, 3, 5, 10, 21, 500 and -5 and 5% and -3. | Reject | NG2/NX1 (unagreed) |
| The value is 1’500. | The value is 1’500. | Reject | NG2/NX1 (unagreed) |
| The value is 5. | The value is 1’500. | Reject | NG2/NX1 (unagreed) |
| The value is 1_500. | The value is 1, 2, 3, 5, 10, 21, 500 and -5 and 5% and -3. | Reject | NG2/NX1 (unagreed) |
| The value is 1_500. | The value is 1_500. | Reject | NG2/NX1 (unagreed) |
| The value is 5. | The value is 1_500. | Reject | NG2/NX1 (unagreed) |
| The value is ١٬٥٠٠. | The value is 1, 2, 3, 5, 10, 21, 500 and -5 and 5% and -3. | Reject | NG2/NX1 (unagreed) |
| The value is ١٬٥٠٠. | The value is ١٬٥٠٠. | Reject | NG2/NX1 (unagreed) |
| The value is 5. | The value is ١٬٥٠٠. | Reject | NG2/NX1 (unagreed) |
| The value is ١٫٥. | The value is 1, 2, 3, 5, 10, 21, 500 and -5 and 5% and -3. | Reject | NG2/NX1 (unagreed) |
| The value is ١٫٥. | The value is ١٫٥. | Reject | NG2/NX1 (unagreed) |
| The value is 5. | The value is ١٫٥. | Reject | NG2/NX1 (unagreed) |
| The value is １．５. | The value is 1, 2, 3, 5, 10, 21, 500 and -5 and 5% and -3. | Reject | NG2/NX1 (unagreed) |
| The value is １．５. | The value is １．５. | Reject | NG2/NX1 (unagreed) |
| The value is 5. | The value is １．５. | Reject | NG2/NX1 (unagreed) |
| The value is 1·5. | The value is 1, 2, 3, 5, 10, 21, 500 and -5 and 5% and -3. | Reject | NG2/NX1 (unagreed) |
| The value is 1·5. | The value is 1·5. | Reject | NG2/NX1 (unagreed) |
| The value is 5. | The value is 1·5. | Reject | NG2/NX1 (unagreed) |
| The value is 1:5. | The value is 1, 2, 3, 5, 10, 21, 500 and -5 and 5% and -3. | Accept | NX2 (supersedes NX1 Reject) |
| The value is 1:5. | The value is 1:5. | Accept | NX2 (supersedes NX1 Reject) |
| The value is 5. | The value is 1:5. | Accept | NX2 (supersedes NX1 Reject) |
| The value is 1.2.3. | The value is 1, 2, 3, 5, 10, 21, 500 and -5 and 5% and -3. | Accept | NX2 (supersedes NX1 Reject) |
| The value is 1.2.3. | The value is 1.2.3. | Accept | NX2 (supersedes NX1 Reject) |
| The value is 5. | The value is 1.2.3. | Reject | NG2/NX1 (unagreed) |
| The value is 1..5. | The value is 1, 2, 3, 5, 10, 21, 500 and -5 and 5% and -3. | Reject | NG2/NX1 (unagreed) |
| The value is 1..5. | The value is 1..5. | Reject | NG2/NX1 (unagreed) |
| The value is 5. | The value is 1..5. | Reject | NG2/NX1 (unagreed) |
| The value is 5.%. | The value is 1, 2, 3, 5, 10, 21, 500 and -5 and 5% and -3. | Reject | NG2/NX1 (unagreed) |
| The value is 5.%. | The value is 5.%. | Reject | NG2/NX1 (unagreed) |
| The value is 5. | The value is 5.%. | Reject | NG2/NX1 (unagreed) |
| The value is 1e3. | The value is 1, 2, 3, 5, 10, 21, 500 and -5 and 5% and -3. | Reject | NG2/NX1 (unagreed) |
| The value is 1e3. | The value is 1e3. | Reject | NG2/NX1 (unagreed) |
| The value is 5. | The value is 1e3. | Reject | NG2/NX1 (unagreed) |
| The value is 1E-3. | The value is 1, 2, 3, 5, 10, 21, 500 and -5 and 5% and -3. | Reject | NG2/NX1 (unagreed) |
| The value is 1E-3. | The value is 1E-3. | Reject | NG2/NX1 (unagreed) |
| The value is 5. | The value is 1E-3. | Reject | NG2/NX1 (unagreed) |
| The value is 1e+3. | The value is 1, 2, 3, 5, 10, 21, 500 and -5 and 5% and -3. | Reject | NG2/NX1 (unagreed) |
| The value is 1e+3. | The value is 1e+3. | Reject | NG2/NX1 (unagreed) |
| The value is 5. | The value is 1e+3. | Reject | NG2/NX1 (unagreed) |
| The value is 1e. | The value is 1, 2, 3, 5, 10, 21, 500 and -5 and 5% and -3. | Reject | NG2/NX1 (unagreed) |
| The value is 1e. | The value is 1e. | Reject | NG2/NX1 (unagreed) |
| The value is 5. | The value is 1e. | Reject | NG2/NX1 (unagreed) |
| The value is 1e-. | The value is 1, 2, 3, 5, 10, 21, 500 and -5 and 5% and -3. | Reject | NG2/NX1 (unagreed) |
| The value is 1e-. | The value is 1e-. | Reject | NG2/NX1 (unagreed) |
| The value is 5. | The value is 1e-. | Reject | NG2/NX1 (unagreed) |
| The value is 1/2. | The value is 1, 2, 3, 5, 10, 21, 500 and -5 and 5% and -3. | Reject | NG2/NX1 (unagreed) |
| The value is 1/2. | The value is 1/2. | Reject | NG2/NX1 (unagreed) |
| The value is 5. | The value is 1/2. | Reject | NG2/NX1 (unagreed) |
| The value is 1⁄2. | The value is 1, 2, 3, 5, 10, 21, 500 and -5 and 5% and -3. | Reject | NG2/NX1 (unagreed) |
| The value is 1⁄2. | The value is 1⁄2. | Reject | NG2/NX1 (unagreed) |
| The value is 5. | The value is 1⁄2. | Reject | NG2/NX1 (unagreed) |
| The value is ½. | The value is 1, 2, 3, 5, 10, 21, 500 and -5 and 5% and -3. | Reject | NG2/NX1 (unagreed) |
| The value is ½. | The value is ½. | Reject | NG2/NX1 (unagreed) |
| The value is 5. | The value is ½. | Reject | NG2/NX1 (unagreed) |
| The value is 1½. | The value is 1, 2, 3, 5, 10, 21, 500 and -5 and 5% and -3. | Reject | NG2/NX1 (unagreed) |
| The value is 1½. | The value is 1½. | Reject | NG2/NX1 (unagreed) |
| The value is 5. | The value is 1½. | Reject | NG2/NX1 (unagreed) |
| The value is 5th. | The value is 1, 2, 3, 5, 10, 21, 500 and -5 and 5% and -3. | Reject | NG2/NX1 (unagreed) |
| The value is 5th. | The value is 5th. | Reject | NG2/NX1 (unagreed) |
| The value is 5. | The value is 5th. | Reject | NG2/NX1 (unagreed) |
| The value is 21st. | The value is 1, 2, 3, 5, 10, 21, 500 and -5 and 5% and -3. | Reject | NG2/NX1 (unagreed) |
| The value is 21st. | The value is 21st. | Reject | NG2/NX1 (unagreed) |
| The value is 5. | The value is 21st. | Reject | NG2/NX1 (unagreed) |
| The value is 2nd. | The value is 1, 2, 3, 5, 10, 21, 500 and -5 and 5% and -3. | Reject | NG2/NX1 (unagreed) |
| The value is 2nd. | The value is 2nd. | Reject | NG2/NX1 (unagreed) |
| The value is 5. | The value is 2nd. | Reject | NG2/NX1 (unagreed) |
| The value is 3rd. | The value is 1, 2, 3, 5, 10, 21, 500 and -5 and 5% and -3. | Reject | NG2/NX1 (unagreed) |
| The value is 3rd. | The value is 3rd. | Reject | NG2/NX1 (unagreed) |
| The value is 5. | The value is 3rd. | Reject | NG2/NX1 (unagreed) |
| The value is 5². | The value is 1, 2, 3, 5, 10, 21, 500 and -5 and 5% and -3. | Reject | NG2/NX1 (unagreed) |
| The value is 5². | The value is 5². | Reject | NG2/NX1 (unagreed) |
| The value is 5. | The value is 5². | Reject | NG2/NX1 (unagreed) |
| The value is 2×10^3. | The value is 1, 2, 3, 5, 10, 21, 500 and -5 and 5% and -3. | Reject | NG2/NX1 (unagreed) |
| The value is 2×10^3. | The value is 2×10^3. | Reject | NG2/NX1 (unagreed) |
| The value is 5. | The value is 2×10^3. | Reject | NG2/NX1 (unagreed) |
| The value is –5. | The value is 1, 2, 3, 5, 10, 21, 500 and -5 and 5% and -3. | Reject | NG2/NX1 (unagreed) |
| The value is –5. | The value is –5. | Reject | NG2/NX1 (unagreed) |
| The value is 5. | The value is –5. | Reject | NG2/NX1 (unagreed) |
| The value is —5. | The value is 1, 2, 3, 5, 10, 21, 500 and -5 and 5% and -3. | Reject | NG2/NX1 (unagreed) |
| The value is —5. | The value is —5. | Reject | NG2/NX1 (unagreed) |
| The value is 5. | The value is —5. | Reject | NG2/NX1 (unagreed) |
| The value is ‐5. | The value is 1, 2, 3, 5, 10, 21, 500 and -5 and 5% and -3. | Reject | NG2/NX1 (unagreed) |
| The value is ‐5. | The value is ‐5. | Reject | NG2/NX1 (unagreed) |
| The value is 5. | The value is ‐5. | Reject | NG2/NX1 (unagreed) |
| The value is ‑5. | The value is 1, 2, 3, 5, 10, 21, 500 and -5 and 5% and -3. | Reject | NG2/NX1 (unagreed) |
| The value is ‑5. | The value is ‑5. | Reject | NG2/NX1 (unagreed) |
| The value is 5. | The value is ‑5. | Reject | NG2/NX1 (unagreed) |
| The value is －5. | The value is 1, 2, 3, 5, 10, 21, 500 and -5 and 5% and -3. | Reject | NG2/NX1 (unagreed) |
| The value is －5. | The value is －5. | Reject | NG2/NX1 (unagreed) |
| The value is 5. | The value is －5. | Reject | NG2/NX1 (unagreed) |
| The value is ＋5. | The value is 1, 2, 3, 5, 10, 21, 500 and -5 and 5% and -3. | Reject | NG2/NX1 (unagreed) |
| The value is ＋5. | The value is ＋5. | Reject | NG2/NX1 (unagreed) |
| The value is 5. | The value is ＋5. | Reject | NG2/NX1 (unagreed) |

The repaired scorer tests also exercise the five literal NX1 rejection pairs
and two acceptance pairs through the cross-encoder loop; raw cosine checks use
`.5`, `-.5` and `+.5%` against `5% and 5` and reselect the compatible
`0.5% and 0.5 and -0.5` passage on both supplied and indexed paths.
Code `1e3`, fraction `1/2`, table-cell `--5`, Arabic `١٫٥` and repeated `5%%`
remain claims after Markdown segmentation and are withheld.

Additional sweep cases, recorded before their corrective edits:

| Sentence (literal) | Passage (literal) | Eligible | Decision |
| --- | --- | --- | --- |
| The value is '15'. | The value is 15. | Accept | D8/NX1 |
| The value is ‘15’. | The value is 15. | Accept | D8/NX1 |
| The value is “15”. | The value is 15. | Accept | D8/NX1 |
| The value is First sentence. 15. | The value is 15. | Accept | D8/NX1 |
| The value is .5-.7. | The value is 0.5 and 0.7. | Accept | D8/NX1 |
| The value is 5%-10%. | The value is 5% and 10%. | Accept | D8/NX1 |
| The value is ±5. | The value is 1, 2, 3, 5, 10, 21, 500 and -5 and 5% and -3. | Reject | NG2/NX1 (unagreed) |
| The value is ±5. | The value is ±5. | Reject | NG2/NX1 (unagreed) |
| The value is 5. | The value is ±5. | Reject | NG2/NX1 (unagreed) |
| The value is ∓5. | The value is 1, 2, 3, 5, 10, 21, 500 and -5 and 5% and -3. | Reject | NG2/NX1 (unagreed) |
| The value is ∓5. | The value is ∓5. | Reject | NG2/NX1 (unagreed) |
| The value is 5. | The value is ∓5. | Reject | NG2/NX1 (unagreed) |
| The value is ⁻5. | The value is 1, 2, 3, 5, 10, 21, 500 and -5 and 5% and -3. | Reject | NG2/NX1 (unagreed) |
| The value is ⁻5. | The value is ⁻5. | Reject | NG2/NX1 (unagreed) |
| The value is 5. | The value is ⁻5. | Reject | NG2/NX1 (unagreed) |
| The value is ⁺5. | The value is 1, 2, 3, 5, 10, 21, 500 and -5 and 5% and -3. | Reject | NG2/NX1 (unagreed) |
| The value is ⁺5. | The value is ⁺5. | Reject | NG2/NX1 (unagreed) |
| The value is 5. | The value is ⁺5. | Reject | NG2/NX1 (unagreed) |
| The value is ₋5. | The value is 1, 2, 3, 5, 10, 21, 500 and -5 and 5% and -3. | Reject | NG2/NX1 (unagreed) |
| The value is ₋5. | The value is ₋5. | Reject | NG2/NX1 (unagreed) |
| The value is 5. | The value is ₋5. | Reject | NG2/NX1 (unagreed) |
| The value is ₊5. | The value is 1, 2, 3, 5, 10, 21, 500 and -5 and 5% and -3. | Reject | NG2/NX1 (unagreed) |
| The value is ₊5. | The value is ₊5. | Reject | NG2/NX1 (unagreed) |
| The value is 5. | The value is ₊5. | Reject | NG2/NX1 (unagreed) |
| The value is ﹢5. | The value is 1, 2, 3, 5, 10, 21, 500 and -5 and 5% and -3. | Reject | NG2/NX1 (unagreed) |
| The value is ﹢5. | The value is ﹢5. | Reject | NG2/NX1 (unagreed) |
| The value is 5. | The value is ﹢5. | Reject | NG2/NX1 (unagreed) |
| The value is ⸺5. | The value is 1, 2, 3, 5, 10, 21, 500 and -5 and 5% and -3. | Reject | NG2/NX1 (unagreed) |
| The value is ⸺5. | The value is ⸺5. | Reject | NG2/NX1 (unagreed) |
| The value is 5. | The value is ⸺5. | Reject | NG2/NX1 (unagreed) |
| The value is 1 500. | The value is 1, 2, 3, 5, 10, 21, 500 and -5 and 5% and -3. | Reject | NG2/NX1 (unagreed) |
| The value is 1 500. | The value is 1 500. | Reject | NG2/NX1 (unagreed) |
| The value is 5. | The value is 1 500. | Reject | NG2/NX1 (unagreed) |
| The value is 1 500. | The value is 1, 2, 3, 5, 10, 21, 500 and -5 and 5% and -3. | Reject | NG2/NX1 (unagreed) |
| The value is 1 500. | The value is 1 500. | Reject | NG2/NX1 (unagreed) |
| The value is 5. | The value is 1 500. | Reject | NG2/NX1 (unagreed) |
| The value is 1․5. | The value is 1, 2, 3, 5, 10, 21, 500 and -5 and 5% and -3. | Reject | NG2/NX1 (unagreed) |
| The value is 1․5. | The value is 1․5. | Reject | NG2/NX1 (unagreed) |
| The value is 5. | The value is 1․5. | Reject | NG2/NX1 (unagreed) |
| The value is 1⋅5. | The value is 1, 2, 3, 5, 10, 21, 500 and -5 and 5% and -3. | Reject | NG2/NX1 (unagreed) |
| The value is 1⋅5. | The value is 1⋅5. | Reject | NG2/NX1 (unagreed) |
| The value is 5. | The value is 1⋅5. | Reject | NG2/NX1 (unagreed) |
| The value is 1˙5. | The value is 1, 2, 3, 5, 10, 21, 500 and -5 and 5% and -3. | Reject | NG2/NX1 (unagreed) |
| The value is 1˙5. | The value is 1˙5. | Reject | NG2/NX1 (unagreed) |
| The value is 5. | The value is 1˙5. | Reject | NG2/NX1 (unagreed) |
| The value is 5–. | The value is 1, 2, 3, 5, 10, 21, 500 and -5 and 5% and -3. | Reject | NG2/NX1 (unagreed) |
| The value is 5–. | The value is 5–. | Reject | NG2/NX1 (unagreed) |
| The value is 5. | The value is 5–. | Reject | NG2/NX1 (unagreed) |
| The value is 5—. | The value is 1, 2, 3, 5, 10, 21, 500 and -5 and 5% and -3. | Reject | NG2/NX1 (unagreed) |
| The value is 5—. | The value is 5—. | Reject | NG2/NX1 (unagreed) |
| The value is 5. | The value is 5—. | Reject | NG2/NX1 (unagreed) |
| The value is 5- . | The value is 1, 2, 3, 5, 10, 21, 500 and -5 and 5% and -3. | Reject | NG2/NX1 (unagreed) |
| The value is 5- . | The value is 5- . | Reject | NG2/NX1 (unagreed) |
| The value is 5. | The value is 5- . | Reject | NG2/NX1 (unagreed) |
| The value is 5--. | The value is 1, 2, 3, 5, 10, 21, 500 and -5 and 5% and -3. | Reject | NG2/NX1 (unagreed) |
| The value is 5--. | The value is 5--. | Reject | NG2/NX1 (unagreed) |
| The value is 5. | The value is 5--. | Reject | NG2/NX1 (unagreed) |
| The value is -\u200b5. | The value is 1, 2, 3, 5, 10, 21, 500 and -5 and 5% and -3. | Reject | NG2/NX1 (unagreed) |
| The value is -\u200b5. | The value is -\u200b5. | Reject | NG2/NX1 (unagreed) |
| The value is 5. | The value is -\u200b5. | Reject | NG2/NX1 (unagreed) |
| The value is 5\u200b%. | The value is 1, 2, 3, 5, 10, 21, 500 and -5 and 5% and -3. | Reject | NG2/NX1 (unagreed) |
| The value is 5\u200b%. | The value is 5\u200b%. | Reject | NG2/NX1 (unagreed) |
| The value is 5. | The value is 5\u200b%. | Reject | NG2/NX1 (unagreed) |
| The value is .\u200b5. | The value is 1, 2, 3, 5, 10, 21, 500 and -5 and 5% and -3. | Reject | NG2/NX1 (unagreed) |
| The value is .\u200b5. | The value is .\u200b5. | Reject | NG2/NX1 (unagreed) |
| The value is 5. | The value is .\u200b5. | Reject | NG2/NX1 (unagreed) |
| The value is 5\u20e3. | The value is 1, 2, 3, 5, 10, 21, 500 and -5 and 5% and -3. | Reject | NG2/NX1 (unagreed) |
| The value is 5\u20e3. | The value is 5\u20e3. | Reject | NG2/NX1 (unagreed) |
| The value is 5. | The value is 5\u20e3. | Reject | NG2/NX1 (unagreed) |

Unicode format/combining characters adjacent to digits and unsupported sign/dash
variants are withheld; the escaped Unicode sequences above denote the literal
characters used by the test. Unknown thin/figure-space grouping is withheld.
A terminal sign/dash must not be silently dropped. Quotation marks around
numbers and a preceding sentence terminator remain ordinary punctuation.

Final sign sweep, recorded before its corrective edit:

| Sentence (literal) | Passage (literal) | Eligible | Decision |
| --- | --- | --- | --- |
| The value is ➖5. | The value is 1, 2, 3, 5, 10, 21, 500 and -5 and 5% and -3. | Reject | NG2/NX1 (unagreed) |
| The value is ➖5. | The value is ➖5. | Reject | NG2/NX1 (unagreed) |
| The value is 5. | The value is ➖5. | Reject | NG2/NX1 (unagreed) |
| The value is ➕5. | The value is 1, 2, 3, 5, 10, 21, 500 and -5 and 5% and -3. | Reject | NG2/NX1 (unagreed) |
| The value is ➕5. | The value is ➕5. | Reject | NG2/NX1 (unagreed) |
| The value is 5. | The value is ➕5. | Reject | NG2/NX1 (unagreed) |
| The value is +/-5. | The value is 1, 2, 3, 5, 10, 21, 500 and -5 and 5% and -3. | Reject | NG2/NX1 (unagreed) |
| The value is +/-5. | The value is +/-5. | Reject | NG2/NX1 (unagreed) |
| The value is 5. | The value is +/-5. | Reject | NG2/NX1 (unagreed) |
| The value is -/+5. | The value is 1, 2, 3, 5, 10, 21, 500 and -5 and 5% and -3. | Reject | NG2/NX1 (unagreed) |
| The value is -/+5. | The value is -/+5. | Reject | NG2/NX1 (unagreed) |
| The value is 5. | The value is -/+5. | Reject | NG2/NX1 (unagreed) |
| The value is -,.5. | The value is 1, 2, 3, 5, 10, 21, 500 and -5 and 5% and -3. | Reject | NG2/NX1 (unagreed) |
| The value is -,.5. | The value is -,.5. | Reject | NG2/NX1 (unagreed) |
| The value is 5. | The value is -,.5. | Reject | NG2/NX1 (unagreed) |

Heavy plus/minus symbols and malformed mixed sign/separator runs have no
agreed interpretation. They are withheld rather than dropping any prefix.

Range preservation counterexamples, recorded before their corrective edit:

| Sentence (literal) | Passage (literal) | Eligible | Decision |
| --- | --- | --- | --- |
| The value is -.5%--.7%. | The value is -0.5% and -0.7%. | Accept | D8/F1/NX1 |
| The value is 5%--10%. | The value is 5% and -10%. | Accept | D8/F1/NX1 |
| The value is 5%–10%. | The value is 5% and 10%. | Accept | D8/F1/NX1 |
| The value is 5%—-10%. | The value is 5% and -10%. | Accept | D8/F1/NX1 |
| The value is 5kg-10kg. | The value is 1, 2, 3, 5, 10, 21, 500 and -5 and 5% and -3. | Accept | NX2 (supersedes NX1 Reject) |
| The value is 5kg-10kg. | The value is 5kg-10kg. | Accept | NX2 (supersedes NX1 Reject) |
| The value is 5. | The value is 5kg-10kg. | Accept | NX2 (supersedes NX1 Reject) |
| The value is 5°-10°. | The value is 1, 2, 3, 5, 10, 21, 500 and -5 and 5% and -3. | Accept | NX2 (supersedes NX1 Reject) |
| The value is 5°-10°. | The value is 5°-10°. | Accept | NX2 (supersedes NX1 Reject) |
| The value is 5. | The value is 5°-10°. | Accept | NX2 (supersedes NX1 Reject) |
| The value is JDK-15. | The value is 1, 2, 3, 5, 10, 21, 500 and -5 and 5% and -3. | Reject | NG2/NX1 (unagreed) |
| The value is JDK-15. | The value is JDK-15. | Reject | NG2/NX1 (unagreed) |
| The value is 5. | The value is JDK-15. | Reject | NG2/NX1 (unagreed) |

A percent sign ends a value for range/date-separator detection just as a digit
does. A unit or identifier immediately before a sign/dash does not decide
whether it is a separator or unary sign; these forms are withheld rather than
reading an unintended negative endpoint.

# NX1 repair verification observations (builder; no acceptance verdict)

Completed 2026-10-21 15:25 CEST, within the recorded estimate. Production changes
are confined to NumericConsistency.java; there are no dependency, lockfile,
frontend, persisted-format or producer-gate changes. Every unknown form listed
above withholds verification, even against identical notation. The sweep covers
79 unagreed forms, in addition to the agreed decimal/range/group/date/digit and
punctuation pairs. A percent sign and a digit both end a range value; unary minus
and percentage notation survive normalization. Plus is no sign under NX1.

All eight repair methods in `uiux-numeric-guard-nx1-repaired.json` pass on the
candidate and fail by assertion on 3ec058b1e. Failures include `.5`, `-.5` and
`+.5%` real CPU matches at 0.9995213151, 0.9988987446 and 0.9990026355 instead of
an empty match list. Raw cosine baseline assertions report expected zero matches,
actual one, for all six decimal/supplied-or-lookup combinations. Code, fraction,
table, Arabic-decimal and repeated-percent cases all execute in the baseline
assertion group. Rejected claims remain scored; compatible alternatives retain
the original score/threshold and source identity.

The original 76efc1c10 JSON/XML evidence remains unchanged. Refreshed evidence for
the two edited old test files is in `uiux-numeric-guard-original-refreshed-repaired.json`
(seven assertion fail-before checks on 76efc1c10) and
`uiux-numeric-guard-refreshed-preserved.json` (five passing preservation methods).
Numeric-free exact matches, all agreed literal compatibility checks, reference
and ordinal distinctions, Ask and Delegate payload/history, raw HTTP-handler
counts, WP4.4 passage checks and MCP output pass. Preservation checks also ran
successfully on the 3ec058b1e shared clone; percentage-range punctuation passed
there, failed on the first repair candidate and passed after the correction.

Final validation, with each Gradle call inside js-gradle-slot (a containing
script holds the slot for its sequential Gradle calls):

- Focused reranker and worker-services tests plus their pmdMain: passed on the
  final production code. Ask/app-services, Delegate/app-agent and UI handler/MCP
  tests and their pmdMain: passed. No named repair check is skipped.
- `./gradlew build -x test -PskipWebBuild=true`: BUILD SUCCESSFUL, 1m 33s,
  332 actionable tasks, normal environment.
- Frontend `npm run lint`, `npm run typecheck`, `npx vitest run --maxWorkers=4`:
  exit 0; 549 files / 7207 tests passed. Root UI gates: 28/28 passed.
- Script-written repair and preservation evidence: exit 0. The changeset gate
  covers every item; its remaining failure is stale independent acceptance.
  The previous verifier rejection is preserved byte-for-byte. Its missing
  `## Acceptance records` heading was restored so the gate can parse the record;
  no acceptance record was authored or changed by this builder.
- Audit warning: origin/main currently has 1 tie-break among 9 audited PRs in
  28 days (11.1%, above one in ten); the orchestrator must report this finding.

The build with the model-test environment enabled exposed an existing
integrationTest classpath failure: missing
`ai.onnxruntime.OrtSession$SessionOptions$OptLevel`. It reproduces on origin/main
738e6a443c9d6ce82383e9c938c264a3a20fb506, whose integration source is unchanged.
The required normal-environment build passes. Model-gated integration tests
skipped by that normal build are not counted as passes; the separate unit-source
CPU model regression checks execute without skips in the repair evidence.

Performance follows the recorded F5 protocol: same six fixtures/hash, model
hashes, CPU scorer, deadline, two suite warm-ups and five measured suite sums;
only the measurement test runs during this session's measured intervals, using
one Gradle worker. CPU: Intel Core i7-12700K, 20 logical CPUs, 31 GiB RAM; Java
25.0.4.1 and ORT 1.24.3. Baseline/candidate XML is retained as
`uiux-numeric-guard-nx1-performance-{before,after}.xml`. Final medians are
193151056 ns and 167168253 ns (ratio 0.86548, within the 10% bound); all six
fixtures remain scored in all five runs, with identical matched counts. These
are scorer measurements, not a production Ask performance claim. The first
measurement overlapped different parallel test workloads (175/432 ms); it was
not used for acceptance, and its samples remain in tmp/nx1-repair/parallel-checks-*
for audit.

Recorded resources and all five final suite samples:

```text
before: NUMERIC_RESOURCE java=25.0.4.1 ort=1.24.3 os=Linux arch=amd64 processors=20 load=8.4619140625
before: NUMERIC_RUN 0 suiteNanos=183767461 scored=[1, 1, 1, 1, 1, 1] matched=[0, 0, 1, 1, 0, 1] load=8.4619140625
before: NUMERIC_RUN 1 suiteNanos=216430063 scored=[1, 1, 1, 1, 1, 1] matched=[0, 0, 1, 1, 0, 1] load=8.4619140625
before: NUMERIC_RUN 2 suiteNanos=188330479 scored=[1, 1, 1, 1, 1, 1] matched=[0, 0, 1, 1, 0, 1] load=8.4619140625
before: NUMERIC_RUN 3 suiteNanos=200032157 scored=[1, 1, 1, 1, 1, 1] matched=[0, 0, 1, 1, 0, 1] load=8.4619140625
before: NUMERIC_RUN 4 suiteNanos=193151056 scored=[1, 1, 1, 1, 1, 1] matched=[0, 0, 1, 1, 0, 1] load=8.4619140625
before: NUMERIC_MEDIAN suiteNanos=193151056
before: MemTotal:       32534604 kB
before: MemAvailable:   12391596 kB
before: SwapFree:       29413272 kB
after: NUMERIC_RESOURCE java=25.0.4.1 ort=1.24.3 os=Linux arch=amd64 processors=20 load=8.3447265625
after: NUMERIC_RUN 0 suiteNanos=163825491 scored=[1, 1, 1, 1, 1, 1] matched=[0, 0, 1, 1, 0, 1] load=8.6376953125
after: NUMERIC_RUN 1 suiteNanos=169612311 scored=[1, 1, 1, 1, 1, 1] matched=[0, 0, 1, 1, 0, 1] load=8.6376953125
after: NUMERIC_RUN 2 suiteNanos=167168253 scored=[1, 1, 1, 1, 1, 1] matched=[0, 0, 1, 1, 0, 1] load=8.6376953125
after: NUMERIC_RUN 3 suiteNanos=157322369 scored=[1, 1, 1, 1, 1, 1] matched=[0, 0, 1, 1, 0, 1] load=8.6376953125
after: NUMERIC_RUN 4 suiteNanos=168531034 scored=[1, 1, 1, 1, 1, 1] matched=[0, 0, 1, 1, 0, 1] load=8.6376953125
after: NUMERIC_MEDIAN suiteNanos=167168253
after: MemTotal:       32534604 kB
after: MemAvailable:   12344256 kB
after: SwapFree:       29413836 kB
```

Remaining unverified: live Engine/browser combinations and actual cold Engine
reload (NG1/NG6/GN3); production Ask timing/scored coverage (NG8); the production
Ask rag-qa before/after verified-count measurement (NG9). The dev stack was
explicitly unavailable to this builder. Unit, Worker, file-store/history and
HTTP-handler checks do not substitute for those live checks.

Tests added or rewritten (all durable):

| File | Scenarios | Status |
| --- | --- | --- |
| CitationScorerNumericTest.java | NG1, NG2, NG3, NG5, NG7, GN1 | durable |
| CitationMatchOpsNumericTest.java | NG1, NG2, NG3, NG5, NG6, NG7, GN1 | durable |
| NumericConsistencyTokenizationTest.java | NG1, NG2, NG3, NG7 | durable |
| NumericCitationScorerDecimalIntegrationTest.java | NG1, NG3 | durable |

Manual comparison checkouts were shared clones under the assigned TMPDIR:
`before` at 3ec058b1e and `origin-main` at 738e6a443c9d6ce82383e9c938c264a3a20fb506.
Both are removed after comparisons; the evidence tool removes its own before
worktrees. No corpus, model or installer was copied. No dev stack was started,
no other session's process was stopped, and no commit, push or publication occurred.

# Historical first implementation and evidence
# Numeric consistency: contract recorded before implementation

Started 2026-10-21 09:41 CEST. Estimate 4–8 h; return for a decision by
2026-10-21 21:41 CEST (the assignment's approximately 12 h limit).

The following literal pairs fix the comparison contract (NG1, NG3, NG4, NG7;
D8 amended by F1 and F3). “Accept” means eligible for the original score and
threshold, never a promise of verification. Every distinct claimed value must
occur in the selected passage. Units and leading zeros are ignored; explicit
plus/minus and percentage notation are retained. A hyphen between digits is a
range/date separator, not a unary minus. Decimal point is the only decimal
separator. Only complete groups of three after a one-to-three-digit first group
are grouping; malformed groups remain separate values. Comma, ordinary space,
NBSP and narrow NBSP are the grouping formats. No decimal rounding, arithmetic,
unit conversion or number-word interpretation occurs.

| Sentence (literal) | Passage (literal) | Eligible |
| --- | --- | --- |
| Vespera Observatory opened in 2099. | Vespera Observatory opened in 1883. | Reject |
| Vespera Observatory opened in 1923. | Vespera Observatory opened in 1883. | Reject |
| The value is 99. | The value is 2099. | Reject |
| The value is 2099. | The value is 99. | Reject |
| The value is 1.5. | The value is 15. | Reject |
| The value is -15. | The value is 15. | Reject |
| The value is 15. | The value is -15. | Reject |
| The value is +15. | The value is 15. | Reject |
| The value is −15. | The value is -15. | Accept |
| The value is 15%. | The value is 15. | Reject |
| The value is 15. | The value is 15%. | Reject |
| The value is 15 %. | The value is 15%. | Accept |
| The value is 0015 kg. | The value is 15 m. | Accept |
| The value is 1.50. | The value is 1.5. | Reject |
| The value is 1,500. | The value is 1500. | Accept |
| The value is 1 500. | The value is 1500. | Accept |
| The value is 1 500. | The value is 1500. | Accept |
| The value is 1 500. | The value is 1500. | Accept |
| The value is 1,500,000. | The value is 1500000. | Accept |
| The value is 12,34. | The values are 12 and 34. | Accept |
| The value is 12,34. | The value is 1234. | Reject |
| The value is 1.500. | The value is 1500. | Reject |
| The interval is 10-20. | The endpoints are 10 and 20. | Accept |
| The interval is 10–20. | The endpoints are 10 and 20. | Accept |
| The interval is 10-20. | The endpoint is 10. | Reject |
| The interval is -20 to -10. | The values are -10 and -20. | Accept |
| The date is 2026-10-21. | The parts are 21, 10 and 2026. | Accept |
| The date is 2026-10-21. | The parts are 21 and 2026. | Reject |
| افتتح المرصد في ١٨٨٣. | Opened in 1883. | Accept |
| افتتح المرصد في ٢٠٩٩. | Opened in 1883. | Reject |
| 値は１５です。 | The value is 15. | Accept |
| The engine owns the index [4]. | The engine owns the index. | Accept |
| The engine owns the index [4][7]. | The engine owns the index. | Accept |
| 4. The engine owns the index. | The engine owns the index. | Accept |
| The array is `[4]`. | The array is empty. | Reject |
| The array is `[4]`. | The array contains 4. | Accept |
| The array is [4, 7]. | The array contains 4. | Reject |
| The equation is x = [4]. | The equation is unspecified. | Reject |
| The equation is x = [4]. | The value is 4. | Accept |
| The constant is `2099`. | The constant is 1883. | Reject |
| Value is 2099 (a table cell). | Value is 1883. | Reject |

Reference syntax: positive integer bracket markers following prose, before a
clause boundary or another marker, are citations. Brackets in code, comma
arrays, and brackets following mathematical operators retain their values.
CommonMark list ordinals are structure. Segmentation must retain numeric claim
metadata for code while keeping existing sentence keys unchanged.

Late preservation counterexamples, recorded before their corrective edit:
`索引归引擎所有 [4]。` / `Some passage.` and
`索引归引擎所有。 [4]` / `Some passage.` must remain eligible.
Recognized references use Unicode punctuation boundaries as well as ASCII ones.
These add no numeric equivalence; they pin NG4/NG7/GN1 for the existing marker rule.

# Performance protocol recorded before implementation (NG8, NG9; F5)

Baseline: 76efc1c10. Candidate: this uncommitted worktree, whose evidence captures
the working-tree hash. Scorer fixtures: the literal table above plus a fixed
number-free corpus in the new scorer tests. No mutable live index: Worker
fixtures construct a fresh Lucene index from literal sources per test; supplied
and lookup texts are identical between revisions. Scorer timing uses the same
fixed answer/source suite on both revisions, including both WP4.4 examples,
a compatible alternate source, a numeric-free answer and Unicode/code cases.
The exact fixture lists and hashes accompany the measurement output.

Model: read-only models/onnx/citation-scorer/{model.onnx,tokenizer.json,config.json}
in the main checkout (MiniLM cross-encoder); record SHA-256 of all three, Java,
ORT version, CPU identity and available memory. CPU-only, one measurement lane,
no dev stack; record competing load before each run. Warm up the same scorer
with two complete unmeasured suite runs per JVM. Measure System.nanoTime around
scoreAll for every fixture, including numeric preparation and all sentence /
window scoring, excluding session/tokenizer startup and warm-up. Run five
complete suites; the median applies to the SUM of fixture intervals per suite,
not separately per fixture. Compare baseline/candidate medians (candidate at
most 110% of baseline) and each fixture's sentencesScored on identical inputs
and the same production deadline. Report every run, medians, matched/scored
counts, and any newly unscored sentence. A deterministic stub timing is not an
ONNX or production Ask performance claim.

Live follow-up (unavailable to this builder): pin a rag-qa corpus/index snapshot
and its content hash, question IDs, chat model/version/hash, generation settings
and retrieved source/answer fixtures. Use the production Ask entry point and
persist its events/claimMatches; cold-reload the same records. Run baseline and
candidate under identical CPU/memory/load and deadlines with two warm-up suites
and five measured suites; measure from citation scoring dispatch through its
completed response, suite median as above. Compare scored coverage per sentence
and report before/after verified counts for every rag-qa answer and totals.
RagQualityEvalTest's direct scoreAll count is supplementary and cannot satisfy
NG9. No live Ask, browser cold reload or chat generation result is claimed here.

# Test-intent entries

The shared helper is used at candidate selection in both existing best-of loops.
Every window is still scored; numeric compatibility only changes eligibility for
the winner. Counts are derived after selection, not corrected by post-filtering.
Parsed claims carry numeric metadata alongside unchanged visible sentence keys;
inline code brackets are protected in that metadata. The existing segmentation
claim floor, producer gate, store format, and frontend are unchanged. The direct
RAG evaluator now carries that metadata too, and its unused string-only wrapper
is removed. The reranker unit suite reuses the existing ORT fixture dependency
and its existing native runtime; no dependency lock or frontend manifest changes.

```json
{
  "entries": [
    {
      "items": [
        "modules/reranker/src/test/java/io/justsearch/reranker/CitationScorerNumericTest.java"
      ],
      "class": "New",
      "behaviour": "Plain-number claim rejections, same-threshold reselection and non-promotion through the real cross-encoder selection loop; NX3 governs the numeric form scope.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "NG1",
          "quote": "A sentence that states a numeric value the chosen passage does not contain as a whole value is not verified against that passage. Applies to Ask and Delegate, cross-encoder and cosine scoring, supplied passages and index lookups, live and after cold reload. Both WP4.4 counterexamples no longer verify against the passage lacking the number.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "NG2",
          "quote": "The guard only withholds a verdict; it never grants one. A sentence whose values all appear in the passage gets exactly the verdict it gets today.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "NG3",
          "quote": "An explicit table of accepted and rejected sentence/passage pairs (decision D8) passes: whole values not substrings (99 vs 2099), 1.5 vs 15, signed values, percentages, ranges, dates, the agreed grouping formats, non-Latin digits. Values inside inline code and table cells count as claims.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "NG5",
          "quote": "When the highest-scoring passage lacks the sentence's value and another passage containing it clears the threshold, the outcome follows decision D9 identically on both scorers; under reselection the mark opens the passage that contains the value.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "NG7",
          "quote": "Sentences in other languages and non-Latin digits neither crash the check nor change the result except as the table in S13.3 says.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-words",
          "location": "task t-20261019-bd718f agreement",
          "quote": "A number touching anything else (a letter, unit, currency sign, percent, degree sign, plus or minus, slash, dash, colon, another digit group, a second decimal point) is not plain: it is skipped. It never withholds a verdict and never counts as checked."
        },
        {
          "kind": "owner-words",
          "location": "task t-20261019-bd718f agreement",
          "quote": "The passage side is generous: a claim's plain number counts as present when the passage contains that same value as a whole number anywhere, whatever touches it there (in a unit, range, date, currency, sign or percent). A whole number is still never a piece of a longer number (99 is not in 2099; 15 is not in 1.5; 1,200 equals 1200 under the agreed grouping)."
        },
        {
          "kind": "owner-words",
          "location": "task t-20261019-bd718f agreement",
          "quote": "A plain number in the claim is a whole run of digits (non-Latin digits normalised as before), optionally with the agreed groups of three and at most one decimal point, that stands alone: bounded on each side by whitespace, the start or end of the text, or ordinary sentence punctuation (. , ; : ! ? quotes, brackets) that is not itself part of a number. A sentence-final period is punctuation (\"in 2019.\" is 2019)."
        }
      ],
      "evidence": "gates/test-intent/evidence/uiux-numeric-guard-nx3a-original-repaired.json",
      "newChecks": [
        "modules/reranker/src/test/java/io/justsearch/reranker/CitationScorerNumericTest.java#rejectsLiteralIncompatiblePairs",
        "modules/reranker/src/test/java/io/justsearch/reranker/CitationScorerNumericTest.java#reselectsCompatibleWindowAcrossSubBatches",
        "modules/reranker/src/test/java/io/justsearch/reranker/CitationScorerNumericTest.java#neverPromotesAnAlternativeBelowThreshold"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/reranker/src/test/java/io/justsearch/reranker/CitationScorerNumericTest.java"
      ],
      "class": "New",
      "behaviour": "NX3-compatible and skipped claim forms retain their exact existing scorer verdicts; numeric-free corpus, reference markers, ordinals, scores, thresholds and counts retain their oracles.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "NG2",
          "quote": "The guard only withholds a verdict; it never grants one. A sentence whose values all appear in the passage gets exactly the verdict it gets today.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "NG3",
          "quote": "An explicit table of accepted and rejected sentence/passage pairs (decision D8) passes: whole values not substrings (99 vs 2099), 1.5 vs 15, signed values, percentages, ranges, dates, the agreed grouping formats, non-Latin digits. Values inside inline code and table cells count as claims.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "NG4",
          "quote": "Reference literals such as [4], list numbering and the answer's own markers are not treated as stated values.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "GN1",
          "quote": "Sentences without numbers get exactly the same matches as before on a fixed corpus.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-words",
          "location": "task t-20261019-bd718f agreement",
          "quote": "A number touching anything else (a letter, unit, currency sign, percent, degree sign, plus or minus, slash, dash, colon, another digit group, a second decimal point) is not plain: it is skipped. It never withholds a verdict and never counts as checked."
        },
        {
          "kind": "owner-words",
          "location": "task t-20261019-bd718f agreement",
          "quote": "The passage side is generous: a claim's plain number counts as present when the passage contains that same value as a whole number anywhere, whatever touches it there (in a unit, range, date, currency, sign or percent). A whole number is still never a piece of a longer number (99 is not in 2099; 15 is not in 1.5; 1,200 equals 1200 under the agreed grouping)."
        },
        {
          "kind": "owner-words",
          "location": "task t-20261019-bd718f agreement",
          "quote": "A plain number in the claim is a whole run of digits (non-Latin digits normalised as before), optionally with the agreed groups of three and at most one decimal point, that stands alone: bounded on each side by whitespace, the start or end of the text, or ordinary sentence punctuation (. , ; : ! ? quotes, brackets) that is not itself part of a number. A sentence-final period is punctuation (\"in 2019.\" is 2019)."
        }
      ],
      "evidence": "gates/test-intent/evidence/uiux-numeric-guard-nx3a-preserved.json",
      "newChecks": [
        "modules/reranker/src/test/java/io/justsearch/reranker/CitationScorerNumericTest.java#preservesLiteralCompatiblePairs",
        "modules/reranker/src/test/java/io/justsearch/reranker/CitationScorerNumericTest.java#preservesNumberFreeCorpusAndThreshold",
        "modules/reranker/src/test/java/io/justsearch/reranker/CitationScorerNumericTest.java#preservesSpacedPercentRanges"
      ]
    },
    {
      "items": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/CitationMatchOpsNumericTest.java"
      ],
      "class": "New",
      "behaviour": "Raw cosine results reject missing plain values on supplied/indexed windows and reselect only a compatible source clearing the unchanged threshold. Numeric data in code, arrays, tables and mathematical brackets is retained when plain under NX3.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "NG1",
          "quote": "A sentence that states a numeric value the chosen passage does not contain as a whole value is not verified against that passage. Applies to Ask and Delegate, cross-encoder and cosine scoring, supplied passages and index lookups, live and after cold reload. Both WP4.4 counterexamples no longer verify against the passage lacking the number.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "NG3",
          "quote": "An explicit table of accepted and rejected sentence/passage pairs (decision D8) passes: whole values not substrings (99 vs 2099), 1.5 vs 15, signed values, percentages, ranges, dates, the agreed grouping formats, non-Latin digits. Values inside inline code and table cells count as claims.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "NG4",
          "quote": "Reference literals such as [4], list numbering and the answer's own markers are not treated as stated values.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "NG5",
          "quote": "When the highest-scoring passage lacks the sentence's value and another passage containing it clears the threshold, the outcome follows decision D9 identically on both scorers; under reselection the mark opens the passage that contains the value.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "NG6",
          "quote": "After the guard, the match list, the matched-sentence count, the marks, the source grades, the coverage note and the frame agree; a rejected sentence still counts as scored, never as not scored. Holds on Ask, Delegate and the raw citation API (RetrieveContextController.java:335-345).",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "NG7",
          "quote": "Sentences in other languages and non-Latin digits neither crash the check nor change the result except as the table in S13.3 says.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-words",
          "location": "task t-20261019-bd718f agreement",
          "quote": "A number touching anything else (a letter, unit, currency sign, percent, degree sign, plus or minus, slash, dash, colon, another digit group, a second decimal point) is not plain: it is skipped. It never withholds a verdict and never counts as checked."
        },
        {
          "kind": "owner-words",
          "location": "task t-20261019-bd718f agreement",
          "quote": "The passage side is generous: a claim's plain number counts as present when the passage contains that same value as a whole number anywhere, whatever touches it there (in a unit, range, date, currency, sign or percent). A whole number is still never a piece of a longer number (99 is not in 2099; 15 is not in 1.5; 1,200 equals 1200 under the agreed grouping)."
        },
        {
          "kind": "owner-words",
          "location": "task t-20261019-bd718f agreement",
          "quote": "A plain number in the claim is a whole run of digits (non-Latin digits normalised as before), optionally with the agreed groups of three and at most one decimal point, that stands alone: bounded on each side by whitespace, the start or end of the text, or ordinary sentence punctuation (. , ; : ! ? quotes, brackets) that is not itself part of a number. A sentence-final period is punctuation (\"in 2019.\" is 2019)."
        }
      ],
      "evidence": "gates/test-intent/evidence/uiux-numeric-guard-nx3a-original-repaired.json",
      "newChecks": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/CitationMatchOpsNumericTest.java#rejectsBothCounterexamplesOnSuppliedAndLookupPaths",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/CitationMatchOpsNumericTest.java#rejectsMissingYearInLongLookupDocument",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/CitationMatchOpsNumericTest.java#reselectsCompatibleSourceOnSuppliedAndLookupPaths",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/CitationMatchOpsNumericTest.java#retainsNumericClaimsInCodeTablesArraysAndMathematicsAfterSegmentation"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/CitationMatchOpsNumericTest.java"
      ],
      "class": "New",
      "behaviour": "Existing plain sentence keys and exact raw cosine matches survive reference/list structure, numeric-free multilingual text, and compatible normalized values, with no threshold promotion.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "NG2",
          "quote": "The guard only withholds a verdict; it never grants one. A sentence whose values all appear in the passage gets exactly the verdict it gets today.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "NG4",
          "quote": "Reference literals such as [4], list numbering and the answer's own markers are not treated as stated values.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "NG7",
          "quote": "Sentences in other languages and non-Latin digits neither crash the check nor change the result except as the table in S13.3 says.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "GN1",
          "quote": "Sentences without numbers get exactly the same matches as before on a fixed corpus.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-words",
          "location": "task t-20261019-bd718f agreement",
          "quote": "A number touching anything else (a letter, unit, currency sign, percent, degree sign, plus or minus, slash, dash, colon, another digit group, a second decimal point) is not plain: it is skipped. It never withholds a verdict and never counts as checked."
        },
        {
          "kind": "owner-words",
          "location": "task t-20261019-bd718f agreement",
          "quote": "The passage side is generous: a claim's plain number counts as present when the passage contains that same value as a whole number anywhere, whatever touches it there (in a unit, range, date, currency, sign or percent). A whole number is still never a piece of a longer number (99 is not in 2099; 15 is not in 1.5; 1,200 equals 1200 under the agreed grouping)."
        },
        {
          "kind": "owner-words",
          "location": "task t-20261019-bd718f agreement",
          "quote": "A plain number in the claim is a whole run of digits (non-Latin digits normalised as before), optionally with the agreed groups of three and at most one decimal point, that stands alone: bounded on each side by whitespace, the start or end of the text, or ordinary sentence punctuation (. , ; : ! ? quotes, brackets) that is not itself part of a number. A sentence-final period is punctuation (\"in 2019.\" is 2019)."
        }
      ],
      "evidence": "gates/test-intent/evidence/uiux-numeric-guard-nx3a-preserved.json",
      "newChecks": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/CitationMatchOpsNumericTest.java#preservesReferencesOrdinalsAndNumberFreeCorpus",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/CitationMatchOpsNumericTest.java#preservesCompatibleNumbersAndOriginalThreshold",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/CitationMatchOpsNumericTest.java#preservesSpacedPercentRanges"
      ]
    },
    {
      "items": [
        "modules/reranker/src/test/java/io/justsearch/reranker/NumericCitationScorerIntegrationTest.java"
      ],
      "class": "New",
      "behaviour": "With the explicitly configured read-only CPU model, both wrong-year counterexamples lose their matches but remain scored; evidence executes this check without a model skip.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "NG1",
          "quote": "A sentence that states a numeric value the chosen passage does not contain as a whole value is not verified against that passage. Applies to Ask and Delegate, cross-encoder and cosine scoring, supplied passages and index lookups, live and after cold reload. Both WP4.4 counterexamples no longer verify against the passage lacking the number.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "NG6",
          "quote": "After the guard, the match list, the matched-sentence count, the marks, the source grades, the coverage note and the frame agree; a rejected sentence still counts as scored, never as not scored. Holds on Ask, Delegate and the raw citation API (RetrieveContextController.java:335-345).",
          "label": "agent-drafted, owner-adopted"
        }
      ],
      "evidence": "gates/test-intent/evidence/uiux-numeric-guard-repaired.json",
      "newChecks": [
        "modules/reranker/src/test/java/io/justsearch/reranker/NumericCitationScorerIntegrationTest.java#rejectsRealModelCounterexamplesAndKeepsCoverage"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/reranker/src/test/java/io/justsearch/reranker/NumericCitationScorerIntegrationTest.java"
      ],
      "class": "New",
      "behaviour": "A fixed literal scorer suite reports model/fixture hashes, resource facts, two warm-ups and five timed suites, and asserts every fixture remains scored. Paired isolated XML artifacts below carry the actual before/after timing comparison; this check makes no production Ask or rag-qa claim.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "NG8",
          "quote": "On a fixed question set, the guard causes no sentence to go unscored for lack of time that was scored before, and scoring time stays within the bound of decision D10.",
          "label": "agent-drafted, owner-adopted"
        }
      ],
      "evidence": "gates/test-intent/evidence/uiux-numeric-guard-preserved.json",
      "newChecks": [
        "modules/reranker/src/test/java/io/justsearch/reranker/NumericCitationScorerIntegrationTest.java#reportsFiveRunScorerSuiteMeasurement"
      ]
    },
    {
      "items": [
        "modules/app-services/src/test/java/io/justsearch/app/services/conversation/spi/NumericCitationHistoryTest.java"
      ],
      "class": "New",
      "behaviour": "An empty authoritative result remains fully scored in Ask events, the persisted claimMatches payload and a fresh FileConversationStore reload on both producers. A legacy wrong-year verified record retains its original verdict and sources.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "NG6",
          "quote": "After the guard, the match list, the matched-sentence count, the marks, the source grades, the coverage note and the frame agree; a rejected sentence still counts as scored, never as not scored. Holds on Ask, Delegate and the raw citation API (RetrieveContextController.java:335-345).",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "GN3",
          "quote": "Conversations saved before the change load and render as before (no re-judging).",
          "label": "agent-drafted, owner-adopted"
        }
      ],
      "evidence": "gates/test-intent/evidence/uiux-numeric-guard-preserved.json",
      "newChecks": [
        "modules/app-services/src/test/java/io/justsearch/app/services/conversation/spi/NumericCitationHistoryTest.java#rejectedSentenceStaysScoredInLiveAndColdReloadedAskPayload",
        "modules/app-services/src/test/java/io/justsearch/app/services/conversation/spi/NumericCitationHistoryTest.java#legacyNumericVerdictLoadsWithoutRejudging"
      ]
    },
    {
      "items": [
        "modules/app-agent/src/test/java/io/justsearch/agent/AgentNumericCitationRejectionTest.java"
      ],
      "class": "New",
      "behaviour": "A fully scored rejection retains the producer stamp and no verified citation through Delegate resolver, done payload and history mapping, on both producers.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "NG6",
          "quote": "After the guard, the match list, the matched-sentence count, the marks, the source grades, the coverage note and the frame agree; a rejected sentence still counts as scored, never as not scored. Holds on Ask, Delegate and the raw citation API (RetrieveContextController.java:335-345).",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "GN3",
          "quote": "Conversations saved before the change load and render as before (no re-judging).",
          "label": "agent-drafted, owner-adopted"
        }
      ],
      "evidence": "gates/test-intent/evidence/uiux-numeric-guard-preserved.json",
      "newChecks": [
        "modules/app-agent/src/test/java/io/justsearch/agent/AgentNumericCitationRejectionTest.java#rejectedSentenceKeepsProducerAndEmptyCitesThroughDelegateEventsAndHistory"
      ]
    },
    {
      "items": [
        "modules/ui/src/test/java/io/justsearch/ui/api/RetrieveContextNumericRejectionTest.java"
      ],
      "class": "New",
      "behaviour": "The raw citation HTTP handler publishes zero matches/matched sentences beside complete sentence/text coverage and the actual producer on both paths.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "NG6",
          "quote": "After the guard, the match list, the matched-sentence count, the marks, the source grades, the coverage note and the frame agree; a rejected sentence still counts as scored, never as not scored. Holds on Ask, Delegate and the raw citation API (RetrieveContextController.java:335-345).",
          "label": "agent-drafted, owner-adopted"
        }
      ],
      "evidence": "gates/test-intent/evidence/uiux-numeric-guard-preserved.json",
      "newChecks": [
        "modules/ui/src/test/java/io/justsearch/ui/api/RetrieveContextNumericRejectionTest.java#rawApiReportsRejectionAsScoredWithZeroMatches"
      ]
    },
    {
      "items": [
        "modules/system-tests/src/integrationTest/java/io/justsearch/systemtests/ai/RagQualityEvalTest.java"
      ],
      "class": "Adaptation",
      "moved": [
        "input re-encoding"
      ],
      "whatMoved": "Faithfulness passes the production segmentation keys together with their numeric metadata instead of discarding code-versus-reference distinctions and passing only strings. The model inputs, passages, threshold, deadline, coverage denominator and matched/total calculation remain the same; the shared scorer now applies the separately warranted numeric guard. The unused string-only wrapper is retired. No rag-qa production Ask result is inferred from this direct-scorer metric.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "NG1",
          "quote": "A sentence that states a numeric value the chosen passage does not contain as a whole value is not verified against that passage. Applies to Ask and Delegate, cross-encoder and cosine scoring, supplied passages and index lookups, live and after cold reload. Both WP4.4 counterexamples no longer verify against the passage lacking the number.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "NG3",
          "quote": "An explicit table of accepted and rejected sentence/passage pairs (decision D8) passes: whole values not substrings (99 vs 2099), 1.5 vs 15, signed values, percentages, ranges, dates, the agreed grouping formats, non-Latin digits. Values inside inline code and table cells count as claims.",
          "label": "agent-drafted, owner-adopted"
        }
      ]
    },
    {
      "items": [
        "modules/reranker/src/test/java/io/justsearch/reranker/NumericConsistencyTokenizationTest.java"
      ],
      "class": "New",
      "behaviour": "D8 literal ranges, date parts, groups, digits and adjacent punctuation retain eligibility, including quotations and a preceding sentence terminator.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "NG2",
          "quote": "The guard only withholds a verdict; it never grants one. A sentence whose values all appear in the passage gets exactly the verdict it gets today.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "NG3",
          "quote": "An explicit table of accepted and rejected sentence/passage pairs (decision D8) passes: whole values not substrings (99 vs 2099), 1.5 vs 15, signed values, percentages, ranges, dates, the agreed grouping formats, non-Latin digits. Values inside inline code and table cells count as claims.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "NG7",
          "quote": "Sentences in other languages and non-Latin digits neither crash the check nor change the result except as the table in S13.3 says.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-words",
          "location": "task t-20261019-bd718f agreement",
          "quote": "A number touching anything else (a letter, unit, currency sign, percent, degree sign, plus or minus, slash, dash, colon, another digit group, a second decimal point) is not plain: it is skipped. It never withholds a verdict and never counts as checked."
        },
        {
          "kind": "owner-words",
          "location": "task t-20261019-bd718f agreement",
          "quote": "The passage side is generous: a claim's plain number counts as present when the passage contains that same value as a whole number anywhere, whatever touches it there (in a unit, range, date, currency, sign or percent). A whole number is still never a piece of a longer number (99 is not in 2099; 15 is not in 1.5; 1,200 equals 1200 under the agreed grouping)."
        },
        {
          "kind": "owner-words",
          "location": "task t-20261019-bd718f agreement",
          "quote": "A plain number in the claim is a whole run of digits (non-Latin digits normalised as before), optionally with the agreed groups of three and at most one decimal point, that stands alone: bounded on each side by whitespace, the start or end of the text, or ordinary sentence punctuation (. , ; : ! ? quotes, brackets) that is not itself part of a number. A sentence-final period is punctuation (\"in 2019.\" is 2019)."
        }
      ],
      "evidence": "gates/test-intent/evidence/uiux-numeric-guard-nx3a-preserved.json",
      "newChecks": [
        "modules/reranker/src/test/java/io/justsearch/reranker/NumericConsistencyTokenizationTest.java#preservesAgreedWholeValuesAndPunctuation"
      ]
    },
    {
      "items": [
        "modules/reranker/src/test/java/io/justsearch/reranker/NumericConsistencyPlainTest.java",
        "modules/reranker/src/test/java/io/justsearch/reranker/NumericConsistencyTokenizationTest.java",
        "modules/reranker/src/test/java/io/justsearch/reranker/CitationScorerNumericTest.java",
        "modules/reranker/src/test/java/io/justsearch/reranker/NumericCitationScorerDecimalIntegrationTest.java",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/CitationMatchOpsNumericTest.java"
      ],
      "class": "New",
      "behaviour": "NX3 checks only standalone plain claim numbers; all touching forms are skipped with empty metadata, never vetoing. Passage whole-value containment ignores decoration without joining digit runs or extracting decimal subvalues. Literal X1–X4/Y1–Y4 cases exercise both scorers, raw cosine supplied/index paths, native CPU model, unchanged scores/thresholds and scored counts. Historical NX1/NX2 veto expectations change only where NX3 supersedes them.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "NG1",
          "quote": "A sentence that states a numeric value the chosen passage does not contain as a whole value is not verified against that passage. Applies to Ask and Delegate, cross-encoder and cosine scoring, supplied passages and index lookups, live and after cold reload. Both WP4.4 counterexamples no longer verify against the passage lacking the number.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "NG2",
          "quote": "The guard only withholds a verdict; it never grants one. A sentence whose values all appear in the passage gets exactly the verdict it gets today.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "NG3",
          "quote": "An explicit table of accepted and rejected sentence/passage pairs (decision D8) passes: whole values not substrings (99 vs 2099), 1.5 vs 15, signed values, percentages, ranges, dates, the agreed grouping formats, non-Latin digits. Values inside inline code and table cells count as claims.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "NG4",
          "quote": "Reference literals such as [4], list numbering and the answer's own markers are not treated as stated values.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "NG6",
          "quote": "After the guard, the match list, the matched-sentence count, the marks, the source grades, the coverage note and the frame agree; a rejected sentence still counts as scored, never as not scored. Holds on Ask, Delegate and the raw citation API (RetrieveContextController.java:335-345).",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "NG7",
          "quote": "Sentences in other languages and non-Latin digits neither crash the check nor change the result except as the table in S13.3 says.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-words",
          "location": "task t-20261019-bd718f agreement",
          "quote": "A number touching anything else (a letter, unit, currency sign, percent, degree sign, plus or minus, slash, dash, colon, another digit group, a second decimal point) is not plain: it is skipped. It never withholds a verdict and never counts as checked."
        },
        {
          "kind": "owner-words",
          "location": "task t-20261019-bd718f agreement",
          "quote": "The passage side is generous: a claim's plain number counts as present when the passage contains that same value as a whole number anywhere, whatever touches it there (in a unit, range, date, currency, sign or percent). A whole number is still never a piece of a longer number (99 is not in 2099; 15 is not in 1.5; 1,200 equals 1200 under the agreed grouping)."
        },
        {
          "kind": "owner-words",
          "location": "task t-20261019-bd718f agreement",
          "quote": "A plain number in the claim is a whole run of digits (non-Latin digits normalised as before), optionally with the agreed groups of three and at most one decimal point, that stands alone: bounded on each side by whitespace, the start or end of the text, or ordinary sentence punctuation (. , ; : ! ? quotes, brackets) that is not itself part of a number. A sentence-final period is punctuation (\"in 2019.\" is 2019)."
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "NG3",
          "quote": "Before reading numbers on either side, normalise compatibility forms (Unicode NFKC: full-width digits and punctuation such as \"：\", \"，\", \"．\" become their plain forms) and the Arabic separators (U+066B ARABIC DECIMAL SEPARATOR is a decimal point, U+066C ARABIC THOUSANDS SEPARATOR is a group separator). Then NX3 applies unchanged: \"1٫5\" is the decimal 1.5 (5 is not in it), \"1：30\" is a time and is skipped, \"5，000\" is the grouped 5000.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "NG3",
          "quote": "A grouped number is read the same whatever follows it after whitespace: \"1,200 5kg bags\" contains 1200 (and a non-plain 5kg).",
          "label": "agent-drafted, owner-adopted"
        }
      ],
      "evidence": "gates/test-intent/evidence/uiux-numeric-guard-nx3a-repaired.json",
      "newChecks": [
        "modules/reranker/src/test/java/io/justsearch/reranker/NumericConsistencyPlainTest.java#skipsTouchingFormsWithoutWithholding",
        "modules/reranker/src/test/java/io/justsearch/reranker/NumericConsistencyPlainTest.java#acceptsWholePassageValuesWhateverTouchesThem",
        "modules/reranker/src/test/java/io/justsearch/reranker/NumericConsistencyPlainTest.java#requiresEveryPlainValueEvenBesideSkippedForms",
        "modules/reranker/src/test/java/io/justsearch/reranker/NumericConsistencyPlainTest.java#followsExplicitWhitespaceAndPunctuationBoundaries",
        "modules/reranker/src/test/java/io/justsearch/reranker/NumericConsistencyTokenizationTest.java#comparesNx2FormerlyWithheldDecidedForms",
        "modules/reranker/src/test/java/io/justsearch/reranker/NumericConsistencyTokenizationTest.java#checksFormerNx2RejectionsUnderNx3",
        "modules/reranker/src/test/java/io/justsearch/reranker/NumericConsistencyTokenizationTest.java#extractsNx2DecidedValuesBesideUndecidedForms",
        "modules/reranker/src/test/java/io/justsearch/reranker/NumericConsistencyTokenizationTest.java#ignoresNx2UnitExponents",
        "modules/reranker/src/test/java/io/justsearch/reranker/NumericConsistencyTokenizationTest.java#skipsFormerNx1ClaimForms",
        "modules/reranker/src/test/java/io/justsearch/reranker/NumericConsistencyTokenizationTest.java#doesNotWithholdFormerlyUnsupportedClaims",
        "modules/reranker/src/test/java/io/justsearch/reranker/CitationScorerNumericTest.java#appliesNx3VerifierCounterexamples",
        "modules/reranker/src/test/java/io/justsearch/reranker/CitationScorerNumericTest.java#checksFormerNx2RejectionsUnderNx3",
        "modules/reranker/src/test/java/io/justsearch/reranker/CitationScorerNumericTest.java#retainsNx2DecidedFormsAndUnrelatedPassageNotation",
        "modules/reranker/src/test/java/io/justsearch/reranker/CitationScorerNumericTest.java#retainsNx2UnitOnlyExactMatches",
        "modules/reranker/src/test/java/io/justsearch/reranker/CitationScorerNumericTest.java#skipsFormerNx1ClaimsWithoutChangingScores",
        "modules/reranker/src/test/java/io/justsearch/reranker/NumericCitationScorerDecimalIntegrationTest.java#skipsFormerNx2FormsThroughRealModel",
        "modules/reranker/src/test/java/io/justsearch/reranker/NumericCitationScorerDecimalIntegrationTest.java#skipsLeadingDecimalsThroughRealModel",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/CitationMatchOpsNumericTest.java#appliesNx3VerifierCounterexamples",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/CitationMatchOpsNumericTest.java#checksFormerNx2RejectionsUnderNx3",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/CitationMatchOpsNumericTest.java#retainsNx2DecidedFormsAndUnrelatedPassageNotation",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/CitationMatchOpsNumericTest.java#retainsNx2UnitOnlyExactMatches",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/CitationMatchOpsNumericTest.java#skipsLeadingDecimalsOnRawCosineSuppliedAndLookupPaths",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/CitationMatchOpsNumericTest.java#skipsNonPlainFormsAfterMarkdownSegmentation"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/reranker/src/test/java/io/justsearch/reranker/NumericConsistencyPlainTest.java",
        "modules/reranker/src/test/java/io/justsearch/reranker/NumericConsistencyTokenizationTest.java",
        "modules/reranker/src/test/java/io/justsearch/reranker/CitationScorerNumericTest.java"
      ],
      "class": "New",
      "behaviour": "Plain grouping, leading zeros, digit normalization, code/reference distinctions and unchanged numeric-free keys retain their literal expectations. Existing .5-to-0.5 equivalences remain eligible while NX3 skips nonplain claim forms.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "NG2",
          "quote": "The guard only withholds a verdict; it never grants one. A sentence whose values all appear in the passage gets exactly the verdict it gets today.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "NG3",
          "quote": "An explicit table of accepted and rejected sentence/passage pairs (decision D8) passes: whole values not substrings (99 vs 2099), 1.5 vs 15, signed values, percentages, ranges, dates, the agreed grouping formats, non-Latin digits. Values inside inline code and table cells count as claims.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "NG4",
          "quote": "Reference literals such as [4], list numbering and the answer's own markers are not treated as stated values.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "NG7",
          "quote": "Sentences in other languages and non-Latin digits neither crash the check nor change the result except as the table in S13.3 says.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "GN1",
          "quote": "Sentences without numbers get exactly the same matches as before on a fixed corpus.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-words",
          "location": "task t-20261019-bd718f agreement",
          "quote": "A number touching anything else (a letter, unit, currency sign, percent, degree sign, plus or minus, slash, dash, colon, another digit group, a second decimal point) is not plain: it is skipped. It never withholds a verdict and never counts as checked."
        },
        {
          "kind": "owner-words",
          "location": "task t-20261019-bd718f agreement",
          "quote": "The passage side is generous: a claim's plain number counts as present when the passage contains that same value as a whole number anywhere, whatever touches it there (in a unit, range, date, currency, sign or percent). A whole number is still never a piece of a longer number (99 is not in 2099; 15 is not in 1.5; 1,200 equals 1200 under the agreed grouping)."
        },
        {
          "kind": "owner-words",
          "location": "task t-20261019-bd718f agreement",
          "quote": "A plain number in the claim is a whole run of digits (non-Latin digits normalised as before), optionally with the agreed groups of three and at most one decimal point, that stands alone: bounded on each side by whitespace, the start or end of the text, or ordinary sentence punctuation (. , ; : ! ? quotes, brackets) that is not itself part of a number. A sentence-final period is punctuation (\"in 2019.\" is 2019)."
        }
      ],
      "evidence": "gates/test-intent/evidence/uiux-numeric-guard-nx3a-preserved.json",
      "newChecks": [
        "modules/reranker/src/test/java/io/justsearch/reranker/NumericConsistencyPlainTest.java#preservesPlainDigitNormalizationAndGrouping",
        "modules/reranker/src/test/java/io/justsearch/reranker/NumericConsistencyPlainTest.java#preservesPunctuationReferencesAndCode",
        "modules/reranker/src/test/java/io/justsearch/reranker/NumericConsistencyTokenizationTest.java#acceptsOnlyExplicitNx1Equivalences",
        "modules/reranker/src/test/java/io/justsearch/reranker/CitationScorerNumericTest.java#repairsExplicitNx1EquivalencesWithoutChangingScoresOrThresholds"
      ]
    },
    {
      "items": [
        "modules/reranker/src/test/java/io/justsearch/reranker/NumericConsistencyPlainTest.java"
      ],
      "class": "New",
      "behaviour": "NX2 regressions Y2/Y3: never join 1$5 into 15 or extract 1 from 1.2.3e4. This unchanged check fails on assigned immediate baseline 062011106 and passes on known-good d36738d92. The other NX3 repair checks use d36738d92 as requested.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "NG1",
          "quote": "A sentence that states a numeric value the chosen passage does not contain as a whole value is not verified against that passage. Applies to Ask and Delegate, cross-encoder and cosine scoring, supplied passages and index lookups, live and after cold reload. Both WP4.4 counterexamples no longer verify against the passage lacking the number.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "NG2",
          "quote": "The guard only withholds a verdict; it never grants one. A sentence whose values all appear in the passage gets exactly the verdict it gets today.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "NG3",
          "quote": "An explicit table of accepted and rejected sentence/passage pairs (decision D8) passes: whole values not substrings (99 vs 2099), 1.5 vs 15, signed values, percentages, ranges, dates, the agreed grouping formats, non-Latin digits. Values inside inline code and table cells count as claims.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "NG4",
          "quote": "Reference literals such as [4], list numbering and the answer's own markers are not treated as stated values.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "NG6",
          "quote": "After the guard, the match list, the matched-sentence count, the marks, the source grades, the coverage note and the frame agree; a rejected sentence still counts as scored, never as not scored. Holds on Ask, Delegate and the raw citation API (RetrieveContextController.java:335-345).",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "NG7",
          "quote": "Sentences in other languages and non-Latin digits neither crash the check nor change the result except as the table in S13.3 says.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-words",
          "location": "task t-20261019-bd718f agreement",
          "quote": "A number touching anything else (a letter, unit, currency sign, percent, degree sign, plus or minus, slash, dash, colon, another digit group, a second decimal point) is not plain: it is skipped. It never withholds a verdict and never counts as checked."
        },
        {
          "kind": "owner-words",
          "location": "task t-20261019-bd718f agreement",
          "quote": "The passage side is generous: a claim's plain number counts as present when the passage contains that same value as a whole number anywhere, whatever touches it there (in a unit, range, date, currency, sign or percent). A whole number is still never a piece of a longer number (99 is not in 2099; 15 is not in 1.5; 1,200 equals 1200 under the agreed grouping)."
        },
        {
          "kind": "owner-words",
          "location": "task t-20261019-bd718f agreement",
          "quote": "A plain number in the claim is a whole run of digits (non-Latin digits normalised as before), optionally with the agreed groups of three and at most one decimal point, that stands alone: bounded on each side by whitespace, the start or end of the text, or ordinary sentence punctuation (. , ; : ! ? quotes, brackets) that is not itself part of a number. A sentence-final period is punctuation (\"in 2019.\" is 2019)."
        }
      ],
      "evidence": "gates/test-intent/evidence/uiux-numeric-guard-nx3a-whole-values-repaired.json",
      "newChecks": [
        "modules/reranker/src/test/java/io/justsearch/reranker/NumericConsistencyPlainTest.java#rejectsSubvaluesAndCurrencyJoining"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/reranker/src/test/java/io/justsearch/reranker/NumericConsistencyPlainTest.java",
        "modules/reranker/src/test/java/io/justsearch/reranker/CitationScorerNumericTest.java",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/CitationMatchOpsNumericTest.java"
      ],
      "class": "New",
      "behaviour": "NX3a normalizes compatibility forms and Arabic decimal/group separators on both sides; grouped values retain identity before subsequent whitespace-separated quantities. Literal counterexamples and neighbours assert claim metadata, cross-encoder verdicts/scores/coverage, and raw cosine supplied/indexed matches (F4).",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "NG1",
          "quote": "A sentence that states a numeric value the chosen passage does not contain as a whole value is not verified against that passage. Applies to Ask and Delegate, cross-encoder and cosine scoring, supplied passages and index lookups, live and after cold reload. Both WP4.4 counterexamples no longer verify against the passage lacking the number.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "NG2",
          "quote": "The guard only withholds a verdict; it never grants one. A sentence whose values all appear in the passage gets exactly the verdict it gets today.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "NG3",
          "quote": "An explicit table of accepted and rejected sentence/passage pairs (decision D8) passes: whole values not substrings (99 vs 2099), 1.5 vs 15, signed values, percentages, ranges, dates, the agreed grouping formats, non-Latin digits. Values inside inline code and table cells count as claims.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "NG6",
          "quote": "After the guard, the match list, the matched-sentence count, the marks, the source grades, the coverage note and the frame agree; a rejected sentence still counts as scored, never as not scored. Holds on Ask, Delegate and the raw citation API (RetrieveContextController.java:335-345).",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "NG7",
          "quote": "Sentences in other languages and non-Latin digits neither crash the check nor change the result except as the table in S13.3 says.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "NG3",
          "quote": "Before reading numbers on either side, normalise compatibility forms (Unicode NFKC: full-width digits and punctuation such as \"：\", \"，\", \"．\" become their plain forms) and the Arabic separators (U+066B ARABIC DECIMAL SEPARATOR is a decimal point, U+066C ARABIC THOUSANDS SEPARATOR is a group separator). Then NX3 applies unchanged: \"1٫5\" is the decimal 1.5 (5 is not in it), \"1：30\" is a time and is skipped, \"5，000\" is the grouped 5000.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "NG3",
          "quote": "A grouped number is read the same whatever follows it after whitespace: \"1,200 5kg bags\" contains 1200 (and a non-plain 5kg).",
          "label": "agent-drafted, owner-adopted"
        }
      ],
      "evidence": "gates/test-intent/evidence/uiux-numeric-guard-nx3a-repaired.json",
      "newChecks": [
        "modules/reranker/src/test/java/io/justsearch/reranker/NumericConsistencyPlainTest.java#appliesNx3aNormalizationAndGroupedBoundaries",
        "modules/reranker/src/test/java/io/justsearch/reranker/CitationScorerNumericTest.java#appliesNx3aNormalizationAndGroupedBoundaries",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/CitationMatchOpsNumericTest.java#appliesNx3aNormalizationAndGroupedBoundaries"
      ],
      "repaired": true
    }
  ]
}
```

# Measurement and verification notes

The first real-model integration-source attempt did not execute: that suite
lacked the ONNX runtime. Adding it there required a new dependency lock entry,
so the new model check instead lives in the existing unit suite, which already
has that runtime. Only the local ORT test-fixture dependency was added. The
fail-before runner overlays that dependency-only build file; no production
repair is overlaid onto the baseline.

The first timing pair overlapped other checks and had different competing load;
it is not used to judge the bound. The isolated pair runs only the timing method
in sequence, with the protocol above, on the same i7-12700K / Java 25.0.4.1 /
ORT 1.24.3 and identical fixture/model hashes. Baseline five suite intervals:
206537468, 262109017, 259216830, 234322166, 209981205 ns (median 234322166).
Candidate: 156463941, 158093332, 179336034, 174737044, 182375029 ns
(median 174737044; ratio 0.7457). Every run scores all six fixture sentences;
matched counts change from [1,1,1,1,0,1] to [0,0,1,1,0,1]. Both measurements
explicitly force fresh execution with --rerun; cached test reports are not new
timing runs. Shared-machine load varied (baseline 9.23–10.90, candidate
4.11–4.11), so this is a recorded scorer-level bound observation,
not a causal speedup or a live Ask claim.

Raw test-generated timing artifacts:
[before](../evidence/uiux-numeric-guard-performance-before.xml) and
[after](../evidence/uiux-numeric-guard-performance-after.xml).

Scenario evidence map (builder observations, not independent acceptance):

| Scenario | Evidence / remaining limit |
| --- | --- |
| NG1 | Repaired JSON: both real and deterministic cross-encoder checks; raw cosine supplied/lookup and long-document checks. Ask/Delegate transport and record behavior use the preservation checks. Running-system/browser combinations remain for the verifier. |
| NG2 | Preserved JSON: literal compatibility, exact scores, thresholds; repaired JSON: below-threshold alternate cannot gain a match. |
| NG3 | Literal table above; cross-encoder table and cosine segmentation checks in both JSON files. |
| NG4 | Reference/ordinal preservation and code/array/math rejection checks. |
| NG5 | Both repaired best-of checks; returned window/source indices address the compatible passage. |
| NG6 | Raw Worker counts plus Ask live/store reload, Delegate events/history, and HTTP-handler preservation checks; existing frontend coverage/projection suite checks its consumers. Browser/live Engine verification remains unrun. |
| NG7 | Arabic and fullwidth numeric cases in the literal and Worker checks; numeric-free Han/German corpus preserved. |
| NG8 | Isolated model timings above, no newly unscored fixture. Production Ask fixed-question timing remains unverified without the dev stack. |
| NG9 | Unverified: no production Ask rag-qa run was available. The live protocol above describes the required remaining measurement. Direct-scorer counts are not substituted. |
| GN1 | Exact numeric-free cross-encoder and raw cosine corpus checks pass before and after. |
| GN2 | Existing RAGContextVerificationSourcesTest (S7.1/S7.3/S7.8), ShortDocumentPassageVerificationTest (S7.2), and literal passage tests pass in focused baseline/candidate runs. |
| GN3 | Old wrong-year record and new rejection survive a fresh store reload; Delegate mapping preserves the stamp. Existing frontend restored-citation projection passes. Browser cold-start comparison remains unrun. |
| GN4 | Existing anchoring/projection/coverage/source-resolution frontend tests pass before (190 checks) and the full candidate suite passes (7110 checks); MCP answer checks are recorded with the final validation. |

All added test files are durable. The five-run method is durable measurement
instrumentation; its local timing XML is supporting measurement evidence. No
acceptance record is authored here. The orchestrator/verifier owns acceptance.

The test-intent CLI currently reports only the missing independent acceptance
record, which this builder was explicitly forbidden to write. It also reports
`WARNING test-intent/reconsider-rate`: 1 of 8 audited PRs required third-reviewer
tie-breaks in the last 28 days (12.5%, above one in ten). This is a finding for the
orchestrator's delivery, not a failure of the implementation checks.

Tests added or rewritten:

| File | Scenarios | Status |
| --- | --- | --- |
| CitationScorerNumericTest.java | NG1–NG5, NG7, GN1 | durable |
| CitationMatchOpsNumericTest.java | NG1–NG7, GN1 | durable |
| NumericCitationScorerIntegrationTest.java (unit source set) | NG1, NG6, NG8 scorer measurement | durable |
| NumericCitationHistoryTest.java | NG6, GN3 | durable |
| AgentNumericCitationRejectionTest.java | NG6, GN3 | durable |
| RetrieveContextNumericRejectionTest.java | NG6 | durable |
| RagQualityEvalTest.java | NG1/NG3 metadata input adaptation | existing durable evaluator; live rag-qa not run |

Before-failure examples (the repaired JSON records every selected check):

- `rejectsLiteralIncompatiblePairs`: incompatible 2099/1883 pair expected no
  match, got `ScoredMatch(... score=0.9760000109672546)`.
- `reselectsCompatibleWindowAcrossSubBatches`: expected window 20, got 0.
- `neverPromotesAnAlternativeBelowThreshold`: expected no match, got the
  incompatible window at 0.9760000109672546.
- `rejectsRealModelCounterexamplesAndKeepsCoverage`: 2099/1883 expected no
  match, got a real-model match at 0.9978494048118591.
- Raw cosine counterexample and long-lookup checks: expected zero matches, got
  one; cosine reselection expected source 1, got 0.
- Segmentation check: inline-code `[4]` expected zero matches, got one.

The manual before checkout was a shared clone of 76efc1c10 under the assigned
TMPDIR and was removed after the comparisons; the evidence tool's managed before checkouts were removed by that tool.
No model, corpus or installer was copied into the checkouts. No dev stack was
started, no process owned by another session was stopped, and no commit was made.

The late Unicode-reference preservation cases passed on the unchanged baseline,
failed by assertion on the first guard candidate (cross-encoder expected the
original match but got none; cosine expected one match but got zero), and passed
after reference boundaries were defined using Unicode punctuation categories.
The added corpus cases retain both sentence keys and exact raw scores. These
cases add no numeric representation equivalence.

Final validation commands and observations are recorded in the builder handoff;
McpAnswerLegibilityTest passes on the baseline and candidate with fresh test
execution. The full frontend suite and all 28 UI gates passed. Live NG8/NG9 and
browser cold-start checks remain explicitly unverified as stated above.

Final command results:

- `js-gradle-slot ./gradlew build -x test -PskipWebBuild=true`: BUILD SUCCESSFUL
  on the final production code (1m 21s; 332 actionable tasks).
- Focused reranker, Worker, Ask, Delegate and raw HTTP-handler tests: passed;
  `pmdMain` for reranker, worker-services, app-services, app-agent and ui: passed.
- New reference-preservation corpus: baseline passed, first candidate assertion
  failures recorded, corrected candidate passed; no scenario or threshold weakened.
- Script-generated evidence: 17 new checks executed/passed; all eight repair
  checks have assertion fail-before evidence on 76efc1c10.
- `npm run lint`, `npm run typecheck`, `npx vitest run`: exit 0; 538 files /
  7110 frontend tests passed. `node scripts/ci/run-ui-web-gates.mjs`: 28/28 passed.
- `McpAnswerLegibilityTest`: fresh baseline and candidate runs passed.
- Test-intent: only independent acceptance is missing; the rolling reconsider-rate
  warning is recorded above. The builder has not authored an acceptance record.


## NX2 builder observations (2026-10-21; implementation and execution evidence)

Production changes are confined to NumericConsistency.java, still shared by both
scorers. An undecided claim withholds; an undecided passage form contributes no
digit pieces and cannot veto separate decided values. Sentence keys, scores,
thresholds, persistence formats and the UI producer gate retain their previous
ownership. The literal table has 368 distinct pairs after applying NX2's explicit
overrides; the standalone table sweep passes all 368. Its temporary harness is
scaffolding, not a durable product check.

Final script-written evidence (all checks executed, no selected check skipped):

| Artifact under gates/test-intent/evidence/ | Checks | Before |
| --- | --- | --- |
| uiux-numeric-guard-nx2-repaired.json | 11 pass | all 11 assertion-fail on d36738d92 |
| uiux-numeric-guard-nx2-refreshed-nx1-repaired.json | 8 pass | all 8 assertion-fail on 3ec058b1e |
| uiux-numeric-guard-nx2-refreshed-original-repaired.json | 7 pass | all 7 assertion-fail on 76efc1c10 |
| uiux-numeric-guard-nx2-refreshed-preserved.json | 7 pass | separate preservation run passes on d36738d92 |

Every earlier JSON/XML evidence artifact is retained byte-for-byte. Fresh
script-produced evidence was moved to the separate NX2 names above so refreshed
test-file digests do not overwrite the earlier historical evidence.

Before-failure observations on unchanged d36738d92:

- rejectsNx2CounterexamplesThroughRealModel: expected an empty match list;
  %-5/5 returned score 0.9739656448364258, Turkish %-5/5 returned
  0.8926525712013245, and the hexadecimal pieces also returned a match.
- rejectsNx2PrefixPercentAndUndecidedPieces: expected zero raw cosine matches,
  got one on both supplied and indexed paths for prefix-percent and hexadecimal
  counterexamples. The deterministic cross-encoder loop similarly returned a
  ScoredMatch where an empty list was required.
- retainsNx2DecidedFormsAndUnrelatedPassageNotation: dates, unit ranges,
  prices, clocks and dotted versions expected the exact original match but got
  none; a supported 1883 claim with an unrelated $5 also got none.
- retainsNx2UnitOnlyExactMatches / ignoresNx2UnitExponents: expected the exact
  original match / numeric-free metadata for m² and cm³, got no match / nonempty
  metadata. All three unit-only checks and both existing numeric-free corpus
  checks pass on known-good 3ec058b1e (five selected methods).

The added spaced-percentage range preservation cases passed on d36738d92,
failed by assertion on the first NX2 candidate, and pass after fixing the range
normalization. No pre-existing expectation outside NX2's explicit overrides was
removed or weakened. The baseline token/Gradle XML, first-candidate token failure,
and final comparison logs are retained under tmp/nx2/.

Tests added or rewritten (all durable):

| File | Scenarios |
| --- | --- |
| NumericConsistencyTokenizationTest.java | NG1, NG2, NG3, NG7, GN1 |
| CitationScorerNumericTest.java | NG1, NG2, NG3, NG7, GN1 |
| CitationMatchOpsNumericTest.java | NG1, NG2, NG3, NG6, NG7, GN1; F4 raw results |
| NumericCitationScorerDecimalIntegrationTest.java | NG1, NG3, NG7; actual CPU model |

Final validation (every Gradle call through js-gradle-slot, including the
containing sequential evidence/check script; at most one invocation at a time):

- Focused numeric reranker/Worker suites pass, including every old pair/check
  that NX2 does not supersede and the NX1 leading-decimal repair.
- One hundred selected preservation checks pass before and after without skips:
  reranker 4, worker-services 35, app-services 44, app-agent 1, ui 16. These cover
  fixed numeric-free exact matches, NG4-NG6, WP4.4 passage/source checks, Ask event
  and file-store reloads, Delegate mapping, HTTP-handler counts and MCP output.
- pmdMain passes for reranker, worker-services, app-services, app-agent and ui.
- ./gradlew build -x test -PskipWebBuild=true: BUILD SUCCESSFUL, 1m 28s,
  332 actionable tasks. Model-gated integration skips in this normal-environment
  build are not claimed as passes; explicit CPU regression evidence executes.
- npm run lint and npm run typecheck: exit 0. Full npx vitest run --maxWorkers=4:
  555 files / 7240 tests passed. Root UI gates: 28/28 passed.

The scorer measurement follows the existing F5 protocol and identical six
fixtures/model hashes: CPU model, two warm-up suites, five timed suite sums,
2000ms deadline, one Gradle worker, resource snapshots in tmp/nx2/. XML is retained
as uiux-numeric-guard-nx2-performance-{before,after}.xml. The five-run median is
196163488 ns before and 182841069 ns after (ratio 0.932085, within 10%). All six
fixtures are scored in all five runs on each side. This is a scorer measurement;
it is not production Ask question-set timing or a rag-qa result.

Remaining unverified in this builder assignment: live/cold Engine and browser
combinations (NG1/NG6/GN3), production Ask timing/coverage (NG8), and production Ask
rag-qa before/after verified counts (NG9). The dev stack was expressly unavailable;
backend, file-store and HTTP-handler checks do not substitute for those checks.

The manual before and known-good copies were shared clones inside the assigned
TMPDIR: d36738d92 and 3ec058b1e, respectively. Each received its own hard-linked
frontend dependencies only where package-lock.json matched. Both clones were
removed after comparison; the evidence runner removed its managed before
worktrees. No model, corpus or installer was copied or changed. No dev server was
started and no other session's process was stopped. The queued evidence wrapper
that was canceled before acquiring a slot is recorded as exit 143; the completed
sequential evidence/check runner exits 0. No commit, push or acceptance record
was made.

Test-intent coverage/evidence is complete. Independent acceptance remains stale
and must be supplied by the verifier; the historical rejection is retained.
The gate also reports reconsider-rate: 1 third-reviewer tie-break among 9 audited
PRs in 28 days (11.1%, above one in ten), a non-failing delivery finding.

## NX3 builder observations (2026-10-22; no acceptance verdict)

Only NumericConsistency.java changed in production: 258 → 158 lines. Both scorers still
share it; no score/threshold, best-window loop, scored count, key, persistence or UI-gate
code changed. The five durable test files above retain prior literal inputs and change only
form expectations superseded by NX3. Code/ordinal and ellipsis preservation controls were added.

Fresh script-written evidence (no candidate check skipped):

| Artifact under gates/test-intent/evidence/ | Observed result |
| --- | --- |
| uiux-numeric-guard-nx3-repaired.json | 24 candidate passes; 23 assertion failures on d36738d92; whole-value check passes there (known-good) |
| uiux-numeric-guard-nx3-whole-values-repaired.json | The unchanged whole-value check passes candidate and assertion-fails on assigned immediate baseline 062011106 for Y2/Y3 |
| uiux-numeric-guard-nx3-preserved.json | 11 candidate passes |
| uiux-numeric-guard-nx3-original-repaired.json | 7 candidate passes, 7 assertion failures on 76efc1c10 |

The first combined evidence command exited 1 because the whole-value check already passes
on d36738d92; no expectation was changed. Its entry uses the separate 062011106 evidence.
The original artifact is retained unedited and also records that known-good pass. All earlier
76efc1c10/NX1/NX2 JSON/XML evidence remains byte-for-byte unchanged.

Actual Gradle scorer suite: 42 tests, zero failures/errors/skips. Final preservation selection:
75 tests pass on 062011106 and on the candidate, zero skips. It includes the fixed numeric-free
corpus, 37 WP4.4 source/passage checks, Ask live-payload/file-store reload controls, Delegate
mapping, HTTP-handler counts and MCP formatting. Both fixed numeric-free checks also pass
on known-good 76efc1c10. These are component checks, not a claim of live browser/Engine coverage.

The unchanged F5 six-fixture scorer protocol used the same read-only CPU model/fixture hashes,
two warm-ups and five timed suite sums, one Gradle worker and the same 2000 ms deadline.
Median: 161234513 ns before, 160337003 ns after; ratio 0.994434 (within 10%). All six fixtures
remain scored in every run. Raw XML is uiux-numeric-guard-nx3-performance-{before,after}.xml.
This is scorer timing only; NG8 production Ask timing/timeout coverage and NG9 production
rag-qa verified counts remain unverified because the stack was unavailable.

pmdMain succeeds for reranker, worker-services, app-services, app-agent and ui (four tasks
executed, one from cache). Frontend lint/typecheck succeed; full Vitest passes 561 files /
7273 tests; root UI gates pass 28/28. No frontend source, package.json or lockfile changed.

`js-gradle-slot ./gradlew build -x test -PskipWebBuild=true` exits 1: UI integration tests
run despite excluding the unit-test task. RuntimeReloadHttpIntegrationTest has seven
fixture-constructor timeouts (33 UI integration cases: 7 failures, 1 skip). The exact suite
also fails all 7 on untouched 062011106. It passes all 7 on origin/main pinned at
7dc1f3eb45274805bc6612b71b679413bbe617ff, which includes fixture repair 98b2507d4
(`test(ui): model both built-in reload subscriptions (#895)`). This unrelated fixture was
not edited in this assignment. The orchestrator must bring in the existing main repair
before claiming a green full build. Logs/XML: tmp/nx3/build.log, reload-before.log,
reload-origin-main.log, and the corresponding retained result directories.

The test-intent gate validates all sources, coverage and evidence, and fails only for stale
independent acceptance. No acceptance record was written or altered. Initial source-location
format errors were corrected to the canonical task-agreement location, preserving the owner
quotes. Independent acceptance is left to the verifier/orchestrator.

Not checked here: full live/cold Engine/browser NG1/NG6 combinations; production NG8/NG9;
full browser rendering of legacy records (GN3). Y5 is explicitly out of scope and untouched.
Shared resources were Gradle cache/slot locks and read-only local models; no owner app,
dev stack or GPU was used or stopped. All manual before/known-good/origin-main shared clones
under the assigned TMPDIR are removed; evidence runner worktrees cleaned themselves up.
No frontend dependency directory, external corpus, model directory or installer was copied into comparison trees. No commit/push.
Detailed builder report: tmp/nx3/builder-report.md.

## NX3a builder observations (2026-10-22; no acceptance verdict)

Production changes are confined to NumericConsistency.java (158 → 164 lines):
NFKC and Arabic separator normalization precede numeric parsing on both sides;
a grouped value can end at whitespace before a later quantity. Scorer loops,
thresholds, sentence keys, counts, persistence and UI code are unchanged.
The new literal table contains 39 pairs, including the two historical Arabic
skip pairs now rejected under NX3a. No input was discarded to preserve that old oracle.

All three new methods assertion-fail on unchanged e9f080f32 with zero skips:
plain 5 against 1٫5 yields support; full-width time/grouping metadata is wrong;
1200 against 1,200 5kg loses support, while its 200 subvalue incorrectly gains it.
Cross-encoder failure returns a 0.9760000109672546 match instead of an empty list;
raw cosine returns one match instead of zero on both supplied and indexed paths.
Retained XML and production-helper identity: tmp/nx3a/before-repair-final-results/
and tmp/nx3a/before-production-identity.json. The helper-only before sweep has
38 failed assertions; all 16 helper methods pass afterward.

Focused candidate selection: 85 tests, zero failures/errors/skips. Preservation:
75 tests before and after, zero failures/errors/skips, including exact numeric-free
matches (GN1), NG4–NG6, WP4.4 passage/source checks, file-store history, Delegate
mapping, HTTP-handler counts and MCP output.

Fresh run-evidence.mjs output (default dependency-link mode; no copy option):

| NX3a artifact suffix | Candidate | Assertion fail-before |
| --- | --- | --- |
| original-repaired.json | 7 pass | 7 on 76efc1c10 |
| preserved.json | 11 pass | preservation checked separately on e9f080f32 |
| repaired.json | 26 pass | 26 on d36738d92 |
| whole-values-repaired.json | 1 pass | 1 on 062011106 (existing historical repair) |

The existing six-fixture CPU scorer protocol used identical model/fixture hashes,
two warm-ups, five timed suite sums and one Gradle worker. Median: 282989260 ns
before, 203356547 ns after; ratio 0.718602, within the 10% bound. All six fixtures
remain scored in every run. Raw XML is uiux-numeric-guard-nx3a-performance-{before,after}.xml.
This is scorer timing; differing shared-machine load does not establish a speedup.

All Gradle calls ran through js-gradle-slot (the containing script holds the slot
for its child Gradle calls). pmdMain passes for reranker, worker-services,
app-services, app-agent and ui. build -x test -PskipWebBuild=true succeeds in
2m 32s, 332 actionable tasks; optional integration skips are not claimed as passes.
Frontend lint/typecheck pass, full Vitest passes 563 files / 7285 tests, and
all 28 root UI gates pass. No frontend source, manifest or lockfile changed.

Durable tests added/rewritten: NumericConsistencyPlainTest (NG1/NG3/NG7),
CitationScorerNumericTest (NG1/NG2/NG3/NG6/NG7), CitationMatchOpsNumericTest
(the same scenarios; F4 observes raw results), NumericConsistencyTokenizationTest
(NG3/NG7; compatibility cases move from the superseded skip list to the table).
The first candidate's sole reranker failure was a stale Arabic skip expectation;
that exact pair and its Worker counterpart remain in the new rejection table.

Every earlier JSON/XML artifact and every acceptance record remains unchanged.
Test-intent validates sources, coverage and evidence; only independent acceptance
is stale. The builder has written no acceptance record and made no commit/push.
The manual shared clone under the assigned TMPDIR and all evidence-runner
checkouts are removed. No dependency/model/corpus/installer was copied.
Shared resources: Gradle caches and slot locks; read-only CPU model files.
No owner app, dev stack or GPU was used or stopped. One initial queued command
was canceled (143) to move the before run into its isolated clone; both completed
comparison runners have retained logs. Queue waits account for most elapsed time.

Still unverified under this assignment's unavailable-stack constraint: full
live/cold Engine/browser NG1/NG6 combinations, browser legacy-record rendering
(GN3), production Ask timing/coverage (NG8), and production Ask rag-qa counts (NG9).
Component/frontend checks do not substitute for those running-system checks.

## Acceptance records

```json
{
  "kind": "acceptance",
  "role": "verifier",
  "session": "wp4.5-verifier-dvn67dxr",
  "verdict": "reject",
  "digest": "sha256:f8b76f3d681d78b7a5058cb34c8b0f870c159ba6328168132c50049320da968e",
  "note": "NG1/NG3 and D8/F1 fail for decimals without a leading zero: .5, -.5 and +.5% are treated as 5, 5 and 5%. Both scorers accept the mismatch; real HTTP Ask and Delegate publish verified citations, and both browser windows render the mark. The 41 literal pairs pass, but these extra equivalences were not agreed. See verifier evidence dvn67dxr."
}
```

```json
{
  "kind": "acceptance",
  "role": "verifier",
  "session": "wp4.5-verifier-1ahxvgxn",
  "verdict": "reject",
  "digest": "sha256:0d9ce40fdf7e9cc3cd8a917623d93fce17e4be5a017a0531695c6c7b2d62d0e7",
  "note": "NG1/F1/NX1 fail: %-5 verifies against 5 (real CPU scorers, Ask, Delegate, both windows and cold reload); 0x10 still verifies against 0 and 10. D8/GN1 regress: date parts, unit ranges and m²-only prose lose marks; an unrelated $5 in a passage vetoes its supported 1883 claim. The 330 literal pairs and the leading-decimal repair pass. Evidence: /home/elias/.cache/am/1ahxvgxn/evidence."
}
```

```json
{
  "kind": "acceptance",
  "role": "verifier",
  "session": "7qhrmqrm",
  "verdict": "reject",
  "digest": "sha256:66d26e42edd0775c1f5608dba08b141339d0a85adc6d67426efbe3a10f1488e0",
  "note": "Reject: the literal checks execute (39/39; NX2 11 assertion failures before, 11 passes after), but omit boundaries required by D8/F1/NX1/NX2. A supported spaced unit range (5 kg - 10 kg) loses its mark; -10 kg verifies against 5 kg -10 kg; 1 verifies against undecided 1.2.3e4; currency deletion invents 15 from 1$5 (real cosine API); percent after a unit is lost (5 against 5kg%). Real private Ask/Delegate/API and Chromium evidence confirms applicable failures. NG6 also fails in the pre-existing old Delegate cosine frame: zero marks but Grounded 1 of 1. Evidence: /home/elias/dev/justsearch/tmp/verify/wp4.5/verifier-3/verification-report.md. NG8 production Ask and NG9 shipped-model counts remain unverified; scorer-only timing meets D10."
}
```

```json
{
  "kind": "acceptance",
  "role": "verifier",
  "session": "wp45-nx3-8dh3rho2",
  "verdict": "accept",
  "digest": "sha256:e43aa1cb618ea26e3ec6518afd9ac9d4014973900bcaab1477129567649f7e96",
  "note": "Expectations accepted under NX3; 48 named checks rerun with zero skips. Five selected repairs assertion-fail on 062011106 and seven on 76efc1c10. This accepts test intent, not the implementation: independent grouped-value and Unicode-form checks fail. Evidence: /home/elias/.cache/am/8dh3rho2/evidence."
}
```
