// Android-only editable final review for scanned books.
(function () {
  if (!window.AndroidBookSource) return;

  const native = window.AndroidBookSource;

  function decodePayload(base64) {
    const bytes = Uint8Array.from(atob(base64), c => c.charCodeAt(0));
    return JSON.parse(new TextDecoder('utf-8').decode(bytes));
  }

  function german() {
    return ((window.LIB_CONFIG && window.LIB_CONFIG.lang) || 'en') === 'de';
  }

  function escapeHtml(value) {
    return String(value == null ? '' : value)
      .replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
      .replace(/\"/g, '&quot;').replace(/'/g, '&#039;');
  }

  function labelText() {
    return german() ? {
      title: 'Titel', author: 'Autor', genre: 'Genre', year: 'Erstveröffentlichung', language: 'Sprache',
      summary: 'Kurzbeschreibung', mainIdea: 'Kernidee', source: 'Quellenreferenz', confidence: 'Sicherheit',
      add: 'Zur Bibliothek hinzufügen', addAnyway: 'Trotzdem hinzufügen', cancel: 'Abbrechen',
      duplicate: 'Ein ähnlicher Eintrag existiert bereits.', saveFailed: 'Speichern fehlgeschlagen',
      review: 'Erkanntes Buch prüfen', hint: 'Alle Felder können vor dem Speichern korrigiert werden.',
      genresHint: 'Mehrere Genres mit Komma trennen.',
      mergeTitle: 'Duplikat zusammenführen',
      mergeHint: 'Wähle für jedes unterschiedliche Feld, welcher Wert im Katalog bleiben soll.',
      existing: 'Bestehend', incoming: 'Neu', mergeSave: 'Auswahl übernehmen', separate: 'Als separaten Eintrag hinzufügen',
      empty: 'Leer', same: 'Gleich in beiden Einträgen',
      originalTitle: 'Originaltitel', keywords: 'Schlagwörter', read: 'Gelesen', summaryEn: 'Kurzbeschreibung (EN)',
      mainIdeaEn: 'Kernidee (EN)', openLibrary: 'Open-Library-ID', wikipedia: 'Wikipedia',
      originalLanguage: 'Originalsprache', country: 'Herkunftsland', period: 'Epoche', rating: 'Bewertung',
      mood: 'Stimmung', series: 'Reihe'
    } : {
      title: 'Title', author: 'Author', genre: 'Genre', year: 'First published', language: 'Language',
      summary: 'Summary', mainIdea: 'Main idea', source: 'Source reference', confidence: 'Confidence',
      add: 'Add to library', addAnyway: 'Add anyway', cancel: 'Cancel',
      duplicate: 'A similar entry already exists.', saveFailed: 'Save failed',
      review: 'Review recognized book', hint: 'All fields can be corrected before saving.',
      genresHint: 'Separate multiple genres with commas.',
      mergeTitle: 'Merge duplicate',
      mergeHint: 'For every differing field, choose which value should remain in the catalog.',
      existing: 'Existing', incoming: 'New', mergeSave: 'Apply selected values', separate: 'Add as separate entry',
      empty: 'Empty', same: 'Same in both entries',
      originalTitle: 'Original title', keywords: 'Keywords', read: 'Read', summaryEn: 'Summary (EN)',
      mainIdeaEn: 'Main idea (EN)', openLibrary: 'Open Library ID', wikipedia: 'Wikipedia',
      originalLanguage: 'Original language', country: 'Country of origin', period: 'Period', rating: 'Rating',
      mood: 'Mood', series: 'Series'
    };
  }

  function inputStyle(multiline) {
    return [
      'box-sizing:border-box','width:100%','border:1px solid var(--rule)','background:transparent',
      'padding:11px 12px','font-family:var(--serif)','font-size:16px','color:var(--ink)',
      multiline ? 'min-height:96px' : '', multiline ? 'line-height:1.4' : ''
    ].filter(Boolean).join(';');
  }

  function field(label, id, value, multiline, extra) {
    const control = multiline
      ? `<textarea id="${id}" style="${inputStyle(true)}">${escapeHtml(value || '')}</textarea>`
      : `<input id="${id}" value="${escapeHtml(value || '')}" ${extra || ''} style="${inputStyle(false)}" />`;
    return `<label style="display:block;font-family:var(--mono);font-size:9px;text-transform:uppercase;letter-spacing:.11em;color:var(--ink-3);margin:13px 0 5px">${escapeHtml(label)}</label>${control}`;
  }

  function cleanForCatalog(result) {
    const book = JSON.parse(JSON.stringify(result || {}));
    Object.keys(book).forEach(key => {
      if (key.startsWith('_')) delete book[key];
    });
    delete book.confidence;
    return book;
  }

  async function loadCatalog() {
    const response = await fetch('books.json', { cache: 'no-store' });
    const root = await response.json();
    return Array.isArray(root.books) ? root.books : [];
  }

  async function replaceJustAddedBook(editedBook) {
    const books = await loadCatalog();
    if (!books.length) throw new Error('Added book was not found in the local catalog.');
    const index = books.length - 1;
    const update = JSON.parse(native.updateBookEntry(index, JSON.stringify(editedBook)));
    if (!update.ok) throw new Error(update.error || 'Could not persist edited metadata.');
  }

  function normalizeMatch(value) {
    return String(value == null ? '' : value)
      .normalize('NFD').replace(/[\u0300-\u036f]/g, '')
      .toLowerCase().replace(/[^a-z0-9]+/g, ' ').trim();
  }

  async function findExistingDuplicate(title, author) {
    const books = await loadCatalog();
    const wantedTitle = normalizeMatch(title);
    const wantedAuthor = normalizeMatch(author);
    for (let index = 0; index < books.length; index += 1) {
      const book = books[index] || {};
      if (normalizeMatch(book.title) !== wantedTitle) continue;
      const existingAuthor = normalizeMatch(book.author);
      if (!wantedAuthor || !existingAuthor || wantedAuthor === existingAuthor) return { index, book };
    }
    return null;
  }

  function stable(value) {
    if (Array.isArray(value)) return '[' + value.map(stable).join(',') + ']';
    if (value && typeof value === 'object') {
      return '{' + Object.keys(value).sort().map(key => JSON.stringify(key) + ':' + stable(value[key])).join(',') + '}';
    }
    return JSON.stringify(value == null ? null : value);
  }

  function emptyValue(value) {
    return value == null || value === '' || (Array.isArray(value) && value.length === 0);
  }

  function displayValue(value, L) {
    if (value == null || value === '') return L.empty;
    if (Array.isArray(value)) return value.length ? value.map(v => String(v)).join(' · ') : L.empty;
    if (typeof value === 'object') return JSON.stringify(value);
    if (typeof value === 'boolean') return value ? (german() ? 'Ja' : 'Yes') : (german() ? 'Nein' : 'No');
    return String(value);
  }

  function mergeFieldLabel(key, L) {
    const known = {
      title: L.title,
      original_title: L.originalTitle,
      author: L.author,
      genre: L.genre,
      language: L.language,
      keywords: L.keywords,
      summary: L.summary,
      summary_en: L.summaryEn,
      read: L.read,
      year_published: L.year,
      main_idea: L.mainIdea,
      main_idea_en: L.mainIdeaEn,
      openlibrary_work_id: L.openLibrary,
      wikipedia_url: L.wikipedia,
      original_language: L.originalLanguage,
      country_of_origin: L.country,
      period: L.period,
      rating: L.rating,
      mood: L.mood,
      series: L.series
    };
    return known[key] || key.replace(/_/g, ' ');
  }

  function mergeKeys(existing, incoming) {
    const preferred = [
      'title', 'original_title', 'author', 'genre', 'year_published', 'language', 'original_language',
      'series', 'summary', 'main_idea', 'keywords', 'read', 'rating', 'mood', 'country_of_origin', 'period',
      'openlibrary_work_id', 'wikipedia_url', 'summary_en', 'main_idea_en'
    ];
    const keys = new Set([...Object.keys(existing || {}), ...Object.keys(incoming || {})]);
    const allowed = Array.from(keys).filter(key => !key.startsWith('_') && key !== 'confidence');
    const order = new Map(preferred.map((key, index) => [key, index]));
    return allowed.sort((a, b) => {
      const ai = order.has(a) ? order.get(a) : preferred.length;
      const bi = order.has(b) ? order.get(b) : preferred.length;
      return ai === bi ? a.localeCompare(b) : ai - bi;
    });
  }

  function openDuplicateMerge(existingInfo, incomingBook, onMerged, onSeparate) {
    const L = labelText();
    document.getElementById('android-duplicate-field-merge')?.remove();

    const existing = JSON.parse(JSON.stringify(existingInfo.book || {}));
    const incoming = JSON.parse(JSON.stringify(incomingBook || {}));
    const keys = mergeKeys(existing, incoming);
    const differing = keys.filter(key => stable(existing[key]) !== stable(incoming[key]));

    const overlay = document.createElement('div');
    overlay.id = 'android-duplicate-field-merge';
    overlay.style.cssText = 'position:fixed;inset:0;z-index:270;background:rgba(28,28,30,.42);display:flex;align-items:flex-end;';
    const sheet = document.createElement('div');
    sheet.style.cssText = 'width:100%;max-height:94vh;overflow:auto;background:var(--paper);border-top:1px solid var(--ink);padding:20px 18px calc(20px + var(--safe-bot));box-shadow:0 -18px 50px rgba(28,28,30,.18);';
    overlay.appendChild(sheet);
    document.body.appendChild(overlay);

    const rows = differing.map((key, index) => {
      const oldValue = existing[key];
      const newValue = incoming[key];
      const preferIncoming = emptyValue(oldValue) && !emptyValue(newValue);
      const name = `dup-field-${index}`;
      const card = (side, label, value, checked) => `
        <label style="display:grid;grid-template-columns:24px 1fr;gap:9px;align-items:start;border:1px solid ${checked ? 'var(--ink)' : 'var(--rule)'};padding:10px 11px;margin-top:7px;cursor:pointer">
          <input type="radio" name="${name}" value="${side}" ${checked ? 'checked' : ''} style="width:17px;height:17px;margin-top:2px" />
          <span>
            <span style="display:block;font-family:var(--mono);font-size:8px;text-transform:uppercase;letter-spacing:.11em;color:var(--ink-3);margin-bottom:3px">${escapeHtml(label)}</span>
            <span style="display:block;font-family:var(--serif);font-size:14px;line-height:1.4;white-space:pre-wrap;overflow-wrap:anywhere">${escapeHtml(displayValue(value, L))}</span>
          </span>
        </label>`;
      return `
        <section data-merge-key="${escapeHtml(key)}" style="border-top:1px solid var(--rule);padding:13px 0 15px">
          <div style="font-family:var(--mono);font-size:9px;text-transform:uppercase;letter-spacing:.11em;color:var(--ink-3)">${escapeHtml(mergeFieldLabel(key, L))}</div>
          ${card('existing', L.existing, oldValue, !preferIncoming)}
          ${card('incoming', L.incoming, newValue, preferIncoming)}
        </section>`;
    }).join('');

    sheet.innerHTML = `
      <div class="display" style="font-size:24px;margin-bottom:7px">${escapeHtml(L.mergeTitle)}</div>
      <div style="font-family:var(--serif);font-size:13px;color:var(--ink-3);margin-bottom:15px">${escapeHtml(L.mergeHint)}</div>
      ${rows || `<div style="font-family:var(--serif);font-size:15px;padding:15px 0;border-top:1px solid var(--rule)">${escapeHtml(L.same)}</div>`}
      <div id="dup-merge-error" style="display:none;border-top:1px solid var(--oxblood);padding:12px 2px;color:var(--oxblood);font-family:var(--serif);font-size:14px"></div>
      <button id="dup-merge-save" style="width:100%;border-top:1px solid var(--ink);padding:15px 2px;text-align:left;font-family:var(--sans);font-size:15px;color:var(--oxblood)">${escapeHtml(L.mergeSave)}</button>
      <button id="dup-merge-separate" style="width:100%;border-top:1px solid var(--rule);padding:15px 2px;text-align:left;font-family:var(--sans);font-size:15px">${escapeHtml(L.separate)}</button>
      <button id="dup-merge-cancel" style="width:100%;border-top:1px solid var(--rule);padding:15px 2px;text-align:left;font-family:var(--sans);font-size:15px">${escapeHtml(L.cancel)}</button>`;

    const close = () => overlay.remove();
    overlay.addEventListener('click', event => { if (event.target === overlay) close(); });
    sheet.querySelector('#dup-merge-cancel').onclick = close;
    const errorBox = sheet.querySelector('#dup-merge-error');

    sheet.querySelector('#dup-merge-save').onclick = async () => {
      const merged = JSON.parse(JSON.stringify(existing));
      differing.forEach((key, index) => {
        const selected = sheet.querySelector(`input[name="dup-field-${index}"]:checked`);
        if (!selected || selected.value === 'existing') return;
        if (Object.prototype.hasOwnProperty.call(incoming, key)) merged[key] = JSON.parse(JSON.stringify(incoming[key]));
        else delete merged[key];
      });
      if (!String(merged.title || '').trim()) {
        errorBox.style.display = 'block';
        errorBox.textContent = german() ? 'Titel darf nicht leer sein.' : 'Title must not be empty.';
        return;
      }
      try {
        const update = JSON.parse(native.updateBookEntry(Number(existingInfo.index), JSON.stringify(merged)));
        if (!update.ok) throw new Error(update.error || L.saveFailed);
        close();
        await onMerged();
      } catch (error) {
        errorBox.style.display = 'block';
        errorBox.textContent = `${L.saveFailed}: ${String(error && error.message || error)}`;
      }
    };

    sheet.querySelector('#dup-merge-separate').onclick = async () => {
      try {
        await onSeparate();
        close();
      } catch (error) {
        errorBox.style.display = 'block';
        errorBox.textContent = `${L.saveFailed}: ${String(error && error.message || error)}`;
      }
    };
  }

  function openEditableReview(result) {
    const L = labelText();
    const old = document.getElementById('android-book-review');
    if (old) old.remove();

    const overlay = document.createElement('div');
    overlay.id = 'android-book-review';
    overlay.style.cssText = 'position:fixed;inset:0;z-index:245;background:rgba(28,28,30,.36);display:flex;align-items:flex-end;';
    const sheet = document.createElement('div');
    sheet.style.cssText = 'width:100%;max-height:91vh;overflow:auto;background:var(--paper);border-top:1px solid var(--ink);padding:20px 18px calc(20px + var(--safe-bot));box-shadow:0 -18px 50px rgba(28,28,30,.16);';
    overlay.appendChild(sheet);
    document.body.appendChild(overlay);

    const genres = Array.isArray(result.genre) ? result.genre.join(', ') : String(result.genre || '');
    const confidence = Math.round((Number(result.confidence) || 0) * 100);
    const sourceParts = [];
    if (result.openlibrary_work_id) sourceParts.push(`Open Library ${result.openlibrary_work_id}`);
    if (Array.isArray(result._metadata_sources) && result._metadata_sources.length) sourceParts.push(result._metadata_sources.join(' · '));

    sheet.innerHTML = `
      <div class="display" style="font-size:24px;margin-bottom:7px">${L.review}</div>
      <div style="font-family:var(--serif);font-size:13px;color:var(--ink-3);margin-bottom:15px">${L.hint}</div>
      ${field(L.title, 'scan-edit-title', result.title, false)}
      ${field(L.author, 'scan-edit-author', result.author, false)}
      ${field(L.genre, 'scan-edit-genre', genres, false)}
      <div style="font-family:var(--serif);font-size:11px;color:var(--ink-3);margin-top:4px">${L.genresHint}</div>
      ${field(L.year, 'scan-edit-year', result.year_published == null ? '' : result.year_published, false, 'inputmode="numeric"')}
      ${field(L.language, 'scan-edit-language', result.language, false)}
      ${field(L.summary, 'scan-edit-summary', result.summary, true)}
      ${field(L.mainIdea, 'scan-edit-mainidea', result.main_idea, true)}
      <div style="margin-top:15px;border-top:1px solid var(--rule);padding-top:8px;display:grid;grid-template-columns:120px 1fr;gap:10px;font-size:12px">
        <div style="font-family:var(--mono);font-size:9px;text-transform:uppercase;letter-spacing:.1em;color:var(--ink-3)">${L.source}</div>
        <div style="font-family:var(--serif)">${escapeHtml(sourceParts.join(' · ') || '—')}</div>
      </div>
      <div style="padding:8px 0 14px;display:grid;grid-template-columns:120px 1fr;gap:10px;font-size:12px">
        <div style="font-family:var(--mono);font-size:9px;text-transform:uppercase;letter-spacing:.1em;color:var(--ink-3)">${L.confidence}</div>
        <div style="font-family:var(--serif)">${confidence}%</div>
      </div>
      <div id="scan-edit-error" style="display:none;border-top:1px solid var(--oxblood);padding:12px 2px;font-family:var(--serif);font-size:14px;line-height:1.45;color:var(--oxblood)"></div>
      <button id="scan-edit-add" style="width:100%;border-top:1px solid var(--ink);padding:15px 2px;text-align:left;font-family:var(--sans);font-size:15px;color:var(--oxblood)">${L.add}</button>
      <button id="scan-edit-cancel" style="width:100%;border-top:1px solid var(--rule);padding:15px 2px;text-align:left;font-family:var(--sans);font-size:15px">${L.cancel}</button>
    `;

    const close = () => overlay.remove();
    overlay.addEventListener('click', e => { if (e.target === overlay) close(); });
    sheet.querySelector('#scan-edit-cancel').onclick = close;
    const addButton = sheet.querySelector('#scan-edit-add');
    const errorBox = sheet.querySelector('#scan-edit-error');

    addButton.onclick = async () => {
      const title = sheet.querySelector('#scan-edit-title').value.trim();
      const author = sheet.querySelector('#scan-edit-author').value.trim();
      if (!title) {
        sheet.querySelector('#scan-edit-title').focus();
        return;
      }

      const edited = cleanForCatalog(result);
      edited.title = title;
      edited.author = author;
      edited.genre = sheet.querySelector('#scan-edit-genre').value
        .split(/[,;·]/).map(v => v.trim()).filter(Boolean).slice(0, 3);
      const yearText = sheet.querySelector('#scan-edit-year').value.trim();
      const year = Number.parseInt(yearText, 10);
      edited.year_published = Number.isFinite(year) && year > 0 ? year : null;
      edited.language = sheet.querySelector('#scan-edit-language').value.trim();
      edited.summary = sheet.querySelector('#scan-edit-summary').value.trim();
      edited.main_idea = sheet.querySelector('#scan-edit-mainidea').value.trim() || null;

      addButton.disabled = true;
      errorBox.style.display = 'none';
      try {
        const saved = JSON.parse(native.addRecognizedBook(title, author, false));
        if (saved.duplicate) {
          const existingInfo = await findExistingDuplicate(title, author);
          if (!existingInfo) throw new Error(german() ? 'Der gemeldete Duplikateintrag konnte nicht geladen werden.' : 'The reported duplicate could not be loaded.');
          openDuplicateMerge(
            existingInfo,
            edited,
            async () => {
              close();
              native.reloadLibrary();
            },
            async () => {
              const forced = JSON.parse(native.addRecognizedBook(title, author, true));
              if (!forced.ok) throw new Error(forced.error || L.saveFailed);
              await replaceJustAddedBook(edited);
              close();
              native.reloadLibrary();
            }
          );
          return;
        }
        if (!saved.ok) throw new Error(saved.error || L.saveFailed);
        await replaceJustAddedBook(edited);
        close();
        native.reloadLibrary();
      } catch (e) {
        console.error(e);
        errorBox.style.display = 'block';
        errorBox.textContent = `${L.saveFailed}: ${String(e && e.message || e)}`;
      } finally {
        addButton.disabled = false;
      }
    };
  }

  window.__bookMetadataResult = function (base64, error) {
    const busy = document.getElementById('android-book-busy');
    if (busy) busy.remove();
    if (error) { alert(error); return; }
    try { openEditableReview(decodePayload(base64)); }
    catch (e) { console.error(e); alert(String(e)); }
  };

  window.__bookScanResult = function (base64, error) {
    const busy = document.getElementById('android-book-busy');
    if (busy) busy.remove();
    if (error) { alert(error); return; }
    try { openEditableReview(decodePayload(base64)); }
    catch (e) { console.error(e); alert(String(e)); }
  };
})();
