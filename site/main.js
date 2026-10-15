// Détecte le système du visiteur et relie les boutons aux fichiers de la dernière version GitHub.

(() => {
  const cfg = window.VDL_CONFIG || {};
  const releasesPage = `https://github.com/${cfg.repo}/releases/latest`;
  const $ = (sel) => document.querySelector(sel);

  // ---------------------------------------------------------------- système du visiteur
  function detectOS() {
    const ua = navigator.userAgent;
    const platform = navigator.userAgentData?.platform || navigator.platform || "";
    if (/android/i.test(ua)) return "android";
    if (/iphone|ipad|ipod/i.test(ua) || (/mac/i.test(platform) && navigator.maxTouchPoints > 1)) return "ios";
    if (/mac/i.test(platform) || /macintosh/i.test(ua)) return "mac";
    if (/win/i.test(platform) || /windows/i.test(ua)) return "windows";
    return "other";
  }

  const LABELS = {
    windows: "Télécharger pour Windows",
    android: "Télécharger pour Android",
    mac: "Bientôt sur Mac",
    ios: "Pas encore disponible sur iPhone",
    other: "Télécharger VDL",
  };
  const MAIN_ASSET = { windows: "setup", android: "apk" };

  const os = detectOS();
  const card = document.querySelector(`.platform[data-os="${os}"]`);
  if (card) card.classList.add("current");

  $("#main-label").textContent = LABELS[os];
  if (os === "mac" || os === "ios") $("#main-dl").classList.add("disabled");

  if (cfg.contact) $("#contact-link").href = `mailto:${cfg.contact}`;
  $("#source-link").href = `https://github.com/${cfg.repo}`;

  // ---------------------------------------------------------------- liens de téléchargement
  const PATTERNS = {
    setup: /^VDL-Setup.*\.exe$/i,
    portable: /^VDL-Portable.*\.zip$/i,
    apk: /android-arm64-v8a\.apk$/i,          // la grande majorité des téléphones
    apk_universal: /android-universal\.apk$/i, // tous les téléphones (plus lourd)
    dmg: /\.dmg$/i,
  };

  function setLinks(assets) {
    for (const link of document.querySelectorAll("[data-asset]")) {
      const a = assets[link.dataset.asset];
      link.href = a ? a.url : releasesPage;
      if (a) link.title = `${a.name} · ${(a.size / 1048576).toFixed(0)} Mo`;
    }
    const main = MAIN_ASSET[os];
    if (main) {
      $("#main-dl").href = assets[main]?.url || releasesPage;
    } else if (os === "other") {
      $("#main-dl").href = "#telecharger";
    }
  }

  setLinks({}); // valeurs par défaut (page des versions) avant la réponse de GitHub

  fetch(`https://api.github.com/repos/${cfg.repo}/releases/latest`, { headers: { Accept: "application/vnd.github+json" } })
    .then((r) => (r.ok ? r.json() : Promise.reject(r.status)))
    .then((rel) => {
      const assets = {};
      for (const a of rel.assets || []) {
        for (const [key, re] of Object.entries(PATTERNS)) {
          if (re.test(a.name) && !assets[key]) {
            assets[key] = { url: a.browser_download_url, name: a.name, size: a.size };
          }
        }
      }
      setLinks(assets);
      const version = (rel.tag_name || "").replace(/^v/, "");
      const date = rel.published_at ? new Date(rel.published_at).toLocaleDateString("fr-FR") : "";
      const main = assets[MAIN_ASSET[os]];
      $("#main-meta").textContent = [
        version && `Version ${version}`,
        main && `${(main.size / 1048576).toFixed(0)} Mo`,
        date && `publiée le ${date}`,
      ].filter(Boolean).join(" · ") || $("#main-meta").textContent;
      $("#version-footer").textContent = version ? `version ${version}` : "";
    })
    .catch(() => { /* hors ligne ou pas encore de version : les liens mènent à la page GitHub */ });

  // Autres systèmes sous le bouton principal
  const others = Object.keys(MAIN_ASSET).filter((k) => k !== os);
  $("#others").innerHTML = others.length
    ? "Aussi pour " + others.map((k) => `<a href="#telecharger">${k === "windows" ? "Windows" : "Android"}</a>`).join(" et ")
    : "";
})();
