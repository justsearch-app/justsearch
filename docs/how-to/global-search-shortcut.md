---
title: Summon JustSearch from the desktop
type: how-to
status: stable
description: "Use the global shortcut or --focus-search to bring JustSearch forward and focus the default search bar."
---

# Summon JustSearch from the desktop

While JustSearch is running, **Alt + Shift + Space** shows its main window and
focuses the default search bar. Settings → Desktop lets you turn this shortcut
on or off and reports whether registration succeeded. Another application may
already own the chord. It is on by default on a fresh install or after deleting
all app data. Settings, modal dialogs, indexing overlays and command palettes
keep focus when summoned. Session renaming and text edits keep focus when the
default search is visible; a summon from another view returns to Search. Search's
bar can take focus while an answer is streaming; summoning does not stop the answer.

You can also run the desktop shell with `--focus-search`, even when the built-in
shortcut is off or unavailable. Use the installed **desktop executable**, not the
headless Engine launcher. An existing instance handles the request; otherwise it
starts with its window shown. `--focus-search --minimized` still shows the window.
An explicit `justsearch://` URL alongside the flag keeps its destination.

## Windows custom shortcut

Find JustSearch in Start, choose **Open file location**, then use the shortcut's
**Properties → Target** to find the installed executable. Create a separate
shortcut to that executable and append a space and `--focus-search` to its Target, outside
the closing quote, for example:

```text
"C:\path\to\JustSearch.exe" --focus-search
```

In that shortcut's **Properties → Shortcut key**, choose an available shortcut,
then Apply. Keep it in Start or on the Desktop so Windows can use it. Turn off the
built-in chord in JustSearch if you prefer only the custom shortcut.

## GNOME custom shortcut

Open **Settings → Keyboard → View and Customize Shortcuts → Custom Shortcuts**
(the wording varies by GNOME version). Add a shortcut named **JustSearch search**.
Use the installed desktop executable's absolute path with `--focus-search`, for
example `/path/to/justsearch --focus-search`, then assign an unused key chord.
Quote the executable path if it contains spaces. GNOME documents this flow in
[Set keyboard shortcuts](https://help.gnome.org/gnome-help/keyboard-shortcuts-set.html).

## KDE Plasma custom shortcut

Open **System Settings → Keyboard → Shortcuts**. Add a command or script shortcut
(the label varies by Plasma version), set its command to the desktop executable's
absolute path followed by `--focus-search`, and assign an unused key chord. Quote
paths containing spaces. On older Plasma versions this is under **Custom
Shortcuts → Edit → New → Global Shortcut → Command/URL**; see KDE’s
[Custom Shortcuts manual](https://docs.kde.org/stable5/en/khotkeys/kcontrol/khotkeys/khotkeys.pdf).

## Session detection and verification

On Linux, JustSearch treats the session as Wayland if `XDG_SESSION_TYPE` equals
`wayland` (ignoring case), **or** `WAYLAND_DISPLAY` is nonempty. This includes
XWayland sessions even if `DISPLAY` is also set. Settings then says **Not available
in this desktop session** and points to `--focus-search`. Desktop custom shortcuts
can run the command on Wayland without JustSearch registering a global chord.

Native registration and routing have automated checks. Actual foreground takeover
on Windows 10/11 and X11, keyboard layout/IME behavior, and the Windows language
switch interaction still require real desktop verification; registration alone
does not establish support. Alt + Shift + Space is provisional until the Windows
check with two input languages passes. If it changes the language, chord selection
must be reopened. No current-monitor or workspace placement policy is promised:
saved geometry is kept unless it is wholly outside the connected screens.

For owner-run NVDA and Narrator checks on the installed Windows app, use the
[screen-reader checklist](screen-reader-checklist.md). No screen-reader run has
been recorded yet.
