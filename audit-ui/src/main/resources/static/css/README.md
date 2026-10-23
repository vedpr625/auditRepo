# audit-console.css

The design system for the Audit Console. Plain CSS, no build step. Fonts are in `../fonts` (IBM Plex Sans and Mono, Noto Sans Sinhala and Tamil; licences included) so the console works without internet.

## Rules for templates
1. Use these classes only. No inline colours, no inline font sizes. (The only allowed inline style is a computed width or height for a chart mark. Chart marks may move to SVG later so a strict Content-Security-Policy can be switched on.)
2. A status is always text plus colour: `.tag tag-ok|bad|warn|info|off|planned`.
3. Anything not built yet is shown with `tag-planned`, `.btn-planned`, `.panel.planned` or `.notice-planned` (dashed edge).
4. Tables that must work on tablets use `.table-cards`. Each `<td>` gets `data-label="..."` and its content is wrapped in one `<div>`.
5. Times: exact time with zone in `.mono`, relative time under it in `.sub`.
6. Never use `text-transform` or letter-spacing on Sinhala or Tamil text (the stylesheet already switches them off under `:lang(si)` and `:lang(ta)`). Set `<html lang="si">` or `lang="ta"` on language switch.
7. Themes: `data-theme="light|dark"` on `<html>`, otherwise the system setting. Colour theme: `data-accent="ocean|ministry|observatory"` (none = Clinic). Density: `data-density="compact"`. The header switch and Settings write these; `theme-init.js` applies them before first paint.
8. htmx: put `.htmx-indicator` on spinners, `.dim` on a target that should fade while loading.

## Checked
- All text and graphic colour pairs meet WCAG AA (4.5:1 text, 3:1 graphics) in light and dark. See `contrast.py` in the design folder.
- Rendered in Chromium in light, dark and 820 px wide: no console errors, no horizontal scroll, all fonts load.

## Not yet checked
- Sinhala and Tamil sample text only shows that the fonts render. Real wording and line breaks need your translators.
- Not tested in Firefox, Safari or Edge, in a real screen reader, or with the real Thymeleaf pages.
- The old `chester-shared.css` is still in this folder and still used by the current templates. Delete it once the templates move to this file.

## Colour themes and contrast
Each theme has a light and a dark version (section 2b of the stylesheet is generated from one palette table). Pairs checked by script, all at or above the WCAG AA limit (text 4.5:1, control edges, focus ring and chart marks 3:1): ink, secondary and tertiary text on page, panel and sub-panel; link text on page, panel and tint; white on the accent button and its hover colour; selected-segment, tag (ok, bad, warn, info, off) and count-badge pairs; chart blue, red and amber on the panel. Observatory in light mode is a cool slate look; its dark mode follows `design/reference/14-B-overview-dark.png`.
