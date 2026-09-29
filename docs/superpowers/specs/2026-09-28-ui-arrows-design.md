# Arrows, per-step numbers and styles for screenshot highlights

## Goal

A `steroid_ui` picture marks what matters with an outline, a number badge and
a label beside it, and marks a click point with a mouse pointer. Where a
target is small or sits among text, as a word in code or an icon in a toolbar,
the badge and label cover what the reader needs, and the outline hides the
target itself. An arrow moves the badge and label away from the target and
points back at it.

Any highlight takes an arrow: a control, a row, the breadcrumb, lines or a
symbol of code, an inspection's row, console lines and the click point. The
caller sets its side and length or lets the layout choose. A step can go
without its number, and a picture can take the colors and line width that
screenshot tools offer.

## What other tools do

| Tool | How an arrow is placed |
| -- | -- |
| Snagit, Skitch, CleanShot X | Drag from a start point to an end point. Styles set the head and tail, the width, the color and a curve; a label is a separate callout. |
| Excalidraw | An arrow's `start` and `end` bind to elements. The head stops a gap short of the element's border, which allows for the stroke width. |
| Floating UI, Shepherd, driver.js | A popover's `placement` is a side and an alignment, `offset` the distance; `flip` and `shift` keep it on screen. Its arrow points at the reference element. |
| GitHub's `image-annotations` agent skill | The caller gives the element's box and a label. The tool searches a ring 25 to 120 px from the element for a label spot, scores contrast, penalizes crossing arrows and crowded labels, and draws an arrow from the label to the element. |
| Scribe, Tango | A circle or a cursor marks the click point; no arrow. |

The common model: the arrow joins a callout to a target. The target decides
where the head goes, a side and a distance place the callout, and a flip keeps
it in the picture. That model fits the highlights and the badge layout that
`steroid_ui` has.

## API

### On a highlight

| Field | Meaning |
| -- | -- |
| `arrow` | `true`, or `{"from": <side>, "length": <px>, "head": <head>}`. `true` is `{"from": "auto"}`. |
| `outline` | `false` draws the arrow without the outline around the target. It needs `arrow`. |
| `number` | `false` gives this step no badge and no number; the other steps count on without it. |
| `style` | `{"color": <color>, "width": <px>}` for this highlight's outline, arrow, badge and label. |

`from` is one of `auto`, `left`, `right`, `above`, `below`, `above-left`,
`above-right`, `below-left` and `below-right`: the side of the target the
callout sits on. `auto` is the default.

`length` is the distance from the head to the tail in logical pixels, from 20
to 400. The default is 60.

`head` is `filled`, a filled triangle and the default; `open`, a V of two
strokes; or `none`, a plain line from the callout to the target.

`number` takes only `false`; a highlight is numbered by default whenever the
picture is. A picture whose highlights are all `"number": false` is outlined
without numbers, as `"numbers": false` gives.

### On a screenshot step

`style` takes the same object and sets the default for every highlight of the
picture. A highlight's own `style` overrides it field by field.

| Style field | Values |
| -- | -- |
| `color` | `red`, the default, `#E52B50`; `orange`, `yellow`, `green`, `blue`, `purple`, `black`; or `#RRGGBB` |
| `width` | The outline's and the arrow's line width, 1 to 8 logical pixels. The default is 2.5. |

A badge and a label fill with the color. Their text is white, or black on a
light color such as yellow: the one with the higher contrast against the fill.
The white edge around every outline and arrow stays, so a mark reads on dark
and light themes alike.

### Examples

```json
{"action":"screenshot","highlight":[
  {"symbol":"parse","arrow":true,"outline":false,"label":"rename this"},
  {"text":"Refactor","arrow":{"from":"right","length":90}}
],"style":{"color":"orange"}}
```

```json
{"action":"screenshot","highlight":[
  {"click":true,"arrow":{"from":"below-left"},"number":false},
  {"text":"Rename...","label":"then this"}
]}
```

## Drawing

The arrow is a straight shaft with its head at the target, drawn in the
highlight's color and width, with the same white edge as an outline. The head
grows with the width.

The head stops 3 px short of the outline, or of the target's bounds grown by
the outline's padding when there is no outline, on the side the tail lies on:
at the middle of that edge for `left`, `right`, `above` and `below`, and at the
corner for a diagonal. On a click point, the head stops 3 px short of the
pointer's tip, and the pointer stays drawn.

At the tail is what the highlight has, and nothing else:

| Highlight | At the tail |
| -- | -- |
| no label, not numbered | nothing: a bare arrow |
| no label, numbered | the badge |
| a label, not numbered | the label |
| a label, numbered | the badge, then the label, reading away from the target |

A highlight without an arrow keeps its badge and label beside its outline, placed
as the layout places them without arrows.

## Placement

The callout is the badge and label at the tail. With `from: auto`, the layout
tries the sides in this order: right, left, below, above, below-right,
above-right, below-left, above-left. It tries each at the given length, then
at 1.5, 2, 3, 4, 5, 6 and 7 times it, up to 400 px, and takes the first spot
where:

- the callout lies inside the picture;
- the callout covers no text of other controls, no line of code in view, no
  badge, label or shaft already placed, and no other outline;
- the shaft crosses no other outline, no placed callout and no other shaft.

When no spot meets all three, it takes a spot whose callout meets the first
two and whose shaft crosses least: a shaft hides little of what it crosses, a
callout hides all. When no callout meets them, it takes the spot inside the
picture, off placed callouts and shafts where one is, whose callout covers the
fewest pixels of text and marks, a crossing counting as much as a badge
covered. A bare arrow has no callout, so only its shaft and its tail's position
count.

The layout measures label text at the picture's scale, as the drawing does, so
the report names the spot the picture shows.

A forced side is tried at the given length first. When its callout leaves the
picture, the arrow flips to the opposite side. When neither fits, the arrow on
the forced side is shortened to fit, down to 20 px, and past that is drawn as
it falls. Obstacles do not move a forced arrow: the caller chose the side.

Arrows are placed in the order of the highlights, after the outlines, so a
later callout keeps off an earlier one.

## Crops, fit and the report

The area of the marks, which `crop: highlights`, `crop: popups` and the growth
of other crops use, includes each arrow and its callout, so no crop cuts them.
The essential areas that `fit` keeps whole remain the targets themselves.

The report's highlights line says where each arrow went, after the
highlight's description: `arrow from left 80px`. It adds `flipped from right`
or `shortened from 120px` when the placement changed a forced side or length,
so the caller sees the change without opening the picture.

## Errors

The step fails before anything runs, with a message that names the valid
values, for:

- an unknown `from` or `head`;
- a `length` outside 20 to 400;
- `outline: false` without `arrow`;
- `number: true`;
- an unknown `color`, or a `#` color that is not six hex digits;
- a `width` outside 1 to 8;
- a `style` field other than `color` and `width`.

## JetDesk

`scripts/steroid/screenshot/capture.js` gains:

| Flag | Effect |
| -- | -- |
| `--arrow [<side>][:<px>]` | An arrow on every outline the capture makes, such as the code and the menu item with `--right-click`. `--arrow` alone is `auto` at the default length. |
| `--head filled\|open\|none` | The arrows' head. Needs `--arrow`. |
| `--no-outline` | Arrows without outlines. Needs `--arrow`. |
| `--color <color>` | The picture's color. |
| `--line-width <px>` | The picture's line width. |

The click pointer of `--right-click` keeps joining the code's step and takes no
arrow of its own.

## Changes

| Where | What |
| -- | -- |
| Server `UiSteps.kt` | `UiHighlight` gains `arrow`, `outline`, `number` and `style`; the screenshot step gains `style`; parsing and the errors above. |
| Server `UiTool.kt` | The tool description of the new fields. |
| Schema `scenario-1.schema.json` and its format fixture | The new fields. |
| Plugin `UiCapture.kt` | `Mark` carries the arrow, the outline flag, its own numbering and the style; drawing of shafts and heads; placement; `markArea` includes arrows; badge and label colors. |
| Plugin `UiSession.kt` | Passing the fields into marks; the report's arrow text. |
| Prompts `ui-scenarios.md`, `ui-driving.md` | The fields, the placement rules and the examples. |
| JetDesk `capture.js`, `capture.test.js`, `.claude/docs/steroid-ui-driving.md` | The flags and their documentation. |

## Testing

Unit tests, each seen failing first:

- `UiCaptureTest`: the head and tail of each side, and the head on a click
  point; `auto` passing over a side whose callout covers text, and trying 1.5
  and 2 times the length; a forced side flipping at the picture's edge and
  shortening when neither side fits; a shaft that would cross another outline;
  a bare arrow; `number: false` skipped in the numbering; the text color on a
  light and a dark fill; `markArea` including an arrow.
- `UiStepsTest`: each field parsed, the step-wide style merged with a
  highlight's, and each error above.
- `capture.test.js`: each flag and its validation.

Live in the sandbox, one `steroid_ui` call each with `"restore": true`:

- a Settings control with an arrow from `auto`;
- a code symbol with `outline: false` and a label;
- an inspection's row with `from: left`;
- a right-click on code with an arrow on the Refactor item, orange;
- a forced side at the picture's edge that flips.

## Out of scope

Curved arrows, arrows between two targets, dashed lines, opacity and shadows.
An arrow from one target to another would need a second locator on a
highlight; no request needs it yet.
