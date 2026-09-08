// Android-only final scan author case normalization.
(function () {
  if (!window.AndroidBookSource) return;

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

  const observer = new MutationObserver(normalizeReviewAuthor);
  observer.observe(document.documentElement, { childList: true, subtree: true });
  normalizeReviewAuthor();
})();
