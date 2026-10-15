// VDL — panneau « Télécharger cette vidéo » façon IDM, sur n'importe quel site.
//
// Le panneau apparaît automatiquement :
//   - quand une vidéo démarre (événement « play ») ;
//   - quand l'extension capte un flux vidéo (MP4, HLS, DASH) dans ce cadre ;
//   - au survol d'une vidéo.
// Il fonctionne aussi dans les lecteurs intégrés (iframes) et en plein écran.

(() => {
  if (window.__vdlLoaded) return;
  window.__vdlLoaded = true;
  const api = globalThis.chrome ?? globalThis.browser;
  const MIN_W = 200, MIN_H = 100;
  const AUTO_HIDE_MS = 7000;
  // Sites bien gérés par yt-dlp : on envoie l'URL de la page (meilleures qualités + titre)
  const KNOWN = /(^|\.)(youtube\.com|youtube-nocookie\.com|youtu\.be|tiktok\.com|facebook\.com|instagram\.com|x\.com|twitter\.com|vimeo\.com|dailymotion\.com|twitch\.tv|reddit\.com|bilibili\.com|soundcloud\.com)$/i;

  const panel = document.createElement("div");
  panel.className = "vdl-panel";
  panel.innerHTML = `<button type="button" class="vdl-go">⬇ Télécharger cette vidéo</button><button type="button" class="vdl-x" title="Masquer">×</button>`;
  const goBtn = panel.querySelector(".vdl-go");
  const closeBtn = panel.querySelector(".vdl-x");

  const dismissed = new WeakSet();
  let current = null;       // vidéo à laquelle le panneau est attaché
  let hideTimer = null;
  let followTimer = null;
  let hovering = false;

  // ------------------------------------------------------------------ affichage

  function bigEnough(v) {
    const r = v.getBoundingClientRect();
    return r.width >= MIN_W && r.height >= MIN_H;
  }

  function bestVideo() {
    const vids = [...document.querySelectorAll("video")].filter(bigEnough);
    vids.sort((a, b) => (Number(!b.paused) - Number(!a.paused)) ||
      (b.clientWidth * b.clientHeight - a.clientWidth * a.clientHeight));
    return vids[0] || null;
  }

  function position() {
    const host = document.fullscreenElement || document.documentElement;
    if (panel.parentNode !== host) host.appendChild(panel);
    if (current && current.isConnected) {
      const r = current.getBoundingClientRect();
      panel.style.top = `${Math.max(4, r.top + 10)}px`;
      panel.style.left = `${Math.max(4, Math.min(r.right, innerWidth) - panel.offsetWidth - 10)}px`;
    } else {
      // Pas d'élément <video> visible (flux audio, lecteur exotique) : coin supérieur droit
      panel.style.top = "12px";
      panel.style.left = `${innerWidth - panel.offsetWidth - 16}px`;
    }
  }

  function show(video, { auto = false } = {}) {
    if (video && dismissed.has(video)) return;
    if (!video && window !== window.top) return;
    current = video;
    panel.classList.add("vdl-visible");
    position();
    clearInterval(followTimer);
    followTimer = setInterval(position, 500);   // suit la vidéo (scroll, redimensionnement)
    clearTimeout(hideTimer);
    if (auto) hideTimer = setTimeout(maybeHide, AUTO_HIDE_MS);
  }

  function hide() {
    panel.classList.remove("vdl-visible");
    clearInterval(followTimer);
  }

  function maybeHide() {
    if (!hovering) hide();
  }

  // 1) Lecture d'une vidéo
  document.addEventListener("play", (e) => {
    if (e.target instanceof HTMLVideoElement && bigEnough(e.target)) show(e.target, { auto: true });
  }, true);

  // 2) Flux détecté par l'extension dans ce cadre
  api.runtime.onMessage.addListener((msg) => {
    if (msg.type === "vdl-media-detected") {
      if (!panel.classList.contains("vdl-visible")) show(bestVideo(), { auto: true });
    } else if (msg.type === "vdl-toast" && window === window.top) {
      toast(msg);
    }
  });

  // 3) Survol (les lecteurs posent des calques au-dessus de <video> : test par coordonnées)
  document.addEventListener("mousemove", (e) => {
    if (panel.contains(e.target)) { hovering = true; clearTimeout(hideTimer); return; }
    hovering = false;
    for (const v of document.querySelectorAll("video")) {
      const r = v.getBoundingClientRect();
      if (r.width >= MIN_W && e.clientX >= r.left && e.clientX <= r.right && e.clientY >= r.top && e.clientY <= r.bottom) {
        show(v);
        hideTimer = setTimeout(maybeHide, 2500);
        return;
      }
    }
  }, { passive: true });

  document.addEventListener("fullscreenchange", () => {
    if (panel.classList.contains("vdl-visible")) position();
  });

  closeBtn.addEventListener("click", (e) => {
    e.preventDefault(); e.stopPropagation();
    if (current) dismissed.add(current);
    hide();
  });

  // ------------------------------------------------------------------ téléchargement

  function send(msg) {
    return new Promise((resolve) => api.runtime.sendMessage(msg, resolve));
  }

  // Choisit le meilleur média capté : playlist HLS/DASH principale, sinon le plus gros fichier
  // Les lecteurs HLS chargent une playlist « maître » puis des playlists par piste
  // (vidéo, audio « index-a1.m3u8 », sous-titres). Il faut la maître, jamais une piste audio seule.
  const AUDIO_TRACK = /([_\-\/.]a\d+[_\-.]|audio|[_\-\/]aud[_\-.]|mp4a|\/a\/)/i;
  function pickMedia(list) {
    const streams = list.filter((m) => m.stream);
    if (streams.length) {
      const oldestFirst = [...streams].reverse();
      const master = oldestFirst.find((m) => /master/i.test(m.url));
      const notAudio = oldestFirst.find((m) => !AUDIO_TRACK.test(m.url));
      return master || notAudio || oldestFirst[0];
    }
    const files = list.filter((m) => !m.stream).sort((a, b) => (b.size || 0) - (a.size || 0));
    return files[0] || null;
  }

  goBtn.addEventListener("click", async (e) => {
    e.preventDefault(); e.stopPropagation();
    const isTop = window === window.top;
    let url = location.href;
    let referer = location.href;
    let headers = {};
    let alternatives = [];

    if (!KNOWN.test(location.hostname)) {
      const media = pickMedia((await send({ type: "frameMedia" })) || []);
      const src = current?.currentSrc || current?.src || "";
      if (media) {
        url = media.url;
        referer = media.referer || referer;   // Referer réellement envoyé par le lecteur
        headers = media.headers || {};
        // Tous les flux captés : VDL vérifie lequel est la playlist maître (vidéo + audio)
        alternatives = (await send({ type: "frameMedia" }) || []).filter((m) => m.stream)
          .reverse().map((m) => m.url).slice(0, 15);
      }
      else if (/^https?:/i.test(src)) url = src;   // <video src="…mp4"> classique
      // sinon : l'URL du cadre (ex. page d'un hébergeur intégré), analysée par VDL
    }

    const r = await send({ type: "download", url, referer, headers, alternatives,
                           title: isTop ? document.title : "" });
    toast(r);
  });

  function toast(r) {
    const t = document.createElement("div");
    t.className = "vdl-toast" + (r && r.ok ? "" : " vdl-error");
    t.textContent = r && r.ok ? "Envoyé à VDL ✔" : `VDL : ${(r && r.error) || "erreur"}`;
    (document.fullscreenElement || document.documentElement).appendChild(t);
    setTimeout(() => t.remove(), 3000);
  }
})();
