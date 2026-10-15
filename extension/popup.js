const api = globalThis.chrome ?? globalThis.browser;
const $ = (id) => document.getElementById(id);

function send(msg) {
  return new Promise((resolve) => api.runtime.sendMessage(msg, resolve));
}

function humanSize(n) {
  if (!n) return "";
  const u = ["o", "Ko", "Mo", "Go"];
  let i = 0;
  while (n >= 1024 && i < u.length - 1) { n /= 1024; i++; }
  return `${n.toFixed(i ? 1 : 0)} ${u[i]}`;
}

function fileName(url) {
  try {
    const p = new URL(url).pathname.split("/").filter(Boolean).pop();
    return decodeURIComponent(p || url);
  } catch { return url; }
}

function feedback(button, r) {
  button.textContent = r && r.ok ? "✔ Envoyé" : `✘ ${(r && r.error) || "erreur"}`;
  button.disabled = true;
}

(async () => {
  const [tab] = await api.tabs.query({ active: true, currentWindow: true });

  const { port } = await api.storage.local.get("port");
  $("port").value = port || 9614;
  $("port").addEventListener("change", async () => {
    await api.storage.local.set({ port: Number($("port").value) || 9614 });
    location.reload();
  });

  const app = await send({ type: "ping" });
  if (app && app.port) $("port").value = app.port;   // port trouvé automatiquement
  $("status").textContent = app ? `Connecté · v${app.version}` : "Application VDL non lancée";
  $("status").className = "status " + (app ? "ok" : "err");

  $("page").addEventListener("click", async (e) => {
    const r = await send({ type: "download", url: tab.url, referer: tab.url, title: tab.title });
    feedback(e.currentTarget, r);
  });

  const media = await send({ type: "media", tabId: tab.id });
  $("empty").hidden = media.length > 0;
  for (const m of media) {
    const li = document.createElement("li");
    li.innerHTML = `<div class="info"><div class="name"></div><div class="meta"></div></div><button>⬇</button>`;
    li.querySelector(".name").textContent = fileName(m.url);
    li.querySelector(".name").title = m.url;
    li.querySelector(".meta").textContent = [m.type, humanSize(m.size)].filter(Boolean).join(" · ");
    li.querySelector("button").addEventListener("click", async (e) => {
      const r = await send({ type: "download", url: m.url, referer: m.referer || tab.url,
                             headers: m.headers || {}, title: tab.title });
      feedback(e.currentTarget, r);
    });
    $("media").appendChild(li);
  }
})();
