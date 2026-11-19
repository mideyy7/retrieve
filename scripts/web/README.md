# Retrieve website

Live at **https://retrieve-cs9.pages.dev** (Cloudflare Pages, built from `web/` on every push to `main`).
This README lives outside `web/` because Cloudflare publishes every file in that folder.

The landing page and privacy policy for the Retrieve app. Plain static files with no build step and no
dependencies. The design and content plan is in [docs/web.md](../../docs/web.md).

```
web/
  index.html        landing page
  privacy.html      privacy policy (also the URL for the Play Console)
  404.html          Cloudflare Pages serves this for unknown paths
  _headers          security and cache headers (Cloudflare Pages)
  robots.txt
  assets/
    styles.css      all styles; colours match app/src/main/java/.../ui/theme/Theme.kt
    main.js         hero demo typing loop + scroll reveals
    favicon.svg     the launcher icon
    apple-touch-icon.png, og.png, qr-play.svg   generated, see below
```

## Preview locally

```sh
python3 -m http.server 8000 -d web     # then open http://localhost:8000
```

`_headers` only applies on Cloudflare. Locally the CSP isn't enforced.

## Deploy to Cloudflare Pages

**Git integration (deploys on every push to `main`):**

1. Push the repo to GitHub.
2. Cloudflare dashboard → Workers & Pages → Create → Pages → Connect to Git → pick the repo.
3. Build settings:
   - Framework preset: **None**
   - Build command: *(leave empty)*
   - Build output directory: **`web`**
   - Root directory: *(leave empty)*
4. After the first deploy: Settings → Builds → **Build watch paths** → include `web/*`, so app-only commits
   don't redeploy the site.
5. The site is live at `https://<project-name>.pages.dev`. Add a custom domain under Custom domains when you have one.

Cloudflare serves `privacy.html` at `/privacy` too. Use `https://retrieve-cs9.pages.dev/privacy` as the Play Console privacy policy URL.

**Or upload from the terminal** (no Git connection):

```sh
npx wrangler pages deploy web --project-name retrieve
```

## Regenerate the images

```sh
scripts/web/build_assets.sh
```

This renders `og.png` from `scripts/web/og.html` and `apple-touch-icon.png` from the favicon with headless
Chrome. It also makes `qr-play.svg` with macOS Core Image, and the script fails unless the QR decodes back to the Play URL.
Re-run it after changing the Play URL or the OG copy.

## Before launch

- [ ] **Official Google Play badge.** The buttons are a stand-in. Download the badge from
      Google's badge page (play.google.com/intl/en_us/badges), save it as `assets/google-play-badge.svg`
      or `.png`, and replace the three `.play` links' contents with an `<img>`. Google's guidelines don't
      allow redrawing it.
- [ ] **Contact email** in `privacy.html` (`[contact email]`). Use the same address as the Play listing.
- [x] **GitHub link** points to `https://github.com/mideyy7/retrieve`.
- [x] **Absolute `og:image`** and canonical URLs point at `https://retrieve-cs9.pages.dev`. Update them in
      `index.html` and `privacy.html` if you add a custom domain.
- [ ] **Play link during the closed test**: the listing URL 404s until the app is public. While testing,
      swap the three badge links (search `play.google.com/store/apps/details` in `index.html`) for the
      testing opt-in link `https://play.google.com/apps/testing/com.mideyy7.retrieve`, and re-run
      `build_assets.sh` with that URL for the QR.
- [ ] **Numbers still blank** (fill from `docs/results/` only): search P99 on the measurement phone,
      answers judged faithful (`qa_eval.csv`), Ask time to first word and tokens/s. The 89 ms vs 270 ms
      embedding figure is from the development tablet. Re-measure it on the phone too (NOTES: the phone is the
      measurement device).
- [ ] **Hero video**: replace the animated demo in `.frame` with
      `<video autoplay muted loop playsinline poster=…>` once it's recorded (docs/web.md §7).
- [ ] Optional: self-host the fonts. Google Fonts means visitors' browsers contact Google, which sits
      awkwardly next to "nothing leaves your phone". It's disclosed in the privacy policy for now.
