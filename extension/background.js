// VDL — service worker : détection des médias, menu contextuel, envoi vers l'application.

const api = globalThis.chrome ?? globalThis.browser;
const DEFAULT_PORT = 9614;

// Types et extensions considérés comme des médias téléchargeables
const MEDIA_TYPES = /^(video\/|audio\/|application\/(x-mpegurl|vnd\.apple\.mpegurl|dash\+xml|octet-stream))/i;
const MEDIA_EXT = /\.(mp4|m4v|webm|mkv|mov|avi|flv|mp3|m4a|aac|ogg|opus|wav|flac|m3u8|mpd)(\?|#|$)/i;
const STREAM = /\.(m3u8|mpd)(\?|#|$)|mpegurl|dash\+xml/i;
// Fragments et flux internes inutiles seuls (la page elle-même sera passée à yt-dlp)
// Adresses internes de Telegram Web (« /stream/{"dcId"… ») : elles n'existent que dans l'onglet,
// déchiffrées avec la session de l'utilisateur, et ne peuvent pas être téléchargées ailleurs.
const IGNORE_URL = /web\.telegram\.org\/[^?#]*\/(stream|progressive)\/|%7B%22dcId|googlevideo\.com|\.ts(\?|$)|\.m4s(\?|$)|\/seg[-_]?\d+|\/fragment|\/chunk[-_]?\d+|\/range\/|[?&]range=/i;
// Segments HLS/DASH (souvent déguisés en .jpg/.png/.html) : jamais utiles seuls
const SEGMENT_TYPES = /^(video\/mp2t|video\/iso\.segment|audio\/mp2t)$/i;
const MIN_SIZE = 300 * 1024; // ignore les petits fichiers (pubs, sons d'interface)
const MAX_PER_TAB = 50;

const SCAN_PORTS = Array.from({ length: 11 }, (_, i) => DEFAULT_PORT + i); // 9614 → 9624

async function getPort() {
  const { port } = await api.storage.local.get("port");
  return Number(port) || DEFAULT_PORT;
}

async function pingPort(port) {
  try {
    const res = await fetch(`http://127.0.0.1:${port}/ping`, {
      headers: { "X-VDL-Client": "extension" },
      signal: AbortSignal.timeout(1500),
    });
    if (!res.ok) return null;
    const data = await res.json();
    return data && data.app === "VDL" ? { ...data, port } : null;
  } catch {
    return null;
  }
}

// Trouve VDL : port mémorisé d'abord, sinon on balaie les ports voisins (réglage modifié dans l'app)
async function ping() {
  const saved = await getPort();
  const found = await pingPort(saved);
  if (found) return found;
  const others = SCAN_PORTS.filter((p) => p !== saved);
  const results = await Promise.all(others.map(pingPort));
  const hit = results.find(Boolean);
  if (hit) await api.storage.local.set({ port: hit.port });
  return hit || null;
}

// --------------------------------------------------------------------------- envoi vers l'app

async function postDownload(port, payload) {
  const res = await fetch(`http://127.0.0.1:${port}/download`, {
    method: "POST",
    headers: { "Content-Type": "application/json", "X-VDL-Client": "extension" },
    body: JSON.stringify({ userAgent: navigator.userAgent, ...payload }),
  });
  return res.ok ? { ok: true } : { ok: false, error: `HTTP ${res.status}` };
}

async function sendToApp(payload) {
  try {
    return await postDownload(await getPort(), payload);
  } catch {
    // Port mémorisé injoignable : on recherche VDL puis on réessaie
    const app = await ping();
    if (!app) return { ok: false, error: "VDL n'est pas lancé" };
    try {
      return await postDownload(app.port, payload);
    } catch {
      return { ok: false, error: "VDL n'est pas lancé" };
    }
  }
}

// --------------------------------------------------------------------------- médias détectés par onglet

const key = (tabId) => `tab:${tabId}`;

async function getMedia(tabId) {
  const k = key(tabId);
  const data = await api.storage.session.get(k);
  return data[k] || [];
}

async function setMedia(tabId, list) {
  await api.storage.session.set({ [key(tabId)]: list });
  const n = list.length;
  api.action.setBadgeText({ tabId, text: n ? String(n) : "" });
  api.action.setBadgeBackgroundColor({ tabId, color: "#2d6cdf" });
}

// Sérialise les écritures pour ne pas perdre de détections simultanées
let queue = Promise.resolve();
function addMedia(tabId, item) {
  queue = queue.then(async () => {
    const list = await getMedia(tabId);
    const base = item.url.split("#")[0];
    if (list.some((m) => m.url.split("#")[0] === base)) return;
    list.unshift(item);
    await setMedia(tabId, list.slice(0, MAX_PER_TAB));
    api.tabs.sendMessage(tabId, { type: "vdl-media-detected" }, { frameId: item.frameId }).catch(() => {});
  }).catch(() => {});
}

function header(headers, name) {
  const h = (headers || []).find((x) => x.name.toLowerCase() === name);
  return h ? h.value : "";
}

// --------------------------------------------------------------------------- en-têtes réels du navigateur
// Les flux à jeton (index.m3u8?t=…&e=…) ne s'ouvrent qu'avec la requête exacte du navigateur :
// Referer, Origin, cookies, User-Agent… On les recopie pour que VDL rejoue la même requête.

const KEEP_HEADERS = /^(referer|origin|cookie|user-agent|authorization|accept-language|x-[\w-]+)$/i;
const sentHeaders = new Map(); // requestId → { nom: valeur }

const FILTER = { urls: ["<all_urls>"], types: ["media", "xmlhttprequest", "other", "object"] };

function onSend(d) {
  if (d.tabId < 0 || IGNORE_URL.test(d.url)) return;
  const h = {};
  for (const { name, value } of d.requestHeaders || []) {
    if (KEEP_HEADERS.test(name) && value) h[name] = value;
  }
  sentHeaders.set(d.requestId, h);
  if (sentHeaders.size > 2000) sentHeaders.delete(sentHeaders.keys().next().value);
}
try {
  // Chrome/Edge : « extraHeaders » est nécessaire pour voir Cookie, Referer et Origin
  api.webRequest.onSendHeaders.addListener(onSend, FILTER, ["requestHeaders", "extraHeaders"]);
} catch {
  api.webRequest.onSendHeaders.addListener(onSend, FILTER, ["requestHeaders"]); // Firefox
}
const forget = (d) => sentHeaders.delete(d.requestId);
api.webRequest.onCompleted.addListener(forget, FILTER);
api.webRequest.onErrorOccurred.addListener(forget, FILTER);

api.webRequest.onHeadersReceived.addListener(
  (d) => {
    if (d.tabId < 0 || d.statusCode >= 400 || IGNORE_URL.test(d.url)) return;
    const type = header(d.responseHeaders, "content-type").split(";")[0].trim().toLowerCase();
    const isStream = STREAM.test(d.url) || STREAM.test(type);
    const looksMedia = MEDIA_TYPES.test(type) || MEDIA_EXT.test(d.url);
    if (!looksMedia && !isStream) return;
    if (type === "application/octet-stream" && !MEDIA_EXT.test(d.url)) return;
    if (SEGMENT_TYPES.test(type)) return;

    let size = 0;
    const range = header(d.responseHeaders, "content-range");
    if (range.includes("/")) size = Number(range.split("/").pop()) || 0;
    else size = Number(header(d.responseHeaders, "content-length")) || 0;
    if (!isStream && size && size < MIN_SIZE) return;

    const headers = sentHeaders.get(d.requestId) || {};
    const refKey = Object.keys(headers).find((k) => k.toLowerCase() === "referer");
    addMedia(d.tabId, {
      url: d.url,
      type: isStream ? (/mpd|dash/i.test(d.url + type) ? "DASH" : "HLS") : (type || "média"),
      stream: isStream,
      size,
      frameId: d.frameId,
      referer: (refKey && headers[refKey]) || d.documentUrl || d.initiator || "",
      headers,
      time: Date.now(),
    });
  },
  FILTER,
  ["responseHeaders"]
);

// Nouvelle page dans l'onglet → on repart de zéro
api.tabs.onUpdated.addListener((tabId, info) => {
  if (info.status === "loading" && info.url) setMedia(tabId, []);
});
api.tabs.onRemoved.addListener((tabId) => api.storage.session.remove(key(tabId)));

// --------------------------------------------------------------------------- menu contextuel

api.runtime.onInstalled.addListener(() => {
  api.contextMenus.removeAll(() => {
    api.contextMenus.create({ id: "vdl-link", title: "Télécharger le lien avec VDL", contexts: ["link"] });
    api.contextMenus.create({ id: "vdl-media", title: "Télécharger ce média avec VDL", contexts: ["video", "audio"] });
    api.contextMenus.create({ id: "vdl-page", title: "Télécharger la vidéo de cette page avec VDL", contexts: ["page", "frame"] });
  });
});

api.contextMenus.onClicked.addListener((info, tab) => {
  const referer = info.frameUrl || info.pageUrl || tab?.url || "";
  let url = info.pageUrl;
  if (info.menuItemId === "vdl-link") url = info.linkUrl;
  if (info.menuItemId === "vdl-media") {
    // Un src « blob: » n'est pas téléchargeable : on envoie la page à yt-dlp
    url = info.srcUrl && /^https?:/i.test(info.srcUrl) ? info.srcUrl : info.pageUrl;
  }
  if (info.menuItemId === "vdl-page") url = info.frameUrl || info.pageUrl;
  if (url) sendToApp({ url, referer, title: tab?.title || "" }).then((r) => notifyTab(tab, r));
});

function notifyTab(tab, result) {
  if (!tab?.id) return;
  api.tabs.sendMessage(tab.id, { type: "vdl-toast", ...result }).catch(() => {});
}

// --------------------------------------------------------------------------- messages (popup, page)

api.runtime.onMessage.addListener((msg, sender, sendResponse) => {
  (async () => {
    switch (msg.type) {
      case "download": {
        const r = await sendToApp({ url: msg.url, referer: msg.referer || sender.tab?.url || "",
                                    headers: msg.headers || {}, alternatives: msg.alternatives || [],
                                    title: msg.title || sender.tab?.title || "", quick: !!msg.quick });
        sendResponse(r);
        break;
      }
      case "media":
        sendResponse(await getMedia(msg.tabId));
        break;
      case "frameMedia": {
        // Médias du cadre qui demande (lecteur intégré dans une iframe), sinon de tout l'onglet
        const all = await getMedia(sender.tab?.id);
        const mine = all.filter((m) => m.frameId === sender.frameId);
        sendResponse(mine.length ? mine : (sender.frameId === 0 ? all : []));
        break;
      }
      case "ping":
        sendResponse(await ping());
        break;
      default:
        sendResponse(null);
    }
  })();
  return true; // réponse asynchrone
});
