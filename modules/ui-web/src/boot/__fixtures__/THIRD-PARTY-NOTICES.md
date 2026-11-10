# Third-party notices — boot test fixtures (`src/boot/__fixtures__/`)

`tauri-drag.js.txt` is a **verbatim copy of a Tauri source file**, kept as a test fixture so
`boot-frame-lockdown.test.ts` can inject the same drag-region script the desktop runtime injects,
without a local Cargo registry. It is not part of any build or package. This notice travels with
the copy, as the MIT license requires.

| Component | Holder | License | Source | What is copied |
|---|---|---|---|---|
| Tauri 2.11.5 | Tauri Apps Contributors (Tauri Programme within The Commons Conservancy) | Apache-2.0 OR MIT (MIT elected here) | github.com/tauri-apps/tauri, crate `tauri` 2.11.5 | `src/window/scripts/drag.js`, unchanged below the fixture's header, including its own copyright and SPDX lines |

> **Placement note.** The repo's root `THIRD_PARTY_NOTICES` is a machine projection of the
> dependency manifests (`scripts/codegen/gen-notices.mjs`, "do NOT edit by hand") and already lists
> `tauri 2.11.5 — Apache-2.0 OR MIT` as a dependency of the shell. A copied source file is not a
> manifest entry, so, as for `shell-v0/views/search-v3/THIRD-PARTY-NOTICES.md`, the notice lives
> with the copy it covers.

## MIT License

Copyright (c) 2017 - Present Tauri Apps Contributors

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
