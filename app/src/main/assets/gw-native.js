(() => {
  "use strict";
  if (window.__GW_STAGE3__) return;
  window.__GW_STAGE3__ = true;

  const B = window.GwBridge || null;
  const $ = id => document.getElementById(id);
  const parse = (value, fallback) => { try { return JSON.parse(String(value)); } catch (_) { return fallback; } };
  let tracks = [];
  let currentIndex = -1;

  function installStabilityFix() {
    const old = $("gw-stage3-style"); if (old) old.remove();
    const style = document.createElement("style");
    style.id = "gw-stage3-style";
    style.textContent = `
      #settingsScreen .settings-tabs{position:relative!important;top:auto!important;z-index:2!important}
      #settingsScreen .settings-panel{animation:none!important}
      #gwSavedTracks{display:grid;gap:12px;margin-top:16px}
      .gw-track{width:100%;display:flex;align-items:center;gap:14px;padding:14px;border:1px solid rgba(235,240,245,.28);border-radius:22px;background:rgba(166,184,197,.12);color:inherit;text-align:left}
      .gw-track-mark{width:48px;height:48px;border-radius:16px;display:grid;place-items:center;background:linear-gradient(145deg,#f4e6ba,#718698);color:#10202a;font-weight:900}
      .gw-track-copy{min-width:0;flex:1}.gw-track-title,.gw-track-artist{overflow:hidden;text-overflow:ellipsis;white-space:nowrap}
      .gw-track-title{font-weight:800;font-size:16px}.gw-track-artist{opacity:.68;margin-top:4px;font-size:13px}
      .gw-track-time{opacity:.62;font-size:12px}
    `;
    document.head.appendChild(style);
    $("nw-fx-pause")?.remove();
    document.documentElement.classList.remove("nw-fx-off");
    document.documentElement.classList.add("nw-fx-on");
  }

  function restoreAnalysis() {
    try { if (typeof setEngine === "function") setEngine(false, true); } catch (_) {}
    try { if (typeof hiRes !== "undefined") hiRes = false; } catch (_) {}
    try { localStorage.setItem("neonwave-hires", "0"); } catch (_) {}
  }

  function prepareNativeCard() {
    const open = $("hrOpen"), card = open && open.closest(".settings-card");
    if (card) {
      card.querySelector(".settings-kicker")?.replaceChildren(document.createTextNode("GW NATIVE · HI-RES ENGINE"));
      card.querySelector("h3")?.replaceChildren(document.createTextNode("GW Native"));
      card.querySelector("p")?.replaceChildren(document.createTextNode("Режим высокой точности будет доступен после подключения аудиодвижка."));
      if (open) open.textContent = "Состояние GW Native ›";
    }
    const toggle = $("hiResToggle");
    if (toggle) { toggle.checked = false; toggle.disabled = true; toggle.setAttribute("aria-disabled", "true"); }
  }

  function pickTracks() {
    try { return !!(B && B.pickTracks && B.pickTracks()); } catch (_) { return false; }
  }

  function readLibrary() {
    try { applyLibrary(parse(B.library(), {tracks:[]})); } catch (_) { applyLibrary({tracks:[]}); }
  }

  function applyLibrary(payload) {
    tracks = Array.isArray(payload?.tracks) ? payload.tracks : [];
    setText("trackCount", String(tracks.length));
    setText("albumCount", String(new Set(tracks.map(t => t.album).filter(Boolean)).size));
    setText("artistCount", String(new Set(tracks.map(t => t.artist).filter(Boolean)).size));
    renderLibrary();
  }

  function setText(id, value) { const node = $(id); if (node && node.textContent !== value) node.textContent = value; }
  function escapeHtml(value) { return String(value ?? "").replace(/[&<>"']/g, c => ({"&":"&amp;","<":"&lt;",">":"&gt;","\"":"&quot;","'":"&#39;"})[c]); }
  function duration(ms) { const s = Math.floor(Number(ms || 0) / 1000); return s ? `${Math.floor(s/60)}:${String(s%60).padStart(2,"0")}` : ""; }

  function renderLibrary() {
    const host = $("localTracks"); if (!host) return;
    let box = $("gwSavedTracks");
    if (!box) { box = document.createElement("div"); box.id = "gwSavedTracks"; host.prepend(box); }
    box.innerHTML = tracks.map((t, i) => `
      <button class="gw-track" type="button" data-gw-track="${i}">
        <span class="gw-track-mark">GW</span>
        <span class="gw-track-copy"><span class="gw-track-title">${escapeHtml(t.title || t.name)}</span><span class="gw-track-artist">${escapeHtml(t.artist || "Неизвестный исполнитель")}</span></span>
        <span class="gw-track-time">${duration(t.durationMs)}</span>
      </button>`).join("");
  }

  async function play(index) {
    const track = tracks[index], audio = $("audio");
    if (!track || !audio) return;
    currentIndex = index;
    audio.pause();
    audio.src = track.uri;
    audio.load();
    updateNowPlaying(track);
    try { await audio.play(); }
    catch (_) { safeToast("Файл ашылмады"); }
  }

  function updateNowPlaying(track) {
    const title = track.title || track.name;
    const artist = track.artist || "Неизвестный исполнитель";
    ["miniTitle","playerTitle","npTitle"].forEach(id => setText(id, title));
    ["miniSub","playerSub","npArtist"].forEach(id => setText(id, artist));
    setText("codecRead", String(track.mime || "AUDIO").split("/").pop().toUpperCase());
    const mini = $("miniPlayer"); if (mini) { mini.hidden = false; mini.classList.add("show","active"); }
  }

  function safeToast(text) {
    try { if (typeof toast === "function") { toast(text); return; } } catch (_) {}
    const node = $("toast"); if (!node) return;
    node.textContent = text; node.classList.add("show");
    clearTimeout(window.__gwToast); window.__gwToast = setTimeout(() => node.classList.remove("show"), 1600);
  }

  function renderRoute() {
    let route = {ok:false};
    try { route = parse(B.currentOutputDevice(), route); } catch (_) {}
    setText("olChip", route.ok ? "GW READY" : "AUTO");
    setText("olType", route.ok ? route.name : "AUTO");
    setText("olRate", "—"); setText("olBits", "—");
    const detail = $("olDetail");
    if (detail) detail.innerHTML = `<div>Выход: <b>${escapeHtml(route.ok ? route.name : "AUTO")}</b></div><div>Режим: <b>DSP</b></div>`;
  }

  document.addEventListener("click", event => {
    const trackButton = event.target?.closest?.("[data-gw-track]");
    if (trackButton) { event.preventDefault(); play(Number(trackButton.dataset.gwTrack)); return; }
    const add = event.target?.closest?.("#homeImport,#addMusicTab,#fileInput");
    if (add) { event.preventDefault(); event.stopImmediatePropagation(); pickTracks(); return; }
    const next = event.target?.closest?.("#miniNext,#nextBtn");
    if (next && tracks.length) { event.preventDefault(); play((currentIndex + 1 + tracks.length) % tracks.length); return; }
    const prev = event.target?.closest?.("#miniPrev,#prevBtn");
    if (prev && tracks.length) { event.preventDefault(); play((currentIndex - 1 + tracks.length) % tracks.length); return; }
    const refresh = event.target?.closest?.("#hrOpen,#olRefresh");
    if (refresh) { event.preventDefault(); renderRoute(); $("outLogCard")?.scrollIntoView({behavior:"smooth",block:"center"}); }
  }, true);

  document.addEventListener("change", event => {
    if (event.target?.id === "hiResToggle") { event.preventDefault(); event.stopImmediatePropagation(); event.target.checked = false; safeToast("GW Native дайындалуда"); }
  }, true);

  window.addEventListener("gw:library-changed", event => applyLibrary(event.detail || {tracks:[]}));
  window.addEventListener("error", event => { try { B?.jsError?.(String(event.message || "window.error")); } catch (_) {} });
  window.GW_LIBRARY = Object.freeze({ refresh: readLibrary, play, pick: pickTracks, tracks: () => tracks.slice() });

  installStabilityFix();
  restoreAnalysis();
  prepareNativeCard();
  renderRoute();
  readLibrary();
})();
