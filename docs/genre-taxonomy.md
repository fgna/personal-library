# Genre taxonomy

Personal Library treats the active `books.json` catalog as the canonical source for detailed genre values. The Android scan flow must not introduce a second, generic taxonomy or invent new genre labels.

For the current private catalog this means 184 distinct existing genre values across 604 books. A book may normally carry up to three detailed genres, matching the dominant convention in the catalog.

## Rules

- New Android scans may only select exact genre values that already occur in the active `books.json`.
- Genre matching is case-insensitive for validation but the stored spelling from the catalog is preserved.
- The scan review uses a searchable selector rather than a long checkbox list.
- If the active catalog cannot be read, existing sanitized scan genres are preserved and the selector does not allow arbitrary new values.
- Detailed genre values remain in each book's `genre` array. No private `genres.json` is required as a second manually maintained source of truth.
- If a generated `genres.json` sidecar is added later, it must be derived from and kept in sync with `books.json`.

The web app keeps a separate broad family layer for high-level grouping such as `Belletristik`, `Philosophie`, `Wissenschaft`, `Kunst`, `Wirtschaft`, `Geschichte`, `Fremdsprachen` and `Sachbuch`. Those families do not replace the detailed catalog genres.

Detailed topical terms still belong in `keywords` when they are not useful as stable catalog genres.
