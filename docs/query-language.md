# Retrieve query language

Queries are typed into the search box. Plain words behave like a normal search engine. Filters, phrases and boolean operators narrow the results down.

```
module:COMP21111 week:4..6 "unit propagation" NOT example
```

## Grammar

```
query    := orExpr
orExpr   := andExpr ("OR" andExpr)*
andExpr  := unary (("AND")? unary)*
unary    := "NOT" unary | primary
primary  := WORD | PHRASE | FILTER | "(" orExpr ")"
FILTER   := ("module" | "week" | "type") ":" value
              module:COMP21111   module:21111 (digits match a code ending in them)
              week:4             week:4..6 (inclusive)
              type:slides        type:pdf      type:audio
PHRASE   := '"' text '"'   (curly quotes “ ” work too: phone keyboards insert them)
WORD     := any run of characters other than whitespace, ( ) and quotes
```

- `AND`, `OR` and `NOT` are operators only in UPPER CASE. Lower-case `and`, `or` and `not` are ordinary words.
- Filter keys are case-insensitive (`Module:`, `WEEK:`). A word of letters followed by `:` and a value is treated as a filter, so an unknown key such as `foo:bar` is an error. Keys that aren't purely letters are ordinary text: `16:1` and `COMP12111:Overview` are words.
- Precedence, from tightest to loosest: `NOT` binds tighter than juxtaposition and `AND`, which bind tighter than `OR`. So `a b OR c` means `(a b) OR c`.

## Words and phrases

Each WORD and PHRASE is split into index terms by the same tokenizer that indexed the slides. That means lower-casing, splitting on punctuation, dropping the possessive `'s`, and folding math-styled letters such as 𝑫𝑩𝑴𝑺 to `dbms`. See `native/index/tokenizer.h`.

- A WORD that splits into **several terms** is matched as a phrase. `RISC-V`, `flip-flop` and `16:1` match those terms next to each other, in order, which is what someone typing them means.
- A WORD that splits into **no terms** (e.g. `—`) is ignored.
- A PHRASE matches its terms at consecutive positions, in order.

## Matching

Inside one `andExpr`, every operand plays one of three roles:

| Role | Which operands | Effect |
| --- | --- | --- |
| **required** | filters, phrases, multi-term words, `( … )` groups, and any operand directly next to an explicit `AND` | must match |
| **should** | single-term words that aren't next to an `AND` | add to the score; see the rule below |
| **must-not** | the operand of `NOT` | must not match |

**The "should" rule.** At least one *should* word must match, **unless** the same `andExpr` contains a required operand that has words of its own: a phrase, a multi-term word, an `AND`-joined word, or a group containing words.

| Query | Means |
| --- | --- |
| `unit propagation` | docs with *unit* or *propagation*, best BM25 first (ordinary search) |
| `module:COMP21111 unit propagation` | in COMP21111 **and** containing *unit* or *propagation*. A filter has no words, so the should rule applies. |
| `"stored procedure" parameters` | must contain the phrase; *parameters* only boosts the ranking |
| `trigger AND update` | must contain both |
| `trigger AND update before` | must contain *trigger* and *update*; *before* only boosts |
| `NOT tutorial` | every chunk except those containing *tutorial* |
| `module:COMP23111 week:5` | every chunk of COMP23111 week 5 (filters only) |
| `(raft OR paxos) NOT lecture` | contains *raft* or *paxos*, and not *lecture* |

`OR` matches if either side matches. An `andExpr` with only `NOT` operands matches everything its enclosing filters allow, minus the excluded docs.

## Ranking

Every result is scored with BM25 (k1 = 1.2, b = 0.75) over the query's **positive terms**: every term from a word or phrase that isn't under an odd number of `NOT`s. Each distinct term counts once. Terms under `NOT` never add to a score.

Ties, including the all-zero scores of filter-only and NOT-only queries, are broken by module, then week, then page (or start time for audio), then internal id. So a filter-only query lists the chunks in course order.

## Errors

Errors give a 1-based column in characters:

| Input | Error |
| --- | --- |
| `"unit propagation` | `unterminated phrase starting at column 1` |
| `(raft OR paxos` | `expected ')' to close '(' at column 1` |
| `raft)` | `unexpected ')' at column 5` |
| `raft AND` | `expected a word, phrase, filter or '(' after AND at column 6` |
| `OR raft` | `expected a word, phrase, filter or '(' at column 1, found OR` |
| `NOT` | `expected a word, phrase, filter or '(' after NOT at column 1` |
| `()` | `empty group at column 1` |
| `""` | `empty phrase at column 1` |
| `module:` | `missing value for module: at column 1` |
| `week:x` | `week must be a number or a range like 4..6 (column 1)` |
| `week:6..4` | `empty week range 6..4 (column 1)` |
| `type:video` | `unknown type 'video' at column 1 (use slides, pdf or audio)` |
| `foo:bar` | `unknown filter 'foo' at column 1 (use module:, week: or type:)` |

An empty or whitespace-only query returns no results and no error. So does a query whose words all tokenize to nothing (e.g. `— …`).

## Execution

There are two executors with identical semantics. Tests check that they return exactly the same results and scores on random queries.

- **Planned** (used by the app):
  1. Apply filters first, to get a candidate set from the per-chunk metadata.
  2. Evaluate required operands in ascending order of estimated document frequency, so the most selective goes first, intersecting as it goes and stopping early once nothing is left.
  3. Apply the should group.
  4. Remove `NOT` results as a set difference.
  5. Compute BM25 only for the surviving documents.
- **Naive** (baseline for the benchmark): score every document that contains any positive term, then test each document against the query tree left to right, checking filters last.
