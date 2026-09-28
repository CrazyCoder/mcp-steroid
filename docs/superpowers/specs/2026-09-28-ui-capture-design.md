# Captures: one call for a framed, highlighted IDE picture

## Goal

Let an agent, or a scenario run in CI, save a picture of a Settings page, a
menu or a dialog with the parts that matter outlined and numbered, in one
`steroid_ui` call, and leave the IDE as it found it.

Three uses drive the design:

- **Settings path provenance.** A support engineer proves that a breadcrumb
  such as `Settings | Editor | General | Appearance` and a leaf option exist,
  with a picture that shows both.
- **Instructions for a user.** A reply or a knowledge base article shows which
  checkbox to set or which option to change, numbered in the order of the
  steps.
- **Documentation screenshots.** A saved scenario captures each picture a doc
  page uses. Replaying the scenarios against a new build refreshes the pictures
  and says which ones changed. CI wiring is out of scope; the scenarios and the
  replay must be ready for it.

What exists: the `settings`, `menu`, `run`, `window`, `scroll` and `screenshot`
steps; a journal of restores that only a scenario replay applies; `UiMarks`,
which labels every control with its ref for the agent; JetDesk's
`settings-path/verify.js` and `scenario/replay.js`.

## The `screenshot` step

| Field | Meaning |
| -- | -- |
| `save` | Name of the picture in the call's execution folder, `screenshots/<save>.png`. One of `save` and `out` is required |
| `out` | Path of the PNG. In a scenario a relative path resolves against the scenario file's folder; in a call with `steps` it must be absolute. The folders are created |
| `highlight` | List of targets to outline, in the order of their numbers. Each item is a locator (`name`, `text`, `ref`, `cls`, `xpath`, `nth`, `row`, `index`), or the string `"breadcrumb"` for the breadcrumb bar of the Settings page shown. An item may add `"label":"<text>"` |
| `crop` | What the picture shows. Omitted: the whole window (see below). `"page"`: the Settings page with its breadcrumb. `"highlights"`: the smallest area holding every highlight and its badge. A locator object: that control |
| `margin` | Padding in pixels around a crop other than the whole window. Default 16 |
| target | As today: pictures the window that holds the target |

### The window pictured

Unchanged rule: the window that holds the target, else the topmost window. With
Settings open that is the Settings dialog, or its separate frame in 2026.3.

The picture also contains the popup windows showing above that window and owned
by it: menus, context menus, list popups, Search Everywhere, completion. Each is
painted at its offset from the window, after the window. A popup that reaches
past the window's bounds grows the picture to hold it. The step paints the IDE's
own windows and never reads the screen, so other applications cannot show
through and the result does not depend on focus.

### Highlights

Each highlight gets a rounded outline and a round number badge at its top left
corner, just outside the outline where there is room. Numbers run 1, 2, 3 in the
list's order. A `label` is drawn beside the badge. Outline and badge use one
color, visible on light and dark themes, with a thin contrasting edge.

Before the picture, a highlight that is outside the visible part of its scroll
pane is scrolled to the middle of it, as `scroll` with `"align":"center"` does.
A highlight that cannot be found fails the step with the locator's usual
message; a picture is never saved with a highlight missing.

`"breadcrumb"` resolves to the banner at the top of the Settings page that shows
the page's path. Without Settings open it fails with "no Settings page is
showing".

### Reports

The step reports the path, the size in pixels, the window, the highlights with
their numbers, and the crop. When `out` replaced a file, it adds `unchanged`
or `changed: N pixels differ`, compared by pixel values, so a docs replay lists
the stale pictures. The facts file `<name>.json` sits next to the PNG and gains
the theme's name, which it already records, and the crop.

## Framing the picture

The existing steps set up the frame. The spec adds what makes them repeatable.

### `window`

`{"action":"window","width":1400,"height":900}` sizes the topmost window, or the
one a target or `title` names. Settings and many dialogs save their size for the
next opening, so a capture's resize would outlive the call. The step records a
restore of the size before. The restore sets that size on the window when it is
still open. When it has closed, the restore writes the saved size back where the
IDE keeps it for the next opening. The implementation finds that place by a live
test of Settings on 2026.2 and 2026.3 and of one ordinary `DialogWrapper`.

### `scroll`

`{"action":"scroll","name":"Show line numbers","align":"top"}` scrolls so the
control sits at the top of the visible area; `"center"` puts it in the middle.
Without `align` it scrolls only as far as needed, as today. `align` needs a
target and rejects `pages`.

### `menu` with `show`

`{"action":"menu","path":"View > Appearance","show":true}` opens the main menu
along the path and leaves the last menu open, for the picture, instead of
running an item. Pressing Escape, a `close` step or the call's restore closes it.
In the hamburger and toolbar menu modes it opens the same path from the button.

## Themes

### Listing

`{"action":"get","themes":true}` lists the installed themes, one per line: the
name, whether it is dark, its id, and a `current` mark on the active one. Themes
from plugins are listed with the plugin's name. The `steroid_ui` description
tells the agent to list themes before it sets one.

### Setting

`{"action":"set","theme":"Light"}` switches to the installed theme with that
name, compared without case; its id is accepted too. An unknown name fails and
lists the installed names. The editor color scheme follows the theme, as the
Appearance page does when the theme changes.

A theme change applies and repaints asynchronously and can take seconds. The
step returns only after:

1. the IDE reports the new theme as current,
2. the event queue has drained and every showing window has repainted after the
   switch, and
3. the UI stays settled for a short quiet period, as `UiSettle` measures it.

It fails with the theme it waited for if that does not happen within the
step's timeout, 30 seconds by default. The step records a restore to the theme
before, which waits the same way. Setting the theme already current does
nothing and records no restore.

The theme is optional in every use. A capture without it pictures whatever
theme the IDE has, and the facts file names that theme.

## `restore` for a call with steps

A new call parameter, `"restore": true`, runs after the steps of a call with
`steps`, whether they passed or failed:

1. Closes the windows and popups the call opened, as a `close` step does, the
   newest first.
2. Applies the call's journal the way a replay does, the last change first,
   including the window size and theme restores above.

The report ends with the `restore step` lines. A scenario replay already
restores, so the parameter only applies to `steps`. Without it nothing changes:
a call with steps leaves the IDE as the steps left it.

## Split Mode

The picture, the popups, the window size and the theme run on the JetBrains
Client, where the user sees them. The theme is a client setting.

A host Settings page is drawn by Lux from the backend's components, so its
controls exist only on the backend. For a `highlight` item on such a page the
client asks the backend to resolve the locator to screen bounds, which match the
client's picture, and draws the outline itself. `crop` with a locator on a host
page works the same way. This is the last task of the plan; until it lands a
host-page highlight fails with "highlights on a host Settings page need the
backend; not supported yet".

## JetDesk

### `scripts/steroid/screenshot/capture.js`

Builds the steps and sends one `steroid_ui` call with `restore: true`.

| Flag | Meaning |
| -- | -- |
| `--settings <id, path or leaf label>` | Opens Settings at that page. A leaf label is resolved to its page first, as `settings-path/verify.js` does, and highlighted |
| `--menu "<A > B>"` | Opens the main menu along the path and leaves it open |
| `--action <id>` | Runs the action, such as one that opens a dialog |
| `--highlight "<label>"` | Repeatable; a control by its name. Numbered in order |
| `--breadcrumb` | Highlights the Settings breadcrumb |
| `--crop page`, `--crop highlights`, `--crop "<label>"` | The crop |
| `--size <W>x<H>` | Sizes the window before the picture |
| `--scroll-to "<label>"`, `--align top\|center` | Frames a long page |
| `--theme "<name>"`, `--themes` | Pictures in that theme; `--themes` lists the installed ones and exits |
| `--out <file>` | Default `workspace/screenshots/<slug>.png` |
| `--port`, `--project` | Pick the IDE as the other bridge scripts do |

It prints the saved path and the step reports. Exit codes: 0 saved, 1 a step
failed (nothing saved, the IDE restored), 2 bad arguments or no IDE.

`settings-path/verify.js --screenshot <file>` saves the provenance picture of
the verified breadcrumb with the breadcrumb and the leaf highlighted.

## Documentation

- Fork, `ui-scenarios.md`: the `screenshot` section covers `out`, `highlight`,
  `crop`, the popups and the change report; a "Frame the picture" part covers
  `window`, `scroll` with `align`, `menu` with `show` and `set` with `theme`,
  with what each restores; a "Documentation screenshots" part gives a scenario
  that sets the size and theme and writes to a relative `out`, and says how to
  replay a folder of them.
- Fork, `ui-driving.md` and the `steroid_ui` description: the one-call capture
  recipe with `restore: true`, and `get` `themes`.
- JetDesk, `steroid-ui-driving.md` and `provenance-settings-paths.md`: the CLI
  and the provenance picture.

## Testing

Unit tests: validation of the new fields, crop and badge geometry, the popup
compositing offsets, the pixel comparison, and the CLI's step building.

Live tests on the 2026.2.3 monolith sandbox and the 2026.3 Split Mode sandbox:

- a Settings option and the breadcrumb, whole window and each crop;
- a long page framed with `scroll` and `align`, and a highlight scrolled in
  automatically;
- a resized Settings window, reopened after the call at its original size;
- a menu with `show`, and an action dialog;
- a theme switch to a dark and a light theme, with the picture taken after the
  repaint, and the theme restored;
- `restore: true` after a failed step;
- a docs scenario replayed twice, the second replay reporting `unchanged`;
- the CLI and `verify.js --screenshot`.
