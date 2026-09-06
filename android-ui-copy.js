// Keep user-facing Android UI model-agnostic and hide obsolete remote-reset controls.
(function () {
  if (!window.AndroidBookSource) return;

  function isGerman() {
    return ((window.LIB_CONFIG && window.LIB_CONFIG.lang) || 'en') === 'de';
  }

  function replaceText(text) {
    if (!text || !/Gemma/i.test(text)) return text;
    if (isGerman()) {
      return text
        .replace(/Gemma\s*\.litertlm/gi, 'Lokales KI-Modell')
        .replace(/Gemma/gi, 'Lokale KI');
    }
    return text
      .replace(/Gemma\s*\.litertlm/gi, 'Local AI model')
      .replace(/Gemma/gi, 'Local AI');
  }

  function removeObsoleteControls(root) {
    const scope = root && root.querySelectorAll ? root : document;
    const remoteReset = scope.querySelector && scope.querySelector('#android-use-remote');
    if (remoteReset) remoteReset.remove();
    if (root && root.id === 'android-use-remote') root.remove();
  }

  function neutralize(root) {
    removeObsoleteControls(root);
    if (!root || !root.ownerDocument && root !== document.body) return;

    const walker = document.createTreeWalker(root || document.body, NodeFilter.SHOW_TEXT);
    let node;
    while ((node = walker.nextNode())) {
      const next = replaceText(node.nodeValue);
      if (next !== node.nodeValue) node.nodeValue = next;
    }

    if (root && root.nodeType === Node.ELEMENT_NODE) {
      for (const attr of ['aria-label', 'title', 'placeholder']) {
        const value = root.getAttribute && root.getAttribute(attr);
        if (value) root.setAttribute(attr, replaceText(value));
      }
    }
  }

  function showLlmRequirement() {
    if (document.getElementById('android-llm-requirement')) return;

    const de = isGerman();
    const overlay = document.createElement('div');
    overlay.id = 'android-llm-requirement';
    overlay.style.cssText = 'position:fixed;inset:0;z-index:380;background:rgba(28,28,30,.36);display:flex;align-items:flex-end;';

    const sheet = document.createElement('div');
    sheet.style.cssText = 'width:100%;background:var(--paper);border-top:1px solid var(--ink);padding:22px 18px calc(22px + var(--safe-bot));box-shadow:0 -18px 50px rgba(28,28,30,.16);';
    sheet.innerHTML = `
      <div class="display" style="font-size:24px;margin-bottom:14px">${de ? 'Buch scannen' : 'Scan book'}</div>
      <div style="font-family:var(--serif);font-size:16px;line-height:1.5;margin-bottom:18px">
        ${de
          ? 'Zum Erkennen eines fotografierten Buches wird der Android LLM Service benötigt. Die Bibliothek selbst funktioniert auch ohne diesen Service.'
          : 'Android LLM Service is required to recognize a photographed book. The library itself works without this service.'}
      </div>
      <a id="android-llm-install" href="https://github.com/fgna/android-llm-service/releases" style="display:block;width:100%;box-sizing:border-box;border-top:1px solid var(--ink);padding:15px 2px;font-family:var(--sans);font-size:15px;color:var(--oxblood);text-decoration:none">
        ${de ? 'Android LLM Service installieren / einrichten' : 'Install / set up Android LLM Service'}
      </a>
      <button id="android-llm-close" style="width:100%;text-align:left;border-top:1px solid var(--rule);padding:15px 2px;font-family:var(--sans);font-size:15px">
        ${de ? 'Schließen' : 'Close'}
      </button>
    `;

    overlay.appendChild(sheet);
    document.body.appendChild(overlay);

    const close = () => overlay.remove();
    overlay.addEventListener('click', event => { if (event.target === overlay) close(); });
    sheet.querySelector('#android-llm-close').onclick = close;
  }

  function showScanSourceChoice() {
    document.getElementById('android-scan-source-choice')?.remove();
    const de = isGerman();
    const overlay = document.createElement('div');
    overlay.id = 'android-scan-source-choice';
    overlay.style.cssText = 'position:fixed;inset:0;z-index:390;background:rgba(28,28,30,.36);display:flex;align-items:flex-end;';
    const sheet = document.createElement('div');
    sheet.style.cssText = 'width:100%;background:var(--paper);border-top:1px solid var(--ink);padding:20px 18px calc(20px + var(--safe-bot));box-shadow:0 -18px 50px rgba(28,28,30,.16);';
    sheet.innerHTML = `
      <div class="display" style="font-size:24px;margin-bottom:14px">${de ? 'Buch einlesen' : 'Read book'}</div>
      <button id="android-scan-camera" style="width:100%;text-align:left;border-top:1px solid var(--ink);padding:16px 2px;font-family:var(--sans);font-size:15px;color:var(--oxblood)">${de ? 'Foto aufnehmen' : 'Take photo'}</button>
      <button id="android-scan-existing-photo" style="width:100%;text-align:left;border-top:1px solid var(--rule);padding:16px 2px;font-family:var(--sans);font-size:15px">${de ? 'Vorhandenes Foto auswählen' : 'Choose existing photo'}</button>
      <button id="android-scan-source-cancel" style="width:100%;text-align:left;border-top:1px solid var(--rule);padding:16px 2px;font-family:var(--sans);font-size:15px">${de ? 'Abbrechen' : 'Cancel'}</button>
    `;
    overlay.appendChild(sheet);
    document.body.appendChild(overlay);
    const close = () => overlay.remove();
    overlay.addEventListener('click', event => { if (event.target === overlay) close(); });
    sheet.querySelector('#android-scan-source-cancel').onclick = close;
    sheet.querySelector('#android-scan-camera').onclick = () => {
      close();
      try { window.AndroidBookSource.captureBook(); }
      catch (e) { console.error('Camera launch failed', e); }
    };
    sheet.querySelector('#android-scan-existing-photo').onclick = () => {
      close();
      try { window.AndroidBookSource.chooseBookPhoto(); }
      catch (e) { console.error('Photo picker launch failed', e); }
    };
  }

  function showDuplicateSearchProgress() {
    document.getElementById('android-duplicate-search-progress')?.remove();
    const overlay = document.createElement('div');
    overlay.id = 'android-duplicate-search-progress';
    overlay.style.cssText = 'position:fixed;inset:0;z-index:350;background:rgba(248,246,241,.97);display:grid;place-items:center;padding:30px;text-align:center;color:var(--ink);pointer-events:auto;';
    overlay.innerHTML = `<div><div style="font-family:var(--mono);font-size:10px;letter-spacing:.14em;text-transform:uppercase;color:var(--ink-3);margin-bottom:12px">${isGerman() ? 'Bibliothek' : 'Library'}</div><div style="font-family:var(--serif);font-size:18px">${isGerman() ? 'Duplikate werden gesucht…' : 'Searching for duplicates…'}</div><div class="boot-bar" style="margin-top:18px"></div></div>`;
    document.body.appendChild(overlay);

    let checks = 0;
    const timer = window.setInterval(() => {
      checks += 1;
      if (document.getElementById('android-duplicates-overlay') || checks > 120) {
        window.clearInterval(timer);
        overlay.remove();
      }
    }, 100);
  }

  window.__bookPhotoSelectionCancelled = function () {
    document.getElementById('android-book-busy')?.remove();
  };

  document.addEventListener('click', event => {
    const scan = event.target && event.target.closest ? event.target.closest('#android-scan-book-button') : null;
    if (scan) {
      event.preventDefault();
      event.stopImmediatePropagation();
      let ready = false;
      try { ready = !!window.AndroidBookSource.isLlmServiceReady(); }
      catch (_) { ready = false; }
      if (!ready) {
        showLlmRequirement();
        return;
      }
      showScanSourceChoice();
      return;
    }

    const target = event.target && event.target.closest ? event.target.closest('#android-find-duplicates') : null;
    if (target) showDuplicateSearchProgress();
  }, true);

  const observer = new MutationObserver(mutations => {
    for (const mutation of mutations) {
      for (const node of mutation.addedNodes) {
        if (node.nodeType === Node.TEXT_NODE) {
          const next = replaceText(node.nodeValue);
          if (next !== node.nodeValue) node.nodeValue = next;
        } else if (node.nodeType === Node.ELEMENT_NODE) {
          neutralize(node);
        }
      }
    }
    removeObsoleteControls(document);
  });

  function start() {
    neutralize(document.body);
    removeObsoleteControls(document);
    observer.observe(document.body, { childList: true, subtree: true });
  }

  if (document.body) start();
  else document.addEventListener('DOMContentLoaded', start, { once: true });
})();
