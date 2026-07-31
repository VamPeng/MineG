---
name: MineG Private Family Archive
colors:
  surface: '#fff8f4'
  surface-dim: '#e1d8d2'
  surface-bright: '#fff8f4'
  surface-container-lowest: '#ffffff'
  surface-container-low: '#fbf2eb'
  surface-container: '#f5ece5'
  surface-container-high: '#f0e7df'
  surface-container-highest: '#eae1da'
  on-surface: '#1f1b17'
  on-surface-variant: '#424841'
  inverse-surface: '#34302b'
  inverse-on-surface: '#f8efe8'
  outline: '#737970'
  outline-variant: '#c2c8be'
  surface-tint: '#fd5033'
  primary: '#fd5033'
  on-primary: '#1f1b17'
  primary-container: '#ffe3de'
  on-primary-container: '#6b1a10'
  inverse-primary: '#ffb59b'
  secondary: '#436444'
  on-secondary: '#ffffff'
  secondary-container: '#dcebd9'
  on-secondary-container: '#19331b'
  tertiary: '#8a4f00'
  on-tertiary: '#ffffff'
  tertiary-container: '#ffe0b2'
  on-tertiary-container: '#3a2500'
  error: '#ba1a1a'
  on-error: '#ffffff'
  error-container: '#ffdad6'
  on-error-container: '#93000a'
  primary-fixed: '#ffe3de'
  primary-fixed-dim: '#ffb59b'
  on-primary-fixed: '#3b0b02'
  on-primary-fixed-variant: '#8b2618'
  secondary-fixed: '#dcebd9'
  secondary-fixed-dim: '#abd0a9'
  on-secondary-fixed: '#0c240d'
  on-secondary-fixed-variant: '#2e4e30'
  tertiary-fixed: '#ffe0b2'
  tertiary-fixed-dim: '#f5bd72'
  on-tertiary-fixed: '#2a1800'
  on-tertiary-fixed-variant: '#663c00'
  background: '#fff8f4'
  on-background: '#1f1b17'
  surface-variant: '#eae1da'
typography:
  display-lg:
    fontFamily: Plus Jakarta Sans
    fontSize: 30px
    fontWeight: '700'
    lineHeight: 40px
  display-lg-mobile:
    fontFamily: Plus Jakarta Sans
    fontSize: 24px
    fontWeight: '700'
    lineHeight: 32px
  headline-md:
    fontFamily: Plus Jakarta Sans
    fontSize: 20px
    fontWeight: '600'
    lineHeight: 28px
  body-lg:
    fontFamily: Noto Sans
    fontSize: 17px
    fontWeight: '400'
    lineHeight: 26px
  body-md:
    fontFamily: Noto Sans
    fontSize: 15px
    fontWeight: '400'
    lineHeight: 22px
  label-sm:
    fontFamily: Noto Sans
    fontSize: 12px
    fontWeight: '500'
    lineHeight: 16px
    letterSpacing: 0.5px
rounded:
  sm: 0.25rem
  DEFAULT: 0.5rem
  md: 0.75rem
  lg: 1rem
  xl: 1.5rem
  full: 9999px
spacing:
  unit: 4px
  xs: 4px
  sm: 8px
  md: 16px
  lg: 24px
  xl: 40px
  gutter: 12px
  margin-mobile: 20px
  margin-tablet: 32px
---

## Brand & Style
The product serves two fixed users and is not currently a promotional or growth product. The visual language remains **Minimalist-Modern** with **Tactile** warmth, but the UI must not add marketing copy, trust badges, private-cloud promises, security reassurance, storage messaging, or decorative explanations.

Photos and the current MVP task remain the focus. Every visible element must help the user act, understand a real state or result, recover from a blocker, or understand the direct consequence of an action. The authoritative content rule is [UI-CONTENT-RULES.md](./UI-CONTENT-RULES.md).

## Colors
The authoritative color tokens and usage rules are defined in [THEME.md](./THEME.md). The palette is derived directly from the MineG logo and supported by warm neutral surfaces.
- **Primary (Logo Gradient):** `#FD7106 → #FD5033 → #FD374B`, used for branding, primary interaction and selected navigation states.
- **Secondary (Sage Green):** `#436444`, reserved for completed and successful states rather than general branding or security reassurance.
- **Neutral Background (Cream & Warm Grey):** A soft `#F5F2ED` base replaces harsh whites to reduce eye strain and provide a "paper-like" feel. 
- **Text (Charcoal Brown):** `#4A4540` provides high legibility for Simplified Chinese characters while feeling softer than pure black.
- **Status Colors:** Clearly defined for technical states like "Backing up" or "Offline," using slightly desaturated tones to maintain the calm atmosphere.

## Typography
For Simplified Chinese legibility on Android, this design system utilizes **Noto Sans** for body text and functional labels, providing a familiar and clean system-like experience. **Plus Jakarta Sans** is used for headlines to add a touch of modern, soft personality.

Key typographic rules:
- **Line Height:** Increased specifically for Chinese characters (standard 1.5x - 1.6x) to prevent crowding.
- **Weight:** Use Medium (500) for labels to ensure they remain distinct against warm backgrounds.
- **Hierarchy:** Use size and color shifts (Charcoal to Medium Grey) rather than excessive bolding to maintain a calm visual flow.

## Layout & Spacing
The layout follows a **Fluid Grid** model with generous margins to signify privacy and "breathing room."
- **Mobile:** 4-column grid with 20px side margins. 
- **Tablet:** 8-column grid with 32px side margins.
- **Spacing Rhythm:** Based on a 4px baseline, but defaults to 16px (md) for most element groupings to ensure touch targets are accessible for older family members.
- **Photo Grids:** Use a 2px "inner gutter" for dense views, but a 12px (gutter) for "Story" or "Moment" views to emphasize individual photos.

## Elevation & Depth
Depth is expressed through **Tonal Layering** rather than heavy shadows. 
- **Base Layer:** The warm cream (`#F5F2ED`) background.
- **Surface Layer:** White (`#FFFFFF`) cards or containers with a very soft, diffused ambient shadow (Opacity 5%, Blur 12px, Y+2) to suggest a subtle "lift" from the page.
- **Active State:** Brand interactions use the logo gradient or its tonal container; success and synced states use Sage Green.
- **Navigation:** The bottom navigation bar uses a subtle backdrop blur (Glassmorphism) to keep the user oriented with the content scrolling beneath it.
- **Bottom Navigation State:** The four primary destinations are icon-only: lock for Private Space, home for Family Album, stacked photos for Backup, and person for Profile. The current destination keeps the icon's original logo-derived gradient and places it on the same gradient at 18% opacity. The translucent gradient applies only to the button background. Inactive icons are desaturated and no text labels are shown.

## Shapes
This design system uses a **Rounded** shape language to evoke friendliness and safety.
- **Standard Components:** 8px (0.5rem) corner radius for buttons and small cards.
- **Large Containers:** 16px (1rem) for photo frames and modals to create a "framed" portrait feel.
- **Search Bars:** Fully pill-shaped (rounded-xl) to differentiate functional inputs from content containers.

## Components
- **Buttons:** Brand primary buttons use the logo gradient with dark text. When white text is required, use the accessible solid action color `#D63B26`. Secondary buttons use a tonal border. Avoid sharp corners; use `rounded-md`.
- **Cards (Memories):** Cards should have no visible border, using subtle elevation to separate them. Include a "Date" label in the top left and a "Shared by" avatar in the bottom right.
- **Content Indicators:** Do not add persistent privacy, security, encryption, cloud, or storage indicators. Icons must identify an available action, a real state, a blocker, or a media type.
- **Lists:** Use high-contrast dividers (`#EAE7E2`) and include 16px of vertical padding for list items to ensure easy tapping.
- **Input Fields:** Soft grey backgrounds use `#D63B26` for a clear focus outline. Error states use the dedicated error color `#BA1A1A`; the brand warm red must not be reused as an error signal.
- **Empty States:** Prefer a short state label and an action only when an action is available. Illustrations are optional and must not introduce promotional, security, storage, or private-cloud messaging.
