// Android-only network self-test using the existing native HttpURLConnection bridge.
(function () {
  if (!window.AndroidBookSource || !window.__bookSourceResolve) return;

  const native = window.AndroidBookSource;
  const originalResolve = window.__bookSourceResolve;
  const pending = new Map();
  let seq = 1;

  window.__bookSourceResolve = function (requestId, base64, error) {
    const diag = pending.get(requestId);
    if (!diag) return originalResolve.apply(this, arguments);
    pending.delete(requestId);

    const text = String(error || '');
    const dnsFailure = /unable to resolve host|no address associated with hostname|unknownhost/i.test(text);
    const reachedHost = !dnsFailure;
    diag.resolve({ reachedHost, dnsFailure, detail: text || 'HTTP/JSON response received' });
  };

  function probe(url) {
    const requestId = `diag-${Date.now()}-${seq++}`;
    return new Promise(resolve => {
      pending.set(requestId, { resolve });
      try {
        native.requestBooks(url, requestId);
      } catch (e) {
        pending.delete(requestId);
        resolve({ reachedHost: false, dnsFailure: false, detail: String(e) });
      }
      setTimeout(() => {
        if (!pending.has(requestId)) return;
        pending.delete(requestId);
        resolve({ reachedHost: false, dnsFailure: false, detail: 'Timeout after 15 s' });
      }, 15000);
    });
  }

  async function runNetworkTest(button, resultBox) {
    button.disabled = true;
    button.textContent = 'Netzwerk wird getestet…';
    resultBox.textContent = '';

    const targets = [
      ['Open Library', 'https://openlibrary.org/search.json?q=test&limit=1'],
      ['Google Books', 'https://www.googleapis.com/books/v1/volumes?q=test&maxResults=1'],
      ['Crossref', 'https://api.crossref.org/works?query.title=test&rows=1']
    ];

    const lines = [];
    for (const [name, url] of targets) {
      const result = await probe(url);
      const status = result.dnsFailure ? 'DNS FEHLER' : (result.reachedHost ? 'ERREICHBAR' : 'FEHLER');
      lines.push(`${name}: ${status}\n${result.detail}`);
    }
    resultBox.textContent = lines.join('\n\n');
    button.disabled = false;
    button.textContent = 'Netzwerk testen';
  }

  function inject() {
    const overlay = document.getElementById('android-settings-overlay');
    if (!overlay || overlay.querySelector('#android-network-test')) return;
    const sheet = overlay.firstElementChild;
    if (!sheet) return;

    const section = document.createElement('div');
    section.style.cssText = 'margin-top:24px;border-top:1px solid var(--rule);padding-top:15px';
    section.innerHTML = `
      <div style="font-family:var(--mono);font-size:10px;text-transform:uppercase;letter-spacing:.12em;color:var(--ink-3);margin-bottom:8px">Netzwerkdiagnose</div>
      <button id="android-network-test" type="button" style="width:100%;text-align:left;padding:12px 2px;font-family:var(--sans);font-size:15px;color:var(--oxblood)">Netzwerk testen</button>
      <pre id="android-network-result" style="white-space:pre-wrap;word-break:break-word;font-family:var(--mono);font-size:10px;line-height:1.5;margin:8px 0 0;color:var(--ink-2)"></pre>
    `;
    sheet.appendChild(section);
    const button = section.querySelector('#android-network-test');
    const resultBox = section.querySelector('#android-network-result');
    button.onclick = () => runNetworkTest(button, resultBox);
  }

  const observer = new MutationObserver(inject);
  observer.observe(document.documentElement, { childList: true, subtree: true });
  inject();
})();
