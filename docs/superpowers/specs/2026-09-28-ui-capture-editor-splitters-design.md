# Captures beyond Settings: code, clicks, tree tables and splitters

## Goal

Extend the one-call captures of 0.119 to the rest of the IDE: a code fragment
in the editor, the place to right-click and the menu it opens, a row deep in a
tree table such as the Inspections tree, a refactoring dialog, the debugger,
Project Structure and split editors. The agent must see from the text reports,
without reading the image, when a picture would show cut content, and must fix
it with steps: resize a window or a tool window, or move a splitter.

A live probe in the 2026.2.3 sandbox on 2026-09-28 found these gaps. Its
pictures are in JetDesk's `workspace/capture/explore/`.

| Scenario | Gap |
| -- | -- |
| Code fragment | A highlight takes a whole control only; `crop: highlights` of the editor is the whole editor. The caret and its row highlight show. |
| Right-click in the editor | `click` lands at the editor's middle, not at the caret `goto` placed, and moves the caret there. Nothing marks the click point. A popup that runs past the window is painted over black. |
| Inspection deep in the tree | The Inspections tree is a tree table: `select` finds a top-level row, but not an `A > B > C` path. Its rows carry no `[expanded]` or `[collapsed]`, and icon columns print as `EmptyIcon 0x0`. |
| Project Structure | The snapshot says "rows need 346 px and the view shows 152 px", but its `layout:` line names no fix: no step moves a splitter. |
| Debugger | Threads & Variables gets about 100 px above an empty console; 3 of 6 variables show. The snapshot says "rows 0-3 of 6 in view" but no `layout:` line: a cut in height inside a splitter pane is not checked. |
| Editor split | `SplitVertically` and `Unsplit` work; no step moves the divider. |
| Change Signature | The dialog is too narrow: table headers, the return type and the preview are cut, and the snapshot flags none of it. The table's cells, which render with an editor, paint clipped with a gray bar in the offscreen picture, and list as `JSFile:fragment.ts`. |

## Code highlights

A highlight entry takes a code range in an editor:

| Field | Meaning |
| -- | -- |
| `lines` | `"20-27"` or `"20"`: those lines of the editor's file, 1-based |
| `symbol` | the declaration or first occurrence of a name, as `goto` finds it; `nth` picks another occurrence |
| `file` | the file whose editor holds the range; the selected editor without it |
| `label` | as for any highlight |

The outline covers the text of the range: from the leftmost character that is
not whitespace to the right end of the longest line, and from the top of the
first line to the bottom of the last, in the editor's coordinates, soft wraps
and inlays included. A symbol outlines its name only. A range out of view is
scrolled into view first, centered; a range taller than the view fails the step
with the view's height in lines.

`crop: highlights` then cuts the picture to the fragment and its margin. A crop
that holds a code highlight widens to the editor's gutter on the left, so line
numbers stay in the picture.

While a screenshot with a code highlight paints, the editor hides its caret and
the caret row's highlight, and shows them again afterwards. The report says so.

In Split Mode the editor the JetBrains Client shows is the one outlined; a
`file` resolves on the client's side.

## The click point

A `click` or `hover` on an editor with no offset lands at the caret,
so `goto` then `{"action":"click","class":"EditorComponentImpl","button":"right"}`
opens the context menu at the symbol, as a person's right-click on it does. The
caret stays where `goto` put it. A click on an editor with `offset_x` and
`offset_y` keeps its meaning. A `click` on an editor also takes `line` and
`column`, or `symbol`, to land on that text; the caret moves there first.

A highlight `{"click": true}` marks the point of the call's last click, in the
same window: a mouse pointer drawn with its tip at the point, in the outline
color with a white edge, and the numbered badge beside it, with its label such
as `"right-click"`. The pointer counts as a mark for `crop: highlights` and
`crop: popups`, so a crop to the popups keeps the point it was opened from. A
`{"click": true}` with no click earlier in the call fails the step.

A picture's area that lies off the window, such as a popup running past its
bottom edge, is filled with the window's background color instead of black.

## Tree tables

Row steps and row highlights treat a tree table, such as
`InspectionsConfigTreeTable`, as a tree: a row takes an `A > B > C` path,
matched on the tree column's text, and expands the collapsed parents it passes.
The snapshot lists a tree table's rows with their depth indent and
`[expanded]`, `[collapsed]` and `[selected]`, as a tree's rows. A column whose
cell renders only an icon is left out of the row text; a checkbox column keeps
`[x]` and `[ ]`.

On the Inspections page, a highlight or a `select` also takes `inspection`, the
inspection's short name, such as `NullableProblems`: it finds the row, expands
its groups, scrolls it into view and outlines it. The short names are the ones
a `get` or `set` of an inspection takes.

## Tabs of run and debug tool windows

A second probe ran two debug sessions, then two runs. What works: the Run and
Debug tool windows list each run as a `ContentTabLabel` with a ref; a
`toolwindow` step with `tab` switches runs and reports every tab; a `click` on
an inner tab's `SingleHeightLabel` (Threads & Variables, Process Console,
Scripts, Frames, Threads) switches it; highlights outline both; an `expect` or
`get` of a `console` by run name reads that run. Pictures 08 and 10 in the
probe folder.

Gaps and what closes them:

| Gap | Change |
| -- | -- |
| `select` knows `JTabbedPane` only: on `JBRunnerTabs` it fails with "has no rows" | `select` and row highlights take the tabs of every `JBTabs` (`JBRunnerTabs`, `GridCellTabs`, `JBEditorTabs`) by tab text or index; the snapshot lists them as rows with `[selected]` |
| A tool window's accessible name follows its selected tab ("tabs-alpha.js Tool Window"), so a crop by name breaks when the run changes | `crop` takes `{"toolwindow":"Run"}`, the tool window by id |
| A label or badge drawn right of a tab covers the next tab's text | A mark in a row of tabs, or anywhere its label would cover another control's text, puts its badge and label below the outline, or above when below has no room |
| A `run` of RunClass whose configuration already runs, single instance, starts nothing and reports only "ran RunClass"; the earlier `Stop` step left a session at its breakpoint | A `run` step of a run or debug action reports the run it started, or that none started and why; a `Stop` step reports the processes it stopped and those still running |
| A console read prints ANSI escapes ("[33m7[39m") | Console text in reports drops ANSI escape sequences |

A code highlight also takes a console: `{"console":"tabs-alpha.js","contains":"tick 25"}`
outlines the console lines that contain the text, the last match unless `nth`
picks one, in a console built on an editor or on a terminal panel.

## The `splitter` step

`{"action":"splitter", ...}` moves the divider of one splitter.

| Field | Meaning |
| -- | -- |
| target | the splitter by `ref`, or a control inside the pane to size: the nearest splitter above it is moved, and the pane that holds the control is the one sized |
| `proportion` | 0.05 to 0.95, the share of the first pane |
| `size` | the pane's size in logical pixels, along the splitter's axis |
| `size: "fit"` | the size that shows the pane's content whole: its preferred size along the axis, within what the other panes' minimum sizes leave |

It covers `Splitter` and its subclasses (`JBSplitter`, `OnePixelSplitter`, the
editor's split panes), `ThreeComponentsSplitter` (the first or last pane; the
inner one takes the rest) and `JSplitPane`. The report gives the pane's size
and the proportion before and after, and what held it back: a minimum size, or
the room of the window.

The snapshot gives each splitter a ref and its state, as
`OnePixelSplitter [ref=e40] horizontal 0.25`, so a step can name it.

A restore, from a call's `restore` or a scenario replay, puts the proportion
back on the splitter while it shows. Where the IDE saves a proportion for the
next opening, the restore writes the saved value back too: a `JBSplitter`'s
`splitterProportionKey` in `PropertiesComponent`, and the proportions the run
and debug layout keeps per tab. A splitter that is gone and saves nothing needs
no restore.

## Detection

The `layout:` lines of a snapshot also report:

- a list, tree or table whose rows are cut in height by its pane, when the pane
  is inside a splitter: "the Variables pane shows 3 of 6 rows";
- text cut inside an editor field, a combo or a table header, when the field or
  header is narrower than its text;
- a label `[truncated]` inside a dialog or a splitter pane.

Each line names the step that makes room, in this order of preference: a
`splitter` with `"size":"fit"` for a pane of a splitter; a `toolwindow` for a
tool window that is too small; a `window` step for a dialog, which grows it to
its content; a `window` maximize for the IDE window. A line with nothing to
take says so.

A `screenshot` step checks the same for what lies inside its picture: its crop,
or the whole window. Its report ends with one `cut:` line per problem with the
step that fixes it, so the agent learns about a bad picture from text.

`"fit": true` on a screenshot applies those steps before it paints, in the
order above, checks again, and paints; the restore of the call or the replay
undoes them. A problem no step can fix stays in the report.

## Offscreen painting of editor cells

Before this work, a probe must find why the Change Signature table's cells
paint clipped offscreen. Candidates: `printAll` bypasses the renderer's editor
layout; the editor-based renderer needs `addNotify`; the picture paints before
the table has laid out its row heights. The fix is either a paint path that
lays those renderers out first, or, where no offscreen paint is right, a screen
capture of that window's area with a note in the report. The table's row text
in the snapshot must read the cell values, not the renderer's `toString`.

## JetDesk

`scripts/steroid/screenshot/capture.js` gains:

| Option | Step or field |
| -- | -- |
| `--file <path> --lines <a-b>` or `--symbol <name>` | a `goto` and a code highlight |
| `--right-click` | a right click at the caret and a `{"click":true}` mark labelled "right-click" |
| `--inspection <shortName>` | the Inspections page and an inspection highlight |
| `--splitter <label>=<proportion or fit>` | a `splitter` step on the pane that holds the control |
| `--fit` | `"fit": true` on the screenshot |
| `--toolwindow <id> [--tab <run>] [--subtab <tab>]` | a `toolwindow` step, a `select` of the inner tab, highlights of both, and the crop to the tool window |

`.claude/docs/steroid-ui-driving.md` gains examples for code, a context menu,
an inspection and the debugger.

## Documentation

The plugin's `ui-scenarios` and `ui-driving` resources describe code
highlights, the click mark, tree table paths, the `splitter` step, the `cut:`
lines and `fit`, with one example each. The tool description of `steroid_ui`
names the `splitter` step and the new layout lines.

## Out of scope

- Moving a splitter by a drag of the mouse.
- Hiding or rearranging the debugger's panes through its Layout Settings.
- Highlights in a diff viewer's two editors.

## Testing

Unit tests: the code range to outline (whitespace, soft wrap), the tree table
path, the splitter sizes and the fit sizes, the cut detection on built Swing
trees, the click mark's crop, the step validation.

Live, in the 2026.2.3 sandbox, monolith and Split Mode, each probe scenario
above again, compared picture by picture with the probe's:

1. A code fragment cut to its lines, no caret.
2. A right-click at a symbol, with the menu and the pointer.
3. `NullableProblems` outlined in the Inspections tree, its groups expanded.
4. Project Structure SDKs, the snapshot's `layout:` line, then its splitter step, then the picture.
5. The debugger with `fit`: every variable of the frame showing.
6. An editor split with its divider moved.
7. Change Signature with `fit`: headers, return type and preview whole, and cells painted right.
8. Two debug sessions: switch session and inner tab with `select`, outline both with labels below, crop to `{"toolwindow":"Debug"}`.
9. Two runs: switch with `toolwindow` `tab`, outline a console line, and a `run` of an already running configuration that says it started nothing.

Each run ends with `restore`, and a snapshot afterwards matches the one before.
