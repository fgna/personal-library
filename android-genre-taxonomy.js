// Android scan-review controlled genre selector.
(function () {
  if (!window.AndroidBookSource) return;

  const GENRES = [
    'Belletristik',
    'Krimi & Thriller',
    'Fantasy & Science-Fiction',
    'Biografie',
    'Geschichte',
    'Politik & Gesellschaft',
    'Wirtschaft',
    'Psychologie',
    'Philosophie',
    'Wissenschaft',
    'Gesundheit',
    'Ratgeber',
    'Reise',
    'Kinder & Jugend',
    'Religion',
    'Kunst & Kultur'
  ];

  const ALIASES = new Map([
    ['sachbuch', 'Ratgeber'],
    ['self-help', 'Ratgeber'],
    ['self help', 'Ratgeber'],
    ['science fiction', 'Fantasy & Science-Fiction'],
    ['science-fiction', 'Fantasy & Science-Fiction'],
    ['fantasy', 'Fantasy & Science-Fiction'],
    ['thriller', 'Krimi & Thriller'],
    ['krimi', 'Krimi & Thriller'],
    ['politik', 'Politik & Gesellschaft'],
    ['gesellschaft', 'Politik & Gesellschaft'],
    ['kunst', 'Kunst & Kultur'],
    ['kultur', 'Kunst & Kultur']
  ]);

  const canonical = value => {
    const clean = String(value || '').trim();
    if (!clean) return null;
    const exact = GENRES.find(g => g.toLowerCase() === clean.toLowerCase());
    return exact || ALIASES.get(clean.toLowerCase()) || null;
  };

  function enhanceScanGenre() {
    const input = document.getElementById('scan-edit-genre');
    if (!input || input.dataset.genreTaxonomyEnhanced) return;
    input.dataset.genreTaxonomyEnhanced = 'true';

    const selected = new Set(
      String(input.value || '')
        .split(/[,;·]/)
        .map(canonical)
        .filter(Boolean)
        .slice(0, 2)
    );

    input.type = 'hidden';
    input.value = Array.from(selected).join(', ');

    const wrap = document.createElement('div');
    wrap.style.cssText = 'display:flex;flex-wrap:wrap;gap:7px;margin-top:2px;';

    function sync() {
      const checked = Array.from(wrap.querySelectorAll('input[type="checkbox"]:checked'));
      input.value = checked.map(el => el.value).join(', ');
    }

    GENRES.forEach(genre => {
      const label = document.createElement('label');
      label.style.cssText = 'display:inline-flex;align-items:center;gap:6px;border:1px solid var(--rule);padding:7px 9px;font-family:var(--sans);font-size:12px;line-height:1.2;cursor:pointer;';
      const checkbox = document.createElement('input');
      checkbox.type = 'checkbox';
      checkbox.value = genre;
      checkbox.checked = selected.has(genre);
      checkbox.style.cssText = 'margin:0;accent-color:var(--oxblood);';
      checkbox.onchange = () => {
        const checked = wrap.querySelectorAll('input[type="checkbox"]:checked');
        if (checked.length > 2) checkbox.checked = false;
        sync();
      };
      label.appendChild(checkbox);
      label.appendChild(document.createTextNode(genre));
      wrap.appendChild(label);
    });

    input.insertAdjacentElement('afterend', wrap);
    const hint = wrap.nextElementSibling;
    if (hint) {
      const de = ((window.LIB_CONFIG && window.LIB_CONFIG.lang) || 'en') === 'de';
      hint.textContent = de ? 'Bis zu zwei Kategorien auswählen.' : 'Select up to two categories.';
    }
  }

  const observer = new MutationObserver(enhanceScanGenre);
  observer.observe(document.documentElement, { childList: true, subtree: true });
  enhanceScanGenre();
})();
