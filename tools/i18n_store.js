// Shared edit-store for the i18n viewer pages and the review page. Backed by localStorage; since the
// folder is served over HTTP (one origin), every viewer and changes.html see the same pending edits.
// Shape: { [lang]: { [key]: newValue } }.
const I18N = (function () {
  const KEY = 'i18n-edits';
  const read = () => { try { return JSON.parse(localStorage.getItem(KEY)) || {}; } catch (e) { return {}; } };
  const write = (e) => localStorage.setItem(KEY, JSON.stringify(e));
  return {
    get(lang, key) { const m = read()[lang]; return m ? m[key] : undefined; },
    set(lang, key, val) { const e = read(); (e[lang] = e[lang] || {})[key] = val; write(e); },
    remove(lang, key) {
      const e = read();
      if (e[lang]) { delete e[lang][key]; if (!Object.keys(e[lang]).length) delete e[lang]; write(e); }
    },
    clearLang(lang) { const e = read(); delete e[lang]; write(e); },
    all() { return read(); },
    langs() { return Object.keys(read()); },
    count() { return Object.values(read()).reduce((n, m) => n + Object.keys(m).length, 0); },
    // Re-escape a string for an Android <string> body (inverse of the exporter's android_unescape).
    // Approximate: covers the common cases; wrap in quotes only when there's edge whitespace.
    androidEscape(s) {
      const t = s.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
                 .replace(/'/g, "\\'").replace(/"/g, '\\"').replace(/\n/g, '\\n').replace(/\t/g, '\\t');
      return /^\s|\s$/.test(s) ? '"' + t + '"' : t;
    },
    // A partial Android strings.xml containing only this language's edited keys, for Weblate upload.
    stringsXml(lang) {
      const m = this.all()[lang] || {};
      const rows = Object.keys(m).sort().map(k => `    <string name="${k}">${this.androidEscape(m[k])}</string>`);
      return '<?xml version="1.0" encoding="utf-8"?>\n<resources>\n' + rows.join('\n') + '\n</resources>\n';
    },
  };
})();
