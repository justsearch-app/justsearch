# Keyboard Shortcuts

## Anywhere in the app

| Shortcut | Action |
|----------|--------|
| Ctrl+K | Open or close the command palette |
| Ctrl+Z | Undo the last action |
| Ctrl+Shift+Z | Redo |
| Esc | Close an open menu, popover, or dialog |

The command palette (Ctrl+K) lists the app's actions and a "Go to" entry for
the main views: open it and type a name, such as "Go to Library". Help is not
in the palette; open it with the **?** button.

## When you are not typing in a text field

| Shortcut | Action |
|----------|--------|
| Alt+Left | Go back to the previous view |
| Alt+Right | Go forward |
| Ctrl+D | Bookmark the current view, or remove its bookmark |
| Ctrl+Shift+A | Open the Search view to ask the AI (needs the chat model running) |

## In the Search view

| Shortcut | Action |
|----------|--------|
| Ctrl+L | Put the cursor in the search bar |
| / | Put the cursor in the search bar, unless you are already typing in a text field |
| Enter | Run the query |
| Shift+Enter | Start a new line in the search bar |
| Ctrl+Enter | Run the query the other way: ask instead of search, or search instead of ask |

## Search results

Click a result to open it in the Inspector, Ctrl+click to add it to or remove
it from a multi-selection, and Shift+click to select a range. Right-click a
result, or use its "⋯" button, for more actions such as "Ask about this file".

## Desktop global search shortcut

**Alt + Shift + Space** shows the JustSearch main window and focuses the default
search bar, including when JustSearch is hidden in the tray or minimized. It
preserves the current search draft and conversation. Settings, dialogs, palettes,
renaming and other text edits keep their focus; nothing is submitted or confirmed.

Settings → Desktop shows the keys, an on/off switch and whether the shortcut is
active. It is on by default. If another app owns the keys, registration fails, or
the desktop session is unavailable, Settings explains why it is not active.
Turning it off releases the keys immediately; the choice survives a restart.

Alternatively run JustSearch with **`--focus-search`**. This works with the
shortcut off or unavailable and starts JustSearch if necessary. It takes
precedence over `--minimized`. An explicit `justsearch://` link passed with it
keeps the link's destination and shows the window.

On Wayland the built-in global shortcut is unavailable. Bind the command line in
your desktop's keyboard settings instead; see the global search shortcut how-to.
