# Play Console answers (R3)

Copy-paste answers for the forms Play needs before the closed test. Each answer is based on what the
app actually does (manifest and code at commit 8b3e159 or later), not on intent. If the app changes,
re-check the "Why" column.

## App content

### Privacy policy
URL: <https://retrieve-cs9.pages.dev/privacy> (`web/privacy.html`).

### Data safety
| Question | Answer | Why |
| --- | --- | --- |
| Does your app collect or share any of the required user data types? | **No** | The app has no network permission at all (INTERNET and ACCESS_NETWORK_STATE are removed in the manifest). Files, searches and questions stay in the app's private storage. |
| Is all user data encrypted in transit? | Not applicable (no data is transmitted) | |
| Do you provide a way for users to request that their data is deleted? | Yes: deleting a document in the app removes its text and index; uninstalling removes everything | All data is app-private storage. |

Note: Play itself downloads the `gemma_pack` AI pack. That's Play's transfer, not the app sending data.

### Content rating (IARC questionnaire)
- Category: **Reference, News, or Educational** (or Productivity).
- Violence, sexual content, profanity, controlled substances, gambling: **No** to all.
- User-generated content shared with other users / user interaction: **No** (nothing is shared; there are no accounts).
- Shares user location: **No**. Digital purchases: **No**.
- Generative AI: the app generates text **on the device, only from the user's own imported documents**, and shows the source of each answer. Answer the AI questions accordingly; no AI-generated content is shared.

### Target audience and content
- Target age group: **18 and over** (university students; simplest, and avoids Families policy).
- Appeals to children: **No**.

### Ads
**No, my app does not contain ads.**

### Government app / financial features / health: No.

### Foreground service (Policy declaration)
| Field | Answer |
| --- | --- |
| Foreground service type | `dataSync` |
| What task does it perform? | Reads the text of PDF slides and lecture caption files the user has just imported, runs on-device text recognition on slides that are images, and builds the on-device search index. |
| Why does it need to run in the foreground? | Indexing a long, image-heavy lecture PDF can take longer than the time Android gives ordinary background work. Running in the foreground with a visible progress notification ("Indexing Lecture 4.pdf, page 12 of 141") lets it finish, and the user can see what the app is doing. It starts only right after the user imports files. |
| User-initiated? | Yes: it only starts after the user picks files in the system file picker. |
| Video | Record a short screen capture: Library → Import files → pick a PDF → notification "Indexing … page N of M" appears and completes. |

### Gemma licence
The app's About & licences screen (Library menu) shows: "Gemma is provided under and subject to the
Gemma Terms of Use found at ai.google.dev/gemma/terms", plus the Prohibited Use Policy link. Put the
same sentence at the end of the full description below.

## Store listing

**App name** (30): `Retrieve: lecture search`

**Short description** (80):
> Find the exact slide or lecture minute for any revision question. Offline.

**Full description** (4000):
> Still scrubbing through lectures to find one slide? Retrieve makes your lecture slides, PDFs and
> recording captions searchable on your phone, and it all happens on the device.
>
> SEARCH YOUR WHOLE COURSE
> • Type what you remember and get the module, the week and the slide, shown as the real page with
>   your words highlighted.
> • Search by meaning: ask for "agreement between servers" and find the slide titled consensus.
> • Slides that are only pictures of text are read with on-device text recognition.
> • Narrow it down when you know roughly where it was: module:COMP21111 week:4..6 "unit propagation"
>
> LECTURE RECORDINGS, TO THE MINUTE
> Import the caption file (.vtt) from your lecture portal and results open the transcript at the
> right timestamp.
>
> ASK YOUR LECTURES
> Ask a question in plain words. Retrieve quotes the sentences from your slides that answer it and
> writes a short summary on your phone, with a link to every source. If your material doesn't cover
> the question, it says "Not in your materials" instead of guessing. (Ask downloads a ~580 MB
> on-device model the first time.)
>
> PRIVATE BY DESIGN
> No account, no cloud, no internet permission. Your files, searches and questions never leave your
> phone, and everything works in airplane mode.
>
> ORGANISED LIKE YOUR COURSE
> Modules, then weeks, then files, each module in its own colour. Open a week and you're already
> searching inside it.
>
> Gemma is provided under and subject to the Gemma Terms of Use found at ai.google.dev/gemma/terms.

**Category:** Education. **Tags:** study, productivity.
**Contact email:** (Ayo's developer email). **Website:** <https://retrieve-cs9.pages.dev>

**Graphics**
- Icon 512×512: export `app/src/main/res/drawable/ic_launcher_*` (flat indigo tile, as on the site).
- Feature graphic 1024×500: indigo background, logo, "Find the slide, not the folder".
- Phone screenshots (≥ 2, from the measurement phone): Search home; results with the slide preview;
  the viewer; Ask with key sentences and sources; Library. Tablet screenshots optional (the dev tablet
  works: 7-inch and 10-inch slots).

## Before uploading the closed-test build (checklist)
- [ ] `scripts/install_llm_pack.sh` (puts Gemma in the pack), then `./gradlew bundleRelease`.
- [ ] `bundletool get-size total` on the AAB: base download under Play's limit; record in NOTES.
- [ ] Local delivery test: `bundletool build-apks --local-testing` → `install-apks`; Ask shows the
      download card, the pack arrives, checksum passes, an answer works in airplane mode.
- [ ] Release build imports a PDF (OCR still works with no network permission) and searches.
- [ ] versionCode bumped; not debuggable.
