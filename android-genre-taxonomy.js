// Android scan-review genre selector derived from the active books.json catalog.
(function () {
  if (!window.AndroidBookSource) return;

  let vocabularyPromise = null;

  function german() {
    return ((window.LIB_CONFIG && window.LIB_CONFIG.lang) || 'en') === 'de';
  }

  function loadVocabulary() {
    if (vocabularyPromise) return vocabularyPromise;
    vocabularyPromise = fetch('books.json', { cache: 'no-store' })
      .then(response => {
        if (!response.ok) throw new Error(`books.json HTTP ${response.status}`);
        return response.json();
      })
      .then(root => {
        const counts = new Map();
        const books = Array.isArray(root.books) ? root.books : [];
        books.forEach(book => {
          const genres = Array.isArray(book && book.genre) ? book.genre : [];
          genres.forEach(value => {
            const clean = String(value || '').trim();
            if (clean) counts.set(clean, (counts.get(clean) || 0) + 1);
          });
        });
        return Array.from(counts.keys()).sort((a, b) => {
          const countDiff = (counts.get(b) || 0) - (counts.get(a) || 0);
          return countDiff || a.localeCompare(b, 'de', { sensitivity: 'base' });
        });
      });
    return vocabularyPromise;
  }

  async function enhanceScanGenre() {
    const input = document.getElementById('scan-edit-genre');
    if (!input || input.dataset.genreTaxonomyEnhanced) return;
    input.dataset.genreTaxonomyEnhanced = 'loading';

    const originalValue = String(input.value || '');
    input.type = 'hidden';

    const wrap = document.createElement('div');
    wrap.style.cssText = 'margin-top:2px;';
    const status = document.createElement('div');
    status.style.cssText = 'font-family:var(--serif);font-size:12px;color:var(--ink-3);padding:8px 0;';
    status.textContent = german() ? 'Katalogkategorien werden geladen …' : 'Loading catalog genres …';
    wrap.appendChild(status);
    input.insertAdjacentElement('afterend', wrap);

    let vocabulary;
    try {
      vocabulary = await loadVocabulary();
    } catch (error) {
      console.error('Genre vocabulary load failed', error);
      status.textContent = german()
        ? 'Genre-Auswahl konnte nicht geladen werden. Vorhandene Zuordnung bleibt erhalten.'
        : 'Genre choices could not be loaded. Existing genres are kept.';
      input.dataset.genreTaxonomyEnhanced = 'error';
      return;
    }

    const canonicalByLower = new Map(vocabulary.map(value => [value.toLocaleLowerCase(), value]));
    const selected = new Set(
      originalValue
        .split(/[,;·]/)
        .map(value => canonicalByLower.get(value.trim().toLocaleLowerCase()))
        .filter(Boolean)
        .slice(0, 3)
    );

    wrap.innerHTML = '';
    input.dataset.genreTaxonomyEnhanced = 'true';

    const selectedWrap = document.createElement('div');
    selectedWrap.style.cssText = 'display:flex;flex-wrap:wrap;gap:7px;margin-bottom:8px;';

    const search = document.createElement('input');
    search.type = 'search';
    search.autocomplete = 'off';
    search.placeholder = german() ? 'Genre suchen …' : 'Search genres …';
    search.style.cssText = 'box-sizing:border-box;width:100%;border:1px solid var(--rule);background:transparent;padding:10px 11px;font-family:var(--sans);font-size:14px;color:var(--ink);';

    const matches = document.createElement('div');
    matches.style.cssText = 'display:none;border:1px solid var(--rule);border-top:0;max-height:190px;overflow:auto;';

    function sync() {
      input.value = Array.from(selected).join(', ');
    }

    function renderSelected() {
      selectedWrap.innerHTML = '';
      Array.from(selected).forEach(genre => {
        const chip = document.createElement('button');
        chip.type = 'button';
        chip.style.cssText = 'display:inline-flex;align-items:center;gap:7px;border:1px solid var(--ink-3);padding:7px 9px;font-family:var(--sans);font-size:12px;line-height:1.2;';
        const text = document.createElement('span');
        text.textContent = genre;
        const remove = document.createElement('span');
        remove.textContent = '×';
        remove.setAttribute('aria-hidden', 'true');
        chip.appendChild(text);
        chip.appendChild(remove);
        chip.onclick = () => {
          selected.delete(genre);
          sync();
          renderSelected();
          renderMatches();
        };
        selectedWrap.appendChild(chip);
      });
    }

    function currentMatches() {
      const query = search.value.trim().toLocaleLowerCase();
      return vocabulary
        .filter(genre => !selected.has(genre))
        .filter(genre => !query || genre.toLocaleLowerCase().includes(query))
        .slice(0, 12);
    }

    function addGenre(genre) {
      if (!genre || selected.has(genre) || selected.size >= 3) return;
      selected.add(genre);
      search.value = '';
      sync();
      renderSelected();
      renderMatches();
      search.focus();
    }

    function renderMatches() {
      matches.innerHTML = '';
      const values = currentMatches();
      if (!values.length || selected.size >= 3 || document.activeElement !== search) {
        matches.style.display = 'none';
        return;
      }
      values.forEach(genre => {
        const option = document.createElement('button');
        option.type = 'button';
        option.textContent = genre;
        option.style.cssText = 'display:block;width:100%;padding:9px 10px;text-align:left;border-top:1px solid var(--rule);font-family:var(--sans);font-size:13px;';
        option.onmousedown = event => event.preventDefault();
        option.onclick = () => addGenre(genre);
        matches.appendChild(option);
      });
      matches.style.display = 'block';
    }

    search.onfocus = renderMatches;
    search.oninput = renderMatches;
    search.onkeydown = event => {
      if (event.key !== 'Enter') return;
      event.preventDefault();
      const exact = canonicalByLower.get(search.value.trim().toLocaleLowerCase());
      addGenre(exact || currentMatches()[0]);
    };
    search.onblur = () => window.setTimeout(renderMatches, 120);

    wrap.appendChild(selectedWrap);
    wrap.appendChild(search);
    wrap.appendChild(matches);
    sync();
    renderSelected();

    const hint = wrap.nextElementSibling;
    if (hint) {
      hint.textContent = german()
        ? `Bis zu drei Genres aus ${vocabulary.length} vorhandenen Katalogkategorien auswählen.`
        : `Select up to three genres from ${vocabulary.length} categories already in the catalog.`;
    }
  }

  const observer = new MutationObserver(() => { enhanceScanGenre(); });
  observer.observe(document.documentElement, { childList: true, subtree: true });
  enhanceScanGenre();
})();
