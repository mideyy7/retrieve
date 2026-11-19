# Retrieve UI: modules, weeks and documents

What the Library and Search screens should become, borrowing the organisation model from
[Blitz](https://github.com/agmada-asa/Blitz) (a Next.js study app) and fitting it to Retrieve's
Compose app and its existing data.

Status: **built** (merged into `main` on 2026-11-19, merge commit `9a84580`). All eleven steps in §7
shipped, plus module titles (§6.3, schema v3). Decisions taken: Navigation Compose with a back stack
per tab (§6.1) and week 0 = "Whole module" (§6.2). The rest of this document is the original
proposal, kept as the design record.

**Visual pass (2026-11-19, after the merge).** Kept the same screens and simplicity; changed how
results are *shown*:
- Brand theme (indigo, highlighter-yellow match marks, light and dark), a stable colour per module
  (badge, week bar, chips), and an adaptive launcher icon.
- Results show the slide itself, rendered from the original PDF with `PdfRenderer` (nothing copied at
  import): a large best-match card (side by side on a tablet) and thumbnails for the rest. Recording
  results show the timestamp on the module's colour.
- A full-screen viewer for any result or document page: swipe to neighbouring slides, page text
  with the search words highlighted, copy citation (`COMP21111 · Week 4 · Lecture 4 · slide 22`),
  share, open in a PDF app. Recordings open as the transcript, scrolled to the moment.
- Start screen: what is searchable, module shortcuts, recent searches (on device), and a search-tips
  sheet whose examples use the student's own modules. Match reasons say "Matches your words" /
  "Similar meaning" instead of scores.

## 1. The idea in one paragraph

Blitz organises material as **project → unit → source** (a module, a week or lecture, a file).
Retrieve already stores the same thing on every row: `Document.module`, `Document.week`, and the
same two fields copied onto every `Chunk`. So the folders don't need new tables; they are a
`GROUP BY module, week` over what is already there. The part worth adding is that **every folder is
also a query**: opening COMP26120 › Week 6 is the same as searching `module:COMP26120 week:6`. That
keeps the UI work tied to the query language (spec §5.3) instead of being polish for its own sake
(spec §10).

## 2. What Blitz does that is worth copying

| Blitz | Where in Blitz | Retrieve version |
| --- | --- | --- |
| Library is a grid of project cards, each with a segmented bar (one segment per unit) and a one-line status | `components/projects/project-grid.tsx` | Module cards: code, doc count, one segment per week coloured by index state |
| Card status only says something when the learner needs to act ("Sources aren't in a unit yet", "No material yet"); otherwise the bar speaks and the counts go to the screen reader | `progressStatus()` in `project-grid.tsx` | Show text only for "2 failed" or "Indexing 3 of 7"; otherwise just the bar |
| Module page: header, a row of "areas" (Practice, Sources, Listen), then the ordered learning path of units | `components/projects/project-overview.tsx` | Module page: header, "Search this module" field, then weeks in order |
| Source list grouped by unit, then "Reference books", "Not in a unit", "Exam papers"; empty groups hidden | `groupSources()` in `components/projects/source-list.tsx` | Docs grouped by week, then "Whole module" (see §6.2) |
| Long lists start with every group folded so the page reads as an index; typing in the filter opens every group with a match | `SourceList`, `FILTER_THRESHOLD` | Same: fold weeks when the module has more than ~12 docs |
| Each row has one menu: Rename, Move to (unit / reference / not in a unit), Delete | `source-list.tsx` | Rename, Move to week, Delete |
| "Add material" dialog has an **Add to** picker, pre-selected when opened from a unit's menu | `components/learn/add-material.tsx` | Import sheet pre-filled with the module/week you opened it from |
| Files upload one at a time with a row each, so one bad file doesn't block the rest | `add-material.tsx` | One import sheet for all picked files, a row each, instead of one dialog per file |
| Empty states say what to do next ("Add your first week") | `project-overview.tsx` | "No modules yet. Import a week of slides." |
| Never show a partial count as if it were exact | `docs/bulk-library.md` | Counts come from the same query as the list, or aren't shown |

### What not to copy

Teach progress, XP and streaks, Review feeds, Listen/podcasts, the research panel, accent themes,
archived projects. None of them map to search, and spec §10 says default Material 3 is fine.
Blitz's visual style (rounded 16dp cards, one brand colour, muted secondary text, pill buttons)
is easy to approximate with M3 defaults plus a seed colour; don't spend more than that on it.

## 3. Screens

Bottom bar stays at two tabs (**Search**, **Library**), later three with **Ask**. Library gains a
back stack: Library → Module → Document.

### 3.1 Library (module grid)

```
┌──────────────────────────────────┐
│ Library                  ⋮       │  ⋮ = Re-index all, Export chunks
│ ┌──────────────┐┌──────────────┐ │
│ │ COMP26120    ││ COMP21111    │ │
│ │ 14 docs      ││ 9 docs       │ │
│ │ ▮▮▮▮▮▮▯▯▯▯▯▯ ││ ▮▮▮▮▮        │ │  one segment per week present
│ └──────────────┘│ 1 failed  ⚠  │ │  status text only when action needed
│ ┌──────────────┐└──────────────┘ │
│ │ COMP23311    │                 │
│ │ Indexing 2/4 │                 │
│ │ ▮▮▯▯         │                 │
│ └──────────────┘                 │
│                  [ + Import PDFs ]│
└──────────────────────────────────┘
```

- Two-column `LazyVerticalGrid`, sorted by most recent import (Blitz's "Recently studied"
  becomes "Recently added"; a sort menu can come later).
- Segment colours: indexed = primary, indexing = primary at 40%, failed = error, queued = outline.
- Long-press or ⋮ on a card: **Rename module** (rewrites the code on all its docs; §5).
- Empty state: "No modules yet. Import a week of lecture slides to start." plus the import button.

### 3.2 Module page

```
┌──────────────────────────────────┐
│ ←  COMP26120                ⋮    │
│ 14 docs · weeks 1–7 · 412 chunks │
│ ┌──────────────────────────────┐ │
│ │ 🔍 Search COMP26120           │ │  opens Search with "module:COMP26120 "
│ └──────────────────────────────┘ │
│ ▾ Week 1                    2    │
│     Lecture 1 – Intro.pdf  Slides│
│     Lab 1.pdf              PDF   │
│ ▸ Week 2                    3    │
│ ▾ Week 3                    1    │
│     Lecture 5.pdf  ⚠ Failed      │
│        Couldn't read page 4.     │
│        [Retry]                   │
│ ▸ Whole module              1    │  (§6.2)
│                  [ + Add files ] │
└──────────────────────────────────┘
```

- Weeks are `stickyHeader`s in a `LazyColumn`, folded by default once the module has more than
  ~12 docs, all open otherwise (Blitz's rule).
- Week header long-press / ⋮: **Search this week** (`module:COMP26120 week:3 `), **Add files to
  week 3**.
- Doc row tap → Document page. Row ⋮: **Rename**, **Move to…** (module + week), **Delete**.
- Row status line, as today: "Reading page 12 of 40" with a progress bar, "Queued", or the error
  with a **Retry** button (today it says "import the file again"; a retry action re-runs
  `IndexDocumentWorker` on the stored `sourceUri`).
- "Add files" opens the import sheet with this module (and week, if opened from a week) pre-filled.

### 3.3 Document page

Small. Name, `COMP26120 › Week 3 › Slides`, status, chunk count, OCR page count, imported date,
then a list of its chunks by page (page number + first line). Tapping a page opens Search with
nothing typed but the doc's module/week scope, or later the result detail. Mainly a debugging aid
for the OCR fallback; keep it minimal.

### 3.4 Search

Two changes:

1. **Breadcrumb on each result.** Replace `COMP26120 · week 6 · slide 14` with a breadcrumb
   whose parts are tappable: tapping `COMP26120` adds `module:COMP26120` to the query, tapping
   `Week 6` adds `week:6`. Doc name and slide stay as the trailing part.
2. **Scope comes in as query text, not chips.** Opening Search from a module or week writes the
   filter into the field (`module:COMP26120 week:3 ` with the cursor at the end). The query string
   stays the only source of truth, the planner sees exactly what the user sees, and students learn
   the syntax by using the folders. Chips that mirror the text would need the parser exposed to
   Kotlin to stay in sync; not worth it.

Optional: with an empty query, show "Recent modules" as one-tap scopes under the field.

### 3.5 Import sheet (replaces one dialog per file)

```
┌──────────────────────────────────┐
│ Import 4 files                   │
│ Add to  [COMP26120 ▾]  Week [6]  │  applies to every row below
│ ───────────────────────────────  │
│ L11 Raft.pdf      COMP26120  6  Slides │  pre-filled from filename
│ L12 Paxos.pdf     COMP26120  6  Slides │
│ wk7_lab.pdf       COMP26120  7  PDF    │  filename said wk7
│ notes.pdf         ⚠ needs module      │
│            [Skip all]  [Import 3]│
└──────────────────────────────────┘
```

- `ModalBottomSheet`. The top "Add to" row sets every row that has no guess of its own; a row
  edited by hand keeps its value (Blitz's `titleSource: auto | user` idea, per row).
- Module field is a dropdown of existing modules plus free text, so typos don't create a second
  "COMP2612O" folder.
- Rows that can't be imported say why and are skipped, the rest go ahead. Each confirmed row
  enqueues its own worker as today.

## 4. Data layer

No schema change for the core of this. New DAO queries:

```kotlin
data class ModuleSummary(
    val module: String,
    val docCount: Int,
    val chunkCount: Int,
    val readyCount: Int,
    val failedCount: Int,
    val indexingCount: Int,
    val lastImportedAt: Long,
)

// GROUP BY module over documents LEFT JOIN chunk counts; ORDER BY lastImportedAt DESC
fun observeModules(): Flow<List<ModuleSummary>>

// Per-week status for the segmented bar: (module, week, status) rows, small enough to group in Kotlin
fun observeWeekStatus(): Flow<List<WeekStatus>>

// Existing DocumentSummary, filtered: WHERE module = :module ORDER BY week, name
fun observeModuleDocuments(module: String): Flow<List<DocumentSummary>>

fun distinctModules(): Flow<List<String>>   // for the import sheet's dropdown
```

`Index(module, week)` already exists on `chunks`; add the same index on `documents` (schema v3,
with a migration test like the v1→v2 one).

ViewModels: `LibraryViewModel` keeps import/delete/re-index and exposes `modules`; a new
`ModuleViewModel(module)` exposes the grouped docs and fold state; `DocumentViewModel(docId)`.

## 5. Renaming and moving: the one tricky part

`module` and `week` are copied onto every chunk, and the native keyword index resolves
`module:`/`week:` filters from what it was built with. So rename and move are not just a label
change:

1. In one Room transaction, update `documents` **and** `chunks` for the affected doc(s).
2. Rebuild the keyword index (the same path `reindexAll` already uses) so filters see the new
   values. Show "Updating search index" in the top bar while it runs.
3. `stableKey` is built from the file's SHA-256 and page, not module/week, so exported chunks and
   `ml/data/questions.json` labels survive a move. Worth a unit test that proves it.

Renaming a module is the same operation over every doc with that code. If the new code already
exists, the two modules merge; say so in the confirm dialog ("COMP2612O will be merged into
COMP26120").

## 6. Decisions to make before building

### 6.1 Navigation

The current comment in `ui/RetrieveApp.kt` says a navigation library isn't worth it for two tabs.
With a three-deep Library stack, back handling, and later Result detail and Ask, it now is.
**Recommendation:** add Navigation Compose with type-safe routes (`Library`, `Module(code)`,
`Document(id)`, `Search(initialQuery)`), one `NavHost` per tab so each tab keeps its own back
stack. A hand-rolled stack with `BackHandler` is ~40 lines but loses state on process death.

### 6.2 Material that spans the whole module

Blitz has "Reference books": a textbook that belongs to the module, not a unit. Retrieve requires a
week of 1–52. Options:

- **Week 0 = "Whole module"** (recommended). No schema change; `week:0` works in the query language
  today; `week:1..12` naturally excludes it. Needs the import sheet and `FilenameTagParser` bounds
  to allow 0, and `docs/query-language.md` to mention it.
- Nullable week. Cleaner meaning, but a migration, and `week:` filters have to decide what NULL
  does.

### 6.3 Module titles

`COMP26120` alone is opaque on a card. A tiny `modules(code PK, title?)` table would let a card
read "COMP26120 · Algorithms and Data Structures". Nice, not needed; leave until after the
measurement work.

## 7. Build order

Small commits, each usable on its own:

1. DAO queries + `documents(module, week)` index + migration test.
2. Navigation Compose, Library tab gets a `NavHost`; the existing list screen becomes the first
   route unchanged.
3. Module grid with segmented bars.
4. Module page: grouped weeks, folding, doc rows with existing status UI.
5. "Search this module/week": Search accepts an initial query.
6. Tappable breadcrumbs on search results.
7. Import sheet replacing the per-file dialog, with module dropdown.
8. Retry for failed docs.
9. Rename / move with index rebuild, plus the `stableKey` survival test.
10. Document page.
11. (Decision 6.2) Whole-module week 0.

Steps 1–6 are the ones that show off the query language in a demo video; 7–11 are quality of
life and can slip behind the Phase 4+ measurement work if time is short.
