# Lingo Room brand

The single source for the Lingo Room identity: logo files, colours and type. The app reads the same values from `app/src/main/res/values/colors.xml`.

## Logo

Two overlapping speech bubbles: a purple one carrying a sound wave (speech going in) and a blue one carrying a translation symbol (understanding coming out).

| File | Use |
| --- | --- |
| `logo-horizontal.svg` | Primary logo, on white or lavender |
| `logo-horizontal-dark.svg` | Primary logo on dark navy |
| `logo-mark.svg` | The mark alone, for compact headers and small spaces |
| `app-icon.svg` | App icon, mark on lavender |
| `logo-*-mono-navy.svg` | One colour version for light backgrounds, stamps and print |
| `logo-*-mono-white.svg` | One colour version for dark backgrounds |
| `png/` | PNG exports of the files above |

All text in the SVG files is converted to outlines, so they render the same everywhere.

To change the logo, edit `build_brand.py` and run `python3 brand/build_brand.py` from the repository root. It regenerates every file here and the Android launcher icons in `app/src/main/res/mipmap-*`, plus `drawable/app_icon_vector.xml`.

## Colours

| Name | Value | Use |
| --- | --- | --- |
| Primary Purple | `#520380` | Primary actions, branding, important controls |
| Electric Blue | `#00B4FF` | Second speaker, translation, audio visualisation |
| Accent Yellow | `#FFB025` | Occasional highlights and attention indicators |
| Background Lavender | `#F5F3FF` | Soft backgrounds and cards |
| Dark Navy | `#171B38` | Text |
| White | `#FFFFFF` | Backgrounds, text on purple |

Accessibility notes:

- White text on Primary Purple passes contrast easily. Use it for buttons.
- White or navy text on Electric Blue: use **navy**. White on this blue is too faint for body text. White is fine for large icons only.
- Accent Yellow is for fills and badges with navy text, never for text on white.

In the app these are the `brand_*` colours, and the older `primary*` and `secondary*` names now point at the purple and blue families, so every screen picks them up through the theme.

## Type

Roboto, which is the Android system font, so nothing is bundled.

| Style | Android family | Use |
| --- | --- | --- |
| Roboto Bold | `sans-serif` with bold | Major headings |
| Roboto Medium | `sans-serif-medium` | Buttons, section titles |
| Roboto Regular | `sans-serif` | Body text |

Hindi, Urdu, Telugu, Tamil and other scripts fall back to the phone's Noto fonts automatically.
