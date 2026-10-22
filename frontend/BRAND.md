# Brand images (`public/brand/`)

*Copyright (c) 2026 FLYT. Author: Pythagore.*

The source is the official logo, `public/SNIGF Integrated Land Governance Logo.png`
(1254 × 1254, transparent background, 823 KB). It is kept unchanged. Because it sits in
`public/`, the build copies it and the proxy serves it as a static file, but no page loads it.
The files in `public/brand/` are derived from it so that pages load little:

| File | Size | Used for |
| --- | --- | --- |
| `snigf-logo-480.png` | full logo, 480 px wide, 256-colour palette (about 27 KB) | sign-in and registration screens, shown at 240 px |
| `snigf-emblem-96.png` | the circular emblem without lettering, 96 px | header, shown at 48 px |
| `favicon-32.png`, `favicon-64.png` | emblem | browser tab |
| `apple-touch-icon-180.png` | emblem on white | home-screen icon |

To regenerate after the logo changes: crop the emblem (top 58.5 % of the logo's opaque area,
centred on a square), resize with Lanczos, and save optimised PNGs; quantise the full logo
to 256 colours without dithering. Check the result by eye at its displayed size.
