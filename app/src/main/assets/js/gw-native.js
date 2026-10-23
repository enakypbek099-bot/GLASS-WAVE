(() => {
  "use strict";
  if (window.__GW_NATIVE_JS_V10__) return;
  window.__GW_NATIVE_JS_V10__ = true;

  const B = window.GwBridge;
  const $ = id => document.getElementById(id);
  const STATE_KEY = "gw-playback-state-v10";
  let stored = [], pendingLibrary = null, libraryTimer = 0, signature = "";
  let lastSave = 0, backgroundTransition = false, reclaiming = false;
  let spatialAllowed = false, lastRouteKey = "";

  const parse = (value, fallback) => { try { return JSON.parse(String(value)); } catch (_) { return fallback; } };
  const ext = name => String(name || "AUDIO").split(".").pop().toUpperCase();
  const sourceFor = track => "https://appassets.androidplatform.net/tracks/" +
    encodeURIComponent(track.gwId) + "/" + encodeURIComponent(track.file?.name || "audio");

  const legacySetEngine = typeof window.setEngine === "function" ? window.setEngine : null;
  function disableLegacyHiRes() {
    try { localStorage.setItem("neonwave-hires", "0"); } catch (_) {}
    try { legacySetEngine?.(false, true); } catch (_) {}
    try { if (typeof hiRes !== "undefined") hiRes = false; } catch (_) {}
  }
  window.__GW_DISABLE_LEGACY_HIRES = disableLegacyHiRes;
  disableLegacyHiRes();

  const style = document.createElement("style");
  style.id = "gw-android-ui-v10";
  style.textContent = `html,body,button,a,label,input,[role="button"],.tab,.nav-item,.music-card,.track-row{-webkit-tap-highlight-color:transparent!important}
  button:focus:not(:focus-visible),a:focus:not(:focus-visible),input:focus:not(:focus-visible),[role="button"]:focus:not(:focus-visible){outline:none!important;box-shadow:none!important}
  button:focus-visible,a:focus-visible,input:focus-visible,[role="button"]:focus-visible{outline:2px solid rgba(244,234,208,.88)!important;outline-offset:3px!important}
  .gw-route-locked{opacity:.46!important;filter:saturate(.35)!important}`;
  document.head.appendChild(style);

  function toTrack(raw) {
    const seconds = Number(raw.durationMs || 0) / 1000;
    const verified = raw.sourceVerified === true;
    const sr = Number(raw.sampleRate || 0), bits = Number(raw.bitsPerSample || 0), format = ext(raw.name);
    return { gwId: raw.id, title: raw.title || raw.name || "Аудиотрек", artist: raw.artist || "Неизвестный автор",
      album: raw.album || "Без альбома", time: typeof fmt === "function" ? fmt(seconds) : "--:--",
      art: "vinyl", playlist: "local", duration: seconds, local: true,
      file: { name: raw.name || "audio", size: Number(raw.size || 0), lastModified: 0 }, coverUrl: raw.coverUrl || null,
      info: { fmt: format, sr, bits, ch: Number(raw.channels || 0), verified,
        hi: verified && bits > 16 && sr >= 44100,
        label: verified ? `${format} · ${(sr / 1000).toFixed(1)} кГц · ${bits} бит` : format } };
  }

  function truthfulBadge(track) {
    const i = track?.info; if (!i) return "";
    const flac = i.fmt === "FLAC" ? ' <span class="flac-tag">FLAC</span>' : "";
    const hi = i.verified === true && Number(i.bits) > 16 && Number(i.sr) >= 44100;
    return flac + (hi ? ' <span class="hr3d" title="Hi-Res source"><i>Hi-Res</i><b>SOURCE</b></span>' : "");
  }
  try { hrBadge = truthfulBadge; engineLabel = t => t?.info?.label || "GLASS WAVE"; } catch (_) {}

  function librarySignature(list) { return list.map(x => [x.id,x.title,x.artist,x.durationMs,x.coverUrl,x.sampleRate,x.bitsPerSample,x.sourceVerified].join("|")).join(";"); }
  function applyLibrary(list) {
    list = Array.isArray(list) ? list : [];
    const next = librarySignature(list); if (next === signature) return; signature = next; stored = list;
    const activeId = typeof currentTrack !== "undefined" && currentTrack ? currentTrack.gwId : "";
    try { localTracks = list.map(toTrack); queue = allTracks(); } catch (_) { return; }
    if (activeId) { const fresh = localTracks.find(t => t.gwId === activeId); if (fresh) { currentTrack = fresh; currentIndex = queue.indexOf(fresh); try { setTrackMeta(fresh); } catch (_) {} } }
    try { renderAllTracks(); } catch (_) {}
  }
  function replaceLibrary(list) { pendingLibrary = Array.isArray(list) ? list : []; clearTimeout(libraryTimer); libraryTimer = setTimeout(() => { const n = pendingLibrary; pendingLibrary = null; applyLibrary(n); }, 300); }
  window.GW_LIBRARY = Object.freeze({ replace: replaceLibrary, tracks: () => stored.slice() });
  window.addEventListener("gw:library-changed", e => replaceLibrary(e.detail));

  function dspMedia() {
    try { if (typeof audio !== "undefined" && audio) return audio; } catch (_) {}
    try { if (typeof webEl !== "undefined" && webEl) return webEl; } catch (_) {}
    return $("audio");
  }

  const boundMedia = new WeakSet();
  function bindMediaEvents(media) {
    if (!media || boundMedia.has(media)) return;
    boundMedia.add(media);
    ["timeupdate","play","pause","seeking","seeked","ended"].forEach(name =>
      media.addEventListener(name, () => {
        if (backgroundTransition && name === "pause") return;
        saveSnapshot(name !== "timeupdate");
      })
    );
  }

  function saveSnapshot(force, playingOverride) {
    if (window.__GW_QUALITY_OWNER === true || reclaiming) return;
    const media = dspMedia();
    if (typeof currentTrack === "undefined" || !currentTrack?.gwId || !media) return;
    const now = Date.now(); if (!force && now - lastSave < 1200) return; lastSave = now;
    const positionMs = Math.max(0, Math.round((media.currentTime || 0) * 1000));
    const playing = typeof playingOverride === "boolean" ? playingOverride : (!media.paused && !media.ended);
    try { B.updatePlaybackSnapshot(currentTrack.gwId, positionMs, playing); } catch (_) {}
    try { localStorage.setItem(STATE_KEY, JSON.stringify({ trackId: currentTrack.gwId, positionMs, paused: !playing })); } catch (_) {}
  }

  function playDsp(index, positionSeconds = 0, autoPlay = true, silentState = false) {
    disableLegacyHiRes();
    try { if (!queue.length) queue = allTracks(); } catch (_) { return; }
    if (index < 0 || index >= queue.length) return;
    const track = queue[index]; currentIndex = index; currentTrack = track; try { setTrackMeta(track); } catch (_) {}
    try { audio = webEl; } catch (_) {}
    const media = dspMedia(); if (!media) return;
    bindMediaEvents(media);
    const same = media.dataset?.gwTrackId === track.gwId && media.getAttribute("src");
    if (!same) { try { media.pause(); } catch (_) {} media.dataset.gwTrackId = track.gwId; media.src = sourceFor(track); media.preload = "auto"; try { media.load(); } catch (_) {} }
    const seek = () => { if (positionSeconds > 0 && Number.isFinite(media.duration)) { try { media.currentTime = Math.min(positionSeconds, Math.max(0, media.duration - .25)); } catch (_) {} } };
    if (media.readyState >= 1) seek(); else media.addEventListener("loadedmetadata", seek, { once: true });
    try { ensureAudioGraph(); } catch (e) { try { B.jsError("ensureAudioGraph: " + e); } catch (_) {} }
    try { if (typeof audioCtx !== "undefined" && audioCtx?.state === "suspended") audioCtx.resume().catch(() => {}); } catch (_) {}
    if (autoPlay) media.play().catch(() => { try { toast("Не удалось запустить трек."); } catch (_) {} }); else try { media.pause(); } catch (_) {}
    try { renderAllTracks(); updatePlayButtons(); } catch (_) {}
    if (!silentState) saveSnapshot(true);
  }

  let originalPlayTrack = null; try { originalPlayTrack = playTrack; } catch (_) {}
  try { playTrack = index => { let list = []; try { list = queue?.length ? queue : allTracks(); } catch (_) {}
    const track = list[index]; if (track?.gwId) playDsp(index, 0, true); else if (typeof originalPlayTrack === "function") originalPlayTrack(index); }; } catch (_) {}
  window.GW_DSP = Object.freeze({ play: playDsp, media: dspMedia });

  function installStableSeek() {
    const bar = $("seekBar"); if (!bar || bar.dataset.gwSeekV10) return; bar.dataset.gwSeekV10 = "1";
    let target = null, wasPlaying = false, committed = false;
    const begin = e => { if (window.__GW_QUALITY_OWNER === true || e.target !== bar) return; const m = dspMedia(); if (!m) return; wasPlaying = !m.paused && !m.ended; target = null; committed = false; };
    const preview = e => { if (window.__GW_QUALITY_OWNER === true || e.target !== bar) return; e.stopPropagation(); e.stopImmediatePropagation(); const m = dspMedia(); if (!m || !Number.isFinite(m.duration) || m.duration <= 0) return; target = m.duration * Number(bar.value || 0) / 100; const el = $("elapsed"); if (el && typeof fmt === "function") el.textContent = fmt(target); };
    const commit = e => { if (window.__GW_QUALITY_OWNER === true || e?.target !== bar || committed || target === null) return; e.stopPropagation(); e.stopImmediatePropagation(); const m = dspMedia(); if (!m || !Number.isFinite(m.duration)) return; committed = true; const at = Math.max(0, Math.min(target, Math.max(0, m.duration - .05))); target = null; let done = false;
      const finish = () => { if (done) return; done = true; m.removeEventListener("seeked", finish); if (wasPlaying && m.paused) m.play().catch(() => {}); saveSnapshot(true); };
      m.addEventListener("seeked", finish, { once: true }); try { m.currentTime = at; } catch (_) { finish(); } setTimeout(finish, 1000); };
    ["pointerdown","touchstart","mousedown"].forEach(n => window.addEventListener(n, begin, true));
    window.addEventListener("input", preview, true);
    ["change","pointerup","touchend","mouseup"].forEach(n => window.addEventListener(n, commit, true));
  }

  function enterBackground() {
    if (backgroundTransition || window.__GW_QUALITY_OWNER === true || reclaiming) return;
    const media = dspMedia();
    if (typeof currentTrack === "undefined" || !currentTrack?.gwId || !media || media.paused) return;
    backgroundTransition = true;
    saveSnapshot(true, true);
    try { media.pause(); } catch (_) {}
    try { if (typeof audioCtx !== "undefined" && audioCtx?.state === "running") audioCtx.suspend().catch(() => {}); } catch (_) {}
  }

  function reclaimDsp() {
    if (reclaiming || window.__GW_QUALITY_OWNER === true) return;
    backgroundTransition = false;
    const state = parse(B.backgroundState(), {});
    if (!state.active || state.engine === "GW_NATIVE" || !state.trackId) return;
    const track = (typeof localTracks !== "undefined" ? localTracks : []).find(t => t.gwId === state.trackId); if (!track) return;
    try { queue = allTracks(); } catch (_) { return; }
    const index = queue.indexOf(track); if (index < 0) return;
    reclaiming = true;
    playDsp(index, Number(state.positionMs || 0) / 1000, false, true);
    const media = dspMedia(); if (!media) { reclaiming = false; return; }
    const ready = () => {
      let latest = parse(B.backgroundState(), state);
      try { if (Number(latest.positionMs || 0) > 0 && Number.isFinite(media.duration)) media.currentTime = Math.min(Number(latest.positionMs) / 1000, Math.max(0, media.duration - .2)); } catch (_) {}
      try { B.qualityPause(); } catch (_) {}
      const began = Date.now();
      const take = () => { latest = parse(B.backgroundState(), latest); if (latest.isPlaying && Date.now() - began < 700) { setTimeout(take, 40); return; }
        try { if (typeof audioCtx !== "undefined" && audioCtx?.state === "suspended") audioCtx.resume().catch(() => {}); } catch (_) {}
        const finish = () => { try { B.claimForegroundPlayback(); } catch (_) {} reclaiming = false; saveSnapshot(true, state.isPlaying === true); };
        if (state.isPlaying) media.play().then(finish).catch(() => { reclaiming = false; }); else finish(); };
      take();
    };
    if (media.readyState >= 2) ready(); else media.addEventListener("canplay", ready, { once: true });
  }

  document.addEventListener("visibilitychange", () => { if (document.hidden) enterBackground(); else setTimeout(reclaimDsp, 80); });
  window.addEventListener("pagehide", enterBackground);
  window.addEventListener("gw:foreground-resume", () => setTimeout(reclaimDsp, 30));

  bindMediaEvents(dspMedia());

  document.addEventListener("click", e => { const t = e.target?.closest?.("#homeImport,#addMusicTab,[data-addmusic],#fileInput"); if (!t) return; e.preventDefault(); e.stopPropagation(); e.stopImmediatePropagation(); try { B.pickTracks(); } catch (_) {} }, true);

  function routeLabel(type) { return ({BUILTIN_SPEAKER:"ДИНАМИК",BUILTIN_EARPIECE:"ТЕЛЕФОН",WIRED_HEADPHONES:"НАУШНИКИ",WIRED_HEADSET:"ГАРНИТУРА",USB_DEVICE:"USB DAC",USB_HEADSET:"USB",BLUETOOTH_A2DP:"BLUETOOTH",BLE_HEADSET:"BLUETOOTH",BLE_SPEAKER:"BLUETOOTH",HEARING_AID:"HEARING AID",HDMI:"HDMI",HDMI_ARC:"HDMI",HDMI_EARC:"HDMI",AUX_LINE:"AUX",LINE_ANALOG:"LINE",LINE_DIGITAL:"LINE"})[type] || "AUTO"; }
  function refreshRoute(snapshot) {
    let s = snapshot; if (!s?.candidate) { try { s = parse(B.outputSnapshot(), {}); } catch (_) { s = {}; } }
    const c = s?.candidate || (s?.type ? s : null); spatialAllowed = c?.spatialCapable === true; window.__gwSpatialAllowed = spatialAllowed;
    document.querySelectorAll("[data-scene='cinema'],[data-scene='reality']").forEach(n => n.classList.toggle("gw-route-locked", !spatialAllowed));
    const key = c ? [c.id,c.type,c.address].join("|") : "AUTO"; if (key === lastRouteKey) return; lastRouteKey = key;
    const label = c ? routeLabel(c.type) : "AUTO"; if ($("olType")) $("olType").textContent = label; if ($("olChip")) $("olChip").textContent = "GW READY"; if ($("olLog")) $("olLog").innerHTML = `<div>Текущий выход: ${label}</div>`;
  }
  try { realityOutputAllowed = () => spatialAllowed; spOpen = () => spatialAllowed; } catch (_) {}
  window.addEventListener("gw:route-changed", e => refreshRoute(e.detail));
  document.addEventListener("click", e => { const b = e.target?.closest?.("button,[role='button'],.scene-card"); if (!b || spatialAllowed) return; const text = (b.textContent || "").replace(/\s+/g," ").toLowerCase(); if (!/3d\s*reality|reality sound|кино|cinema/.test(text)) return; e.preventDefault(); e.stopPropagation(); e.stopImmediatePropagation(); try { toast("Требуется внешний аудиовыход"); } catch (_) {} }, true);

  function restoreLocal() { const s = parse(localStorage.getItem(STATE_KEY), null); if (!s?.trackId) return; const t = (typeof localTracks !== "undefined" ? localTracks : []).find(x => x.gwId === s.trackId); if (!t) return; try { queue = allTracks(); const i = queue.indexOf(t); if (i >= 0) playDsp(i, Number(s.positionMs || 0) / 1000, false); } catch (_) {} }

  try { applyLibrary(parse(B.library(), [])); } catch (_) {}
  installStableSeek(); setTimeout(installStableSeek, 700);
  try { refreshRoute(parse(B.outputSnapshot(), {})); } catch (_) {}
  setTimeout(() => { const s = parse(B.backgroundState(), {}); if (s.active && s.engine !== "GW_NATIVE") reclaimDsp(); else if (!s.active) restoreLocal(); }, 250);
  try { B.jsLog("INFO", "GW_NATIVE_JS_V10 ready"); } catch (_) {}
})();
