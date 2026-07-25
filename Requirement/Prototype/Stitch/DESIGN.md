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
  surface-tint: '#456646'
  primary: '#436444'
  on-primary: '#ffffff'
  primary-container: '#5b7d5b'
  on-primary-container: '#f7fff2'
  inverse-primary: '#abd0a9'
  secondary: '#914b2a'
  on-secondary: '#ffffff'
  secondary-container: '#fda27a'
  on-secondary-container: '#773717'
  tertiary: '#5d5c58'
  on-tertiary: '#ffffff'
  tertiary-container: '#767471'
  on-tertiary-container: '#fcffe3'
  error: '#ba1a1a'
  on-error: '#ffffff'
  error-container: '#ffdad6'
  on-error-container: '#93000a'
  primary-fixed: '#c6edc4'
  primary-fixed-dim: '#abd0a9'
  on-primary-fixed: '#012108'
  on-primary-fixed-variant: '#2e4e30'
  secondary-fixed: '#ffdbcd'
  secondary-fixed-dim: '#ffb596'
  on-secondary-fixed: '#360f00'
  on-secondary-fixed-variant: '#743415'
  tertiary-fixed: '#e5e2dd'
  tertiary-fixed-dim: '#c9c6c2'
  on-tertiary-fixed: '#1c1c19'
  on-tertiary-fixed-variant: '#474743'
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
The design system is centered on the concept of a "digital hearth"—a secure, warm, and private space for families to store their most precious memories. It moves away from the frantic energy of social media, opting instead for a **Minimalist-Modern** aesthetic with **Tactile** warmth. 

The target audience includes multi-generational family members, requiring an interface that feels intuitive and reliable. The emotional response is one of safety and nostalgia. Visual density is kept low to reduce cognitive load for non-technical users, ensuring that the photos themselves remain the focal point. All interactions should feel deliberate and calm, reinforcing the "private cloud" promise.

## Colors
The palette is inspired by natural materials and domestic comfort. 
- **Primary (Sage Green):** Used for primary actions and "Secure/Synced" states. It evokes growth and tranquility.
- **Secondary (Muted Sunset):** An accent for highlights or special memories (e.g., "On this day").
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
- **Active State:** Elements may use a slight inner shadow or a color shift to Sage Green to indicate they are pressed.
- **Navigation:** The bottom navigation bar uses a subtle backdrop blur (Glassmorphism) to keep the user oriented with the content scrolling beneath it.

## Shapes
This design system uses a **Rounded** shape language to evoke friendliness and safety.
- **Standard Components:** 8px (0.5rem) corner radius for buttons and small cards.
- **Large Containers:** 16px (1rem) for photo frames and modals to create a "framed" portrait feel.
- **Search Bars:** Fully pill-shaped (rounded-xl) to differentiate functional inputs from content containers.

## Components
- **Buttons:** Primary buttons are filled with Sage Green (`#6B8E6B`) with white text. Secondary buttons use a tonal border. Avoid sharp corners; use `rounded-md`.
- **Cards (Memories):** Cards should have no visible border, using subtle elevation to separate them. Include a "Date" label in the top left and a "Shared by" avatar in the bottom right.
- **Privacy Indicators:** A persistent but unobtrusive icon (e.g., a small shield or lock) should appear near the album title to reassure users of the private nature of the cloud.
- **Lists:** Use high-contrast dividers (`#EAE7E2`) and include 16px of vertical padding for list items to ensure easy tapping.
- **Input Fields:** Soft grey backgrounds with a clear focus state using the primary Sage Green. Error states should use Muted Red text but avoid aggressive red background fills.
- **Empty States:** Use hand-drawn style iconography or soft illustrations to maintain the "family" feel when no photos are present.