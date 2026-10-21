# cmux Pocket Design System — Color

Unified color system for the cmux Pocket Android app. This file is the **canonical design reference** for every color used in the app; the single source of truth in code is `CmuxColors` in `android/app/src/main/java/app/cmuxpocket/ui/CmuxTheme.kt`.

> **Status: CANONICAL.** The canonical palette is **Neon Grid** (selected 2026-10-18). Token values below are implemented in `CmuxColors`.

## Design principles

1. **Dark-first, terminal-native.** The app is a terminal companion: dark mode is the primary experience, optimized for long sessions and OLED screens (near-black, never pure `#000000` except the canvas well).
2. **Light mode is first-class.** Every token has a designed light counterpart — not an inverted afterthought. Semantic accent hues stay constant across modes; their lightness is re-tuned per mode to preserve contrast.
3. **Single token source.** No hardcoded `Color(0x…)` outside `CmuxTheme.kt`. Components consume semantic tokens only (`surface`, `onSurfaceVariant`, `accentOrange`, …), never raw hex.
4. **Contrast floor.** Body text ≥ 4.5:1 (WCAG AA), large text/icons ≥ 3:1 against their background, in both modes.
5. **Color means state.** Semantic accents are reserved for state/severity: `green = connected/success`, `orange = working/warning`, `red = error/disconnected`, `purple = agent/AI`, `blue/cyan = info/link`. Never use them decoratively.

## Token architecture

| Token | Used by |
| --- | --- |
| `background` / `onBackground` | Root scaffold, empty states |
| `surface` / `onSurface` | Cards, drawer container, dialogs, sheets |
| `surfaceVariant` / `onSurfaceVariant` | Inset rows, secondary text, chevrons, captions |
| `primary` / `primaryContainer` / `onPrimary` | Active selection, ESC key, links, badges, focused states |
| `terminalBg` / `terminalFg` | Terminal canvas well (string hex, passed to the render engine) |
| `divider` | Hairlines, separators, key borders |
| `accessoryBg` / `accessoryKeyBg` / `accessoryKeyText` | Bottom accessory keyboard bar |
| `accentGreen` / `accentOrange` / `accentRed` / `accentPurple` / `accentBlue` | Status dot, agent states, log severities, terminal ANSI-mapped accents |
| `accent*Ink` (`accentGreenInk`, `accentOrangeInk`, `accentRedInk`, `accentBlueInk`) | Text/icon ink on an accent-tinted chip of the same hue (`accentX.copy(alpha = 0.16–0.20f)`). Dark mode reuses the accent; light mode darkens it to hold the 4.5:1 floor |
| `inkOnLightFill` / `inkOnDarkFill` | Label ink for a fill chosen at runtime (terminal palette swatches). Pick by the fill's luminance, never by the app theme |
| `islandBg` / `islandBorder` | Floating island pill (frosted; includes alpha) |
| `scrim` | Modal overlay dimming |

## Palette: Neon Grid（像素霓虹）

Night-ops neon: violet primary with a neon-cyan secondary accent and a Monokai-lime nod for success, with semantic accents deliberately desaturated enough to avoid eye-searing. Light mode subdues the neon into violet/teal ink.

![Neon Grid — dark and light mockups with token table](docs/design/palette-p3.png)

| Token | Dark | Light |
| --- | --- | --- |
| `background` | `#090911` | `#F5F5FA` |
| `surface` | `#12121E` | `#FFFFFF` |
| `surfaceVariant` | `#1A1A2B` | `#E8E8F2` |
| `onSurface` | `#F0F0F8` | `#14141F` |
| `onSurfaceVariant` | `#8888A3` | `#5C5C75` |
| `primary` | `#A68CFF` | `#6D28D9` |
| `primaryContainer` | `#3D2E73` | `#E6DCFB` |
| `onPrimary` | `#150B33` | `#FFFFFF` |
| `terminalBg` | `#0C0C16` | `#FBFBFE` |
| `terminalFg` | `#CDCDDE` | `#1A1A26` |
| `divider` | `#242438` | `#D8D8E5` |
| `accessoryBg` | `#12121E` | `#E8E8F2` |
| `accessoryKeyBg` | `#202033` | `#FFFFFF` |
| `accessoryKeyText` | `#DBDBEA` | `#1A1A26` |
| `accentGreen` | `#A6E22E` | `#5A9E2E` |
| `accentOrange` | `#FFC857` | `#B97F0E` |
| `accentRed` | `#FF6E7F` | `#D43D51` |
| `accentPurple` | `#A68CFF` | `#6D28D9` |
| `accentBlue` | `#3EE6D8` | `#0D9488` |
| `islandBg` | `#12121EE6` | `#FFFFFFF2` |
| `islandBorder` | `#2E2E48` | `#D8D8E5` |

## References and deliberate divergence

- **Tokyo Night** (verified against the official VS Code theme JSON): purple `#bb9af7`, cyan `#2ac3de`, amber `#e0af68`, foreground `#c0caf5`. Neon Grid borrows its violet/cyan tension but darkens the base to `#090911` and pushes lime into success, avoiding a straight clone.
- **Catppuccin** (public theme family): pastel-on-mauve-dark; cited as inspiration only, no values copied — its lavender base is becoming a cliché across dev tools.
- **Blink Shell / Prompt 3** (studied via Mobbin earlier in this project): near-monochrome system-native palettes; we keep their restraint in the neutral tokens but add a real accent identity, which they lack.

## Usage rules (binding)

1. All component colors must resolve through `CmuxTheme.colors`; new colors require a new token in this file first, then in `CmuxColors`.
2. Semantic accents are state-only (see principle 5). The island status dot, agent badges, and log severities must use them; decoration must not.
3. `islandBg` always carries alpha (frosted glass over the canvas); `islandBorder` is a hairline, never a solid outline.
4. The terminal canvas (`terminalBg`/`terminalFg`) is part of the app theme, not the remote host's ANSI palette — ANSI colors from cmux render unchanged; these two tokens only set the well and default foreground.
5. Light mode re-tunes accent lightness; hues never shift between modes.
6. **Tint needs ink.** Text or an icon drawn on an accent-tinted chip uses the matching `accent*Ink`, not the raw accent: in light mode the tint lifts the fill towards white and drops the raw accent to ~2.7:1. The raw accents remain for dots, bars, borders, and fills.
7. **System bars follow the palette.** The window's status and navigation bars are painted with `background` and their glyphs switch with `isDark`; the platform window theme is a fixed dark `Theme.Material.NoActionBar`, so leaving the bars unset produces a black band with dark glyphs in light mode.
