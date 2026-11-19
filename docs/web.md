# Retrieve website: landing page and pitch video

A one-page site that explains Retrieve and sends Android users to the Google Play listing. The look
and section rhythm follow [mixhaus.app](https://www.mixhaus.app/). The copy, colours and visuals are
Retrieve's own. Nothing is copied from their text or assets.

Status: **built** (2026-11-19) in [web/](../web/), deployed with Cloudflare Pages (root `web/`, no
build step), not GitHub Pages. See [web/README.md](../web/README.md) for deploy steps and the launch
checklist. Sections 5 and 8 below are the original plan; the rest still describes the page.

## 1. What we're borrowing from Mixhaus

I studied the rendered page on 2026-11-19 (Next.js, light theme, one long scroll).

| Mixhaus pattern | What it looks like | Retrieve version |
| --- | --- | --- |
| Hero headline is a **pain-point question**, last line in the brand blue | "Still piecing together links, emails and mix feedback?" in Bricolage Grotesque 700, ~86px, tight negative tracking | "Still scrubbing through lectures to find one slide?" with the last line in indigo |
| Sub-line plus two pill buttons (filled primary + white outline) | `border-radius: 999px`, blue `#2563EB` | Google Play badge + "See how it works" outline pill |
| Three small ticked reassurances under the CTAs | "Start for free · Clients never need an account · macOS and Windows" | "Free · Works offline · Nothing leaves your phone" |
| **Promo video right under the hero** | 1152×648, 16:9, `autoplay muted loop playsinline`, no controls, poster PNG, 28px radius, soft shadow, webm | Same frame for the Retrieve pitch video (§7) |
| **Without / With** comparison | Two big cards side by side: grey with scattered "message" bubbles, blue with one clean product card | Grey: scattered Blackboard PDFs, a 2-hour recording, phone search finding nothing. Indigo: one result card, `COMP26120 · Week 6 · slide 14 · 32:10` |
| **4-step "how it works"** row | Thin black top rule, small blue "Step 1" label, bold title, two lines of text | Import → Index → Search → Jump to the moment |
| Feature grid with a headline on the left, short body on the right | Headline left-aligned, 3 to 6 cards | 6 feature cards (§3.5) |
| **Dark download card** | Near-black rounded panel, faint blue glow, platform tiles on the right | Dark panel with the Play badge, "Android 10 or later", and a QR code for desktop visitors |
| FAQ, then a short final CTA, then footer | Plain prose answers, calm British tone, no hype words | Same tone (Ayo writes British English already) |
| Sections fade/slide in on scroll | Content is blank until it scrolls into view | `IntersectionObserver` + CSS transition, turned off under `prefers-reduced-motion` |
| Sticky translucent header | White at 80% opacity with blur, centre links, "Launch app ↗" on the right | Logo · How it works · Features · Privacy · FAQ · "Get the app ↗" |

What we **don't** take: pricing (Retrieve is free), their wording, their screenshots, their logo or
anything that would make the page look like Mixhaus rather than a page in the same style.

## 2. Visual system

**Fonts (both free on Google Fonts):** Bricolage Grotesque 700 for headings (tracking about
−0.05em at hero size), Instrument Sans 400/500 for body. These are what Mixhaus uses, and they are
the main reason it feels the way it does.

**Colours** come from the app's theme ([Theme.kt](../app/src/main/java/com/mideyy7/retrieve/ui/theme/Theme.kt)) so the site
and the app match:

| Token | Value | Use |
| --- | --- | --- |
| `--bg` | `#F7F7F5` (warm off-white, as Mixhaus) | page |
| `--surface` | `#FFFFFF` | cards, "how it works" band |
| `--ink` | `#0F1115` | headings, dark download panel |
| `--muted` | `#46464F` | body text |
| `--brand` | `#3A47D5` (app indigo) | hero accent line, buttons, step labels, "With Retrieve" card |
| `--brand-soft` | `#E0E0FF` | chips, glow |
| `--mark` | highlighter yellow, matching the app's match highlight | search words in mock result cards |

Light theme only at first, as Mixhaus. Add dark mode only if time allows.

**Shapes:** 28px radius on the video and big cards, 16px on small cards, 999px pills. Shadows are
soft and large. Max content width about 1150px, with 16px side gutters on phones.

## 3. Page content, section by section

Draft copy below. Tighten it at build time. **Every number on the page must come from
`docs/results/`**, the same rule as the CV (spec §6).

### 3.1 Header
`Retrieve` wordmark (launcher icon + text) · How it works · Features · Privacy · FAQ ·
**Get the app ↗** (links to Play).

### 3.2 Hero
- H1: *Still scrubbing through lectures* / *to find one* / **slide?** (last word or line in indigo)
- Sub: "Retrieve makes your lecture slides, PDFs and recording captions searchable on your phone.
  Ask where something was taught and get the module, week, slide and the exact minute in the
  recording."
- CTAs: official **Get it on Google Play** badge · "See how it works" pill (scrolls to §3.4)
- Ticks: Free · Works offline · Nothing leaves your phone
- Pitch video below (§7). Until it exists, use a static poster: a phone screenshot of a result card
  on an indigo gradient.

### 3.3 Problem: "Revision shouldn't start with a search party."
Left-aligned headline, short paragraph on the right (Mixhaus layout).
- **Without Retrieve** (grey card): stacked "bubbles": `Week 6 folder: 14 PDFs`,
  `Lecture recording 1:52:04`, `Phone search: no results`, `Slides are images, can't Ctrl+F`.
- **With Retrieve** (indigo card): one white result card that looks like the app. Query
  `raft log matching`, best match `COMP26120 · Week 6 · slide 14` with the slide thumbnail and the
  words highlighted in yellow, and a second row `Recording · 32:10`.

### 3.4 How it works (`#how`)
Four columns with a top rule and a "Step n" label:
1. **Import** Pick the slides, PDFs and caption files from your module page.
2. **Index** Retrieve reads every page on the phone, including slides that are only images.
3. **Search** Type what you remember, or narrow it down: `module:COMP26120 week:4..6`.
4. **Jump to it** Open the exact slide, or the transcript at the right minute.

### 3.5 Features (`#features`): "Built for the way you actually revise."
Six cards, each with a small icon (reuse the app's drawables where they fit):
- **Search by meaning, not just words**: finds "consensus" when the slide says "agreement".
- **A query language for power users**: `module:`, `week:4..6`, `"exact phrase"`, `NOT tutorial`.
- **Recordings by the minute**: captions are indexed in 30-second chunks.
- **See the slide, not a snippet**: the result is the slide itself, with the matched words marked.
- **Organised like your course**: modules → weeks → documents, and every folder is also a search.
- **Private by design**: no account, no cloud, works in airplane mode.

### 3.5b Ask (`#ask`): "Ask your lectures. Every answer shows where it came from."
Phase 9 shipped (Ask tab, `dcc7e6d`), so Ask gets its own section rather than a feature card.
- Left: three points (answers only from your material · every citation is checked and opens the
  slide or minute · "Not in your materials" + "Search for it instead"), then a proof row:
  off-topic refusals and the answered rate from `refusal.csv` (test split, threshold tuned on dev),
  and "[f] of 30 judged faithful" from `qa_eval.csv` once it is judged.
- Right: a mock of the Ask screen (question, answer with numbered citation chips, Sources list
  with cited and "read but not cited" rows), plus a small refusal card.
- TTFT and tokens/s stay blank until measured on the measurement phone (NOTES: tablet = dev device).
- FAQ gains "Can Ask make things up?". The phones answer says search runs on Android 10+, while
  Ask needs a newer phone and downloads the ~580 MB Gemma model on first use (release track R2).

### 3.6 Under the hood (`#tech`): replaces Mixhaus' pricing section
For recruiters and curious engineers. A dark or white band with 3 or 4 large stat tiles, each with a
one-line source:
- Search latency P99 on device (from the on-device benchmark CSV, with the phone model named)
- recall@5 for hybrid vs BM25 on the labelled test set (`docs/results/recall.csv`)
- Model size before and after distillation + int8 (`docs/results/distill_*.csv`, `quantize.csv`)
- Stack line: Kotlin · Jetpack Compose · C++ (NDK/JNI) · PyTorch · LiteRT
- Link: "Read the code on GitHub ↗" (only once the repo is public)

### 3.7 Download (`#download`): dark card
- Left: "Your lectures, searchable in your pocket." plus one line about it being free and offline.
- Right: tile with the Android logo, "Android 10 or later" (minSdk 29), the Play badge, and a
  **QR code** that points to the Play listing (desktop visitors can't tap the badge on their phone).

### 3.8 FAQ (`#faq`)
Use native `<details>` elements, so no JS is needed:
- Does anything get uploaded? *(No. Indexing, search and answers all run on the phone.)*
- Which files can I add? *(PDF slides and documents, and WebVTT caption files from the lecture portal.)*
- Can it search lecture recordings? *(Yes, through their caption files. It doesn't transcribe audio.)*
- What phones does it run on? *(Android 10+. Give the tablet/phone tested and the RAM.)*
- Is it free? *(Yes.)*
- Who made it? *(A Manchester CS student, link to the GitHub repo.)*

### 3.9 Final CTA + footer
"Find it in seconds next time." + Play badge. Footer: © Retrieve, **Privacy policy**, GitHub, contact email.

## 4. The privacy policy (needed for Play anyway)
Google Play requires a privacy policy URL for every app. Host it on this site at `/privacy`, a
short plain page: no data collected, no network calls for user content, files stay in app storage,
the app reads only the files you pick. This page is worth building **before** the Play submission even
if the landing page isn't finished.

## 5. Tech and hosting

- **Plain static site:** `web/index.html`, `web/privacy.html`, `web/styles.css`, about 40 lines of
  `web/main.js` for the scroll reveals. No framework. One page doesn't need Next.js, and it keeps the
  repo's toolchain to Gradle + CMake + Python.
- Fonts from Google Fonts with `display=swap`. Images as WebP/AVIF. Video as webm + mp4 fallback.
- **Hosting:** GitHub Pages from `web/` through a small Actions workflow (free, HTTPS). This needs a
  GitHub remote; the repo has none yet. A custom domain is optional (e.g. `retrieve.study` if
  available, about £10 a year). The `github.io` URL is fine for Play and the CV.
- SEO/sharing: `<title>`, meta description, Open Graph image (1200×630, hero headline + phone), a
  favicon from the launcher icon, and a `SoftwareApplication` JSON-LD block like Mixhaus has.
- Accessibility: real `<h1>`–`<h3>` order, alt text on mockups, visible focus rings, contrast AA on
  the indigo card, and `prefers-reduced-motion` turns off reveals and pauses the video.
- Target: Lighthouse ≥ 95 on all four scores, page weight < 1.5 MB excluding the video, which loads
  `preload="metadata"` with a poster.

## 6. The Play link

- Listing URL: `https://play.google.com/store/apps/details?id=com.mideyy7.retrieve`
- Use Google's official **"Get it on Google Play"** badge artwork, unmodified, at the size their
  badge guidelines give, and add the trademark line in the footer as they require.
- Add `&utm_source=website&utm_campaign=landing` (Play Console shows these in acquisition reports).
- **Before the app is public:** new personal developer accounts need a closed test (12+ testers for
  14 days) before production. During that period the CTA becomes **"Join the beta"**, linking to the
  Play testing opt-in URL, and the ticks say "Closed beta". Swap it in one place (`const PLAY_URL`).

## 7. Pitch video (do this once the app is feature-complete)

Mixhaus' hero video is a 16:9, silent, looping product walkthrough, not a talking head. Make two cuts
from one recording session:

| Cut | Length | Where | Sound |
| --- | --- | --- | --- |
| **Hero loop** | 20–30 s | Autoplays muted on the site | none, on-screen captions only |
| **Pitch** | 60–90 s | YouTube (unlisted is fine), README, CV, Play listing promo video | voice-over + light music |

**Storyboard (the pitch, which also gives the loop):**
1. *0–8 s* Problem: a 1h52m recording scrubber and a folder of 14 PDFs. Caption: "Where did they explain Raft log matching?"
2. *8–20 s* Type the question into Retrieve → the best match slide appears with the words highlighted.
3. *20–30 s* Tap the recording result → the transcript opens at 32:10.
4. *30–45 s* Power use: `module:COMP26120 week:4..6 "amortised" NOT tutorial`.
5. *45–60 s* Turn on airplane mode, search again, still instant. Caption: "Nothing leaves your phone."
6. *60–75 s* (if shipped) Ask a question, get an answer with slide and timestamp citations.
7. *75–90 s* Under the hood stats (same sources as §3.6) → Play badge end card.

**How to make it:**
- Record the real app with `scrcpy --record` (or `adb shell screenrecord`) on the test phone, at
  1080p with demo mode on (`adb shell settings put global sysui_demo_allowed 1`) so the status bar is clean.
- Put the recordings into a phone frame on an indigo/off-white background in 16:9, using DaVinci
  Resolve (free) or CapCut. Use slow zooms on the result card, the same fonts as the site for
  captions, and no transitions beyond cuts and fades.
- Export: `retrieve-hero.webm` (VP9, ~2–4 MB) + `retrieve-hero.mp4` (H.264 fallback) +
  `retrieve-hero-poster.webp`. The full pitch goes to YouTube, embedded only in the README/Play listing,
  not autoplayed on the site.
- Use the student's own lecture material only if the lecturer is fine with it appearing in a public
  video. Otherwise make a demo module from open slides (e.g. MIT OCW) so the copyright is clean.

## 8. Build order

| Step | Deliverable | Depends on |
| --- | --- | --- |
| 1 | `web/privacy.html` + Pages deploy | GitHub remote |
| 2 | Skeleton: tokens, fonts, header, hero (static poster), footer | — |
| 3 | Problem cards + how-it-works + features | 2–3 clean app screenshots |
| 4 | Under the hood stats | final numbers in `docs/results/` |
| 5 | Download card, QR, FAQ, final CTA, OG image, JSON-LD | Play URL (or beta URL) |
| 6 | Scroll reveals, reduced-motion, Lighthouse pass, phone-width check | — |
| 7 | Record + edit pitch video; swap poster for hero loop | app feature-complete (spec §7, Mon 7–Tue 8 Dec) |
| 8 | Switch CTA from "Join the beta" to Play production | production release |

## 9. Open questions
- Repo public on GitHub, or the site only? (Decides the "Read the code" link and Pages hosting.)
- Custom domain or `github.io`?
- Contact email for the footer and Play listing: personal or a new project address?
- Demo material for screenshots/video: real course slides (needs permission) or an open course?
