// Android-only final scan author case normalization and duplicate identity guard.
(function () {
  if (!window.AndroidBookSource) return;

  let bypassDuplicateGuard = false;

  function normalizeAllCapsAuthor(value) {
    const clean = String(value == null ? '' : value).trim().replace(/\s+/g, ' ');
    const letters = Array.from(clean).filter(ch => /[A-Za-zÀ-ÖØ-öø-ÿ]/.test(ch));
    if (!letters.length || letters.some(ch => ch !== ch.toUpperCase())) return clean;
    return clean.split(' ').map(word => {
      if (!word) return word;
      const lower = word.toLocaleLowerCase();
      return lower.charAt(0).toLocaleUpperCase() + lower.slice(1);
    }).join(' ');
  }

  function normalizeReviewAuthor() {
    const input = document.getElementById('scan-edit-author');
    if (!input) return;
    const normalized = normalizeAllCapsAuthor(input.value);
    if (normalized && normalized !== input.value) input.value = normalized;
  }

  function visibleOpenLibraryWorkId(review) {
    const match = String(review && review.textContent || '').match(/Open Library\s+(OL[A-Z0-9]+W)\b/i);
    return match ? match[1].toUpperCase() : '';
  }

  async function existingBookByWorkId(workId) {
    if (!workId) return null;
    const response = await fetch('books.json', { cache: 'no-store' });
    const root = await response.json();
    const books = Array.isArray(root.books) ? root.books : [];
    return books.find(book => String(book && book.openlibrary_work_id || '').trim().toUpperCase() === workId) || null;
  }

  // The native scan-save duplicate check still matches title + author. For a grounded scan,
  // align those two values with an already-catalogued copy carrying the same verified work ID
  // before the existing save handler runs. This keeps the established duplicate/merge flow intact.
  document.addEventListener('click', async event => {
    const button = event.target && event.target.closest ? event.target.closest('#scan-edit-add') : null;
    if (!button) return;
    if (bypassDuplicateGuard) {
      bypassDuplicateGuard = false;
      return;
    }

    const review = button.closest('#android-book-review');
    const workId = visibleOpenLibraryWorkId(review);
    if (!workId) return;

    event.preventDefault();
    event.stopImmediatePropagation();
    button.disabled = true;
    try {
      const existing = await existingBookByWorkId(workId);
      if (existing) {
        const title = review.querySelector('#scan-edit-title');
        const author = review.querySelector('#scan-edit-author');
        if (title && String(existing.title || '').trim()) title.value = String(existing.title).trim();
        if (author && String(existing.author || '').trim()) author.value = normalizeAllCapsAuthor(existing.author);
      }
    } catch (error) {
      console.warn('Could not pre-check scan duplicate by Open Library work ID', error);
    } finally {
      button.disabled = false;
      bypassDuplicateGuard = true;
      button.click();
    }
  }, true);

  const observer = new MutationObserver(normalizeReviewAuthor);
  observer.observe(document.documentElement, { childList: true, subtree: true });
  normalizeReviewAuthor();
})();
