// Field-wise merge chooser for the general Android duplicate finder.
(function () {
  if (!window.AndroidBookSource) return;
  const native = window.AndroidBookSource;

  const german = () => ((window.LIB_CONFIG && window.LIB_CONFIG.lang) || 'en') === 'de';
  const text = () => german() ? {
    title: 'Einträge zusammenführen',
    hint: 'Wähle für jedes unterschiedliche Feld, welche Version erhalten bleiben soll.',
    save: 'Auswahl übernehmen',
    cancel: 'Abbrechen',
    empty: 'Leer',
    error: 'Einträge konnten nicht zusammengeführt werden.',
    version: 'Version'
  } : {
    title: 'Merge entries',
    hint: 'For every differing field, choose which version should be kept.',
    save: 'Apply selected values',
    cancel: 'Cancel',
    empty: 'Empty',
    error: 'Entries could not be merged.',
    version: 'Version'
  };

  const esc = value => String(value == null ? '' : value)
    .replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;').replace(/'/g, '&#039;');

  function stable(value) {
    if (Array.isArray(value)) return '[' + value.map(stable).join(',') + ']';
    if (value && typeof value === 'object') {
      return '{' + Object.keys(value).sort().map(key => JSON.stringify(key) + ':' + stable(value[key])).join(',') + '}';
    }
    return JSON.stringify(value == null ? null : value);
  }

  function empty(value) {
    return value == null || value === '' || (Array.isArray(value) && value.length === 0);
  }

  function display(value, L) {
    if (empty(value)) return L.empty;
    if (Array.isArray(value)) return value.map(v => String(v)).join(' · ');
    if (typeof value === 'object') return JSON.stringify(value);
    if (typeof value === 'boolean') return value ? (german() ? 'Ja' : 'Yes') : (german() ? 'Nein' : 'No');
    return String(value);
  }

  function label(key) {
    const de = {
      title:'Titel', original_title:'Originaltitel', author:'Autor', genre:'Genre', year_published:'Jahr',
      language:'Sprache', original_language:'Originalsprache', series:'Reihe', summary:'Kurzbeschreibung',
      summary_en:'Kurzbeschreibung (EN)', main_idea:'Kernidee', main_idea_en:'Kernidee (EN)', keywords:'Schlagwörter',
      read:'Gelesen', rating:'Bewertung', mood:'Stimmung', country_of_origin:'Herkunftsland', period:'Epoche',
      openlibrary_work_id:'Open-Library-ID', wikipedia_url:'Wikipedia'
    };
    const en = {
      title:'Title', original_title:'Original title', author:'Author', genre:'Genre', year_published:'Year',
      language:'Language', original_language:'Original language', series:'Series', summary:'Summary',
      summary_en:'Summary (EN)', main_idea:'Main idea', main_idea_en:'Main idea (EN)', keywords:'Keywords',
      read:'Read', rating:'Rating', mood:'Mood', country_of_origin:'Country of origin', period:'Period',
      openlibrary_work_id:'Open Library ID', wikipedia_url:'Wikipedia'
    };
    const known = german() ? de : en;
    return known[key] || key.replace(/_/g, ' ');
  }

  function allKeys(entries) {
    const preferred = [
      'title','original_title','author','genre','year_published','language','original_language','series',
      'summary','main_idea','keywords','read','rating','mood','country_of_origin','period',
      'openlibrary_work_id','wikipedia_url','summary_en','main_idea_en'
    ];
    const keys = new Set();
    entries.forEach(entry => Object.keys(entry.book || {}).forEach(key => {
      if (!key.startsWith('_') && key !== 'confidence') keys.add(key);
    }));
    const order = new Map(preferred.map((key, index) => [key, index]));
    return Array.from(keys).sort((a, b) => {
      const ai = order.has(a) ? order.get(a) : preferred.length;
      const bi = order.has(b) ? order.get(b) : preferred.length;
      return ai === bi ? a.localeCompare(b) : ai - bi;
    });
  }

  function differingKeys(entries) {
    return allKeys(entries).filter(key => {
      const values = entries.map(entry => stable((entry.book || {})[key]));
      return values.some(value => value !== values[0]);
    });
  }

  function defaultChoice(entries, key) {
    const populated = entries
      .map((entry, index) => ({ index, value: (entry.book || {})[key] }))
      .filter(item => !empty(item.value));
    if (populated.length === 1) return populated[0].index;
    return 0;
  }

  function findSelectedEntries(indices) {
    let result;
    try { result = JSON.parse(native.findDuplicateBooks()); } catch (_) { return []; }
    const wanted = new Set(indices.map(Number));
    const found = new Map();
    (result.groups || []).forEach(group => (group.entries || []).forEach(entry => {
      if (wanted.has(Number(entry.index))) found.set(Number(entry.index), entry);
    }));
    return indices.map(Number).map(index => found.get(index)).filter(Boolean);
  }

  function openChooser(entries, onDone) {
    const L = text();
    document.getElementById('android-general-field-merge')?.remove();
    const keys = differingKeys(entries);
    const overlay = document.createElement('div');
    overlay.id = 'android-general-field-merge';
    overlay.style.cssText = 'position:fixed;inset:0;z-index:430;background:rgba(28,28,30,.42);display:flex;align-items:flex-end;pointer-events:auto;';
    const sheet = document.createElement('div');
    sheet.style.cssText = 'width:100%;height:94vh;max-height:94vh;overflow:auto;background:var(--paper);border-top:1px solid var(--ink);padding:20px 18px calc(20px + var(--safe-bot));box-shadow:0 -18px 50px rgba(28,28,30,.18);';
    overlay.appendChild(sheet);
    document.body.appendChild(overlay);

    const rows = keys.map((key, fieldIndex) => {
      const chosen = defaultChoice(entries, key);
      const choices = entries.map((entry, entryIndex) => {
        const book = entry.book || {};
        const checked = entryIndex === chosen;
        const source = [book.title, book.author].filter(Boolean).join(' · ');
        return `<label style="display:grid;grid-template-columns:24px 1fr;gap:9px;align-items:start;border:1px solid ${checked ? 'var(--ink)' : 'var(--rule)'};padding:10px 11px;margin-top:7px;cursor:pointer">
          <input type="radio" name="general-merge-${fieldIndex}" value="${entryIndex}" ${checked ? 'checked' : ''} style="width:17px;height:17px;margin-top:2px" />
          <span>
            <span style="display:block;font-family:var(--mono);font-size:8px;text-transform:uppercase;letter-spacing:.11em;color:var(--ink-3);margin-bottom:3px">${esc(L.version)} ${entryIndex + 1}${source ? ' · ' + esc(source) : ''}</span>
            <span style="display:block;font-family:var(--serif);font-size:14px;line-height:1.4;white-space:pre-wrap;overflow-wrap:anywhere">${esc(display(book[key], L))}</span>
          </span>
        </label>`;
      }).join('');
      return `<section data-key="${esc(key)}" style="border-top:1px solid var(--rule);padding:13px 0 15px">
        <div style="font-family:var(--mono);font-size:9px;text-transform:uppercase;letter-spacing:.11em;color:var(--ink-3)">${esc(label(key))}</div>
        ${choices}
      </section>`;
    }).join('');

    sheet.innerHTML = `<div class="display" style="font-size:24px;margin-bottom:7px">${esc(L.title)}</div>
      <div style="font-family:var(--serif);font-size:13px;color:var(--ink-3);margin-bottom:15px">${esc(L.hint)}</div>
      ${rows}
      <div id="general-merge-error" style="display:none;border-top:1px solid var(--oxblood);padding:12px 2px;color:var(--oxblood);font-family:var(--serif);font-size:14px"></div>
      <button id="general-merge-save" style="width:100%;border-top:1px solid var(--ink);padding:15px 2px;text-align:left;font-family:var(--sans);font-size:15px;color:var(--oxblood)">${esc(L.save)}</button>
      <button id="general-merge-cancel" style="width:100%;border-top:1px solid var(--rule);padding:15px 2px;text-align:left;font-family:var(--sans);font-size:15px">${esc(L.cancel)}</button>`;

    const close = () => overlay.remove();
    sheet.querySelector('#general-merge-cancel').onclick = close;
    overlay.addEventListener('click', event => { if (event.target === overlay) close(); });

    sheet.querySelector('#general-merge-save').onclick = () => {
      const merged = JSON.parse(JSON.stringify(entries[0].book || {}));
      keys.forEach((key, fieldIndex) => {
        const selected = sheet.querySelector(`input[name="general-merge-${fieldIndex}"]:checked`);
        const entryIndex = selected ? Number(selected.value) : 0;
        const source = entries[entryIndex].book || {};
        if (Object.prototype.hasOwnProperty.call(source, key)) merged[key] = JSON.parse(JSON.stringify(source[key]));
        else delete merged[key];
      });
      if (!String(merged.title || '').trim()) {
        const box = sheet.querySelector('#general-merge-error');
        box.style.display = 'block';
        box.textContent = german() ? 'Titel darf nicht leer sein.' : 'Title must not be empty.';
        return;
      }
      let result;
      try {
        result = JSON.parse(native.mergeBookEntries(JSON.stringify(entries.map(entry => Number(entry.index))), JSON.stringify(merged)));
      } catch (error) {
        result = { ok:false, error:String(error) };
      }
      if (!result.ok) {
        const box = sheet.querySelector('#general-merge-error');
        box.style.display = 'block';
        box.textContent = result.error || L.error;
        return;
      }
      close();
      onDone();
    };
  }

  document.addEventListener('click', event => {
    const button = event.target && event.target.closest ? event.target.closest('.dup-merge') : null;
    if (!button) return;
    const group = button.closest('.dup-group');
    if (!group) return;
    const indices = Array.from(group.querySelectorAll('.dup-check:checked')).map(input => Number(input.dataset.index));
    if (indices.length < 2) return;
    const entries = findSelectedEntries(indices);
    if (entries.length < 2) return;
    event.preventDefault();
    event.stopPropagation();
    event.stopImmediatePropagation();
    openChooser(entries, () => {
      native.reloadLibrary();
    });
  }, true);
})();
