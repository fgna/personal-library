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

  function rewritePayload(base64) {
    if (!base64) return base64;
    try {
      const bytes = Uint8Array.from(atob(base64), c => c.charCodeAt(0));
      const payload = JSON.parse(new TextDecoder('utf-8').decode(bytes));
      if (payload && typeof payload === 'object') {
        payload.author = normalizeAllCapsAuthor(payload.author);
      }
      const json = JSON.stringify(payload);
      const encoded = new TextEncoder().encode(json);
      let binary = '';
      encoded.forEach(byte => { binary += String.fromCharCode(byte); });
      return btoa(binary);
    } catch (error) {
      console.warn('Could not normalize scan author case', error);
      return base64;
    }
  }

  ['__bookScanResult', '__bookMetadataResult'].forEach(name => {
    const original = window[name];
    if (typeof original !== 'function') return;
    window[name] = function (base64, error) {
      return original.call(this, rewritePayload(base64), error);
    };
  });
})();
