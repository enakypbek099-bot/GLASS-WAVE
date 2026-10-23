(() => {
  "use strict";

  if (window.__GW_QUALITY_JS_V5__) return;
  window.__GW_QUALITY_JS_V5__ = true;

  const B = window.GwBridge;
  if (!B || typeof B.qualityStart !== "function") return;

  const $ = id => document.getElementById(id);
  const PREF_KEY = "gw-quality-mode-v5";
  const parse = (value, fallback) => {
    try { return JSON.parse(String(value)); }
    catch (_) { return fallback; }
  };

  let pref = "DSP";
  let owner = "DSP";
  let status = null;
  let pollTimer = 0;
  let pendingId = "";
  let pendingAt = 0;
  let transition = false;
  let panelObserver = null;
  let panelWriting = false;

  try {
    pref = localStorage.getItem(PREF_KEY) === "QUALITY"
      ? "QUALITY"
      : "DSP";
    localStorage.setItem("neonwave-hires", "0");
  } catch (_) {}

  const legacySetEngine = typeof window.setEngine === "function"
    ? window.setEngine
    : null;

  function stopLegacyEngine() {
    try { localStorage.setItem("neonwave-hires", "0"); } catch (_) {}
    try { legacySetEngine?.(false, true); } catch (_) {}
    try { if (typeof hiRes !== "undefined") hiRes = false; } catch (_) {}
  }

  stopLegacyEngine();

  const say = message => {
    try { toast(message); } catch (_) {}
  };

  const log = (level, message) => {
    try { B.jsLog(level, "QUALITY " + message); } catch (_) {}
  };

  const formatMs = ms => {
    const total = Math.max(0, Math.floor(Number(ms || 0) / 1000));
    return Math.floor(total / 60) + ":" + String(total % 60).padStart(2, "0");
  };

  const currentTrackSafe = () => {
    try { return currentTrack || null; }
    catch (_) { return null; }
  };

  const currentIndexSafe = () => {
    try { return typeof currentIndex === "number" ? currentIndex : -1; }
    catch (_) { return -1; }
  };

  const isWav = track => !!track && (
    /^(wav|wave)$/i.test(String(track.info?.fmt || "")) ||
    /\.wave?$/i.test(String(track.file?.name || ""))
  );

  function setOwner(next) {
    owner = next;
    window.__GW_QUALITY_OWNER = next === "QUALITY";
    syncToggle();
  }

  function savePreference() {
    try {
      localStorage.setItem(PREF_KEY, pref);
      localStorage.setItem("neonwave-hires", "0");
    } catch (_) {}
  }

  function silenceWebAudio() {
    const media = window.GW_DSP?.media?.() || $("audio");
    if (media) {
      try {
        media.pause();
        media.removeAttribute("src");
        media.dataset.gwTrackId = "";
        media.load();
      } catch (_) {}
    }

    try {
      if (typeof audioCtx !== "undefined" && audioCtx?.state === "running") {
        audioCtx.suspend().catch(() => {});
      }
    } catch (_) {}
  }

  function startQuality(track, positionMs, autoPlay) {
    if (!track?.gwId || !isWav(track)) return false;

    silenceWebAudio();
    setOwner("QUALITY");
    pendingId = track.gwId;
    pendingAt = Date.now();
    transition = true;

    let accepted = false;
    try {
      accepted = B.qualityStart(
        track.gwId,
        Math.max(0, Math.round(positionMs || 0)),
        !!autoPlay,
        -1,
        true
      );
    } catch (_) {}

    if (!accepted) {
      transition = false;
      setOwner("DSP");
      say("GW Native іске қосылмады");
      return false;
    }

    startPolling();
    return true;
  }

  function waitServiceStopped() {
    return new Promise(resolve => {
      const started = Date.now();
      const check = () => {
        const state = parse(B.qualityStatus(), {});
        if (!state.active || Date.now() - started >= 2000) {
          resolve(!state.active);
          return;
        }
        setTimeout(check, 60);
      };
      check();
    });
  }

  async function switchToDsp(index, positionMs, autoPlay) {
    if (transition) return;
    transition = true;
    try { B.qualityStop(); } catch (_) {}

    const stopped = await waitServiceStopped();
    if (!stopped) {
      transition = false;
      say("GW Native тоқтамады; DSP іске қосылмады");
      return;
    }

    setOwner("DSP");
    status = null;
    stopLegacyEngine();
    try {
      await window.GW_DSP.play(
        index,
        Number(positionMs || 0) / 1000,
        !!autoPlay
      );
    } catch (_) {}
    transition = false;
    ensureTruthPanel();
  }

  function setPreference(enabled) {
    const track = currentTrackSafe();
    const index = currentIndexSafe();

    if (enabled && track && !isWav(track)) {
      pref = "DSP";
      savePreference();
      setOwner("DSP");
      stopLegacyEngine();
      say("GW QUALITY әзірге тек WAV. Бұл формат DSP режимінде қалады.");
      ensureTruthPanel();
      return;
    }

    pref = enabled ? "QUALITY" : "DSP";
    savePreference();

    if (!enabled) {
      if (owner === "QUALITY" && index >= 0) {
        const native = status?.native || {};
        void switchToDsp(
          index,
          Number(native.positionMs || 0),
          native.state === "STARTED"
        );
      } else {
        setOwner("DSP");
      }
      ensureTruthPanel();
      return;
    }

    if (!track) {
      say("GW QUALITY қосылды. WAV трегін таңда.");
      syncToggle();
      ensureTruthPanel();
      return;
    }

    const media = window.GW_DSP?.media?.();
    const positionMs = media ? Number(media.currentTime || 0) * 1000 : 0;
    const playing = !!media && !media.paused && !media.ended;
    startQuality(track, positionMs, playing);
    ensureTruthPanel();
  }

  function syncTrack(trackId) {
    let list = [];
    try { list = allTracks(); } catch (_) {}
    const track = list.find(item => item?.gwId === trackId);
    if (!track) return;
    try {
      queue = list;
      currentIndex = list.indexOf(track);
      currentTrack = track;
      setTrackMeta(track);
      renderAllTracks();
    } catch (_) {}
  }

  function startPolling() {
    if (pollTimer) return;
    pollTimer = setInterval(poll, 350);
    poll();
  }

  function poll() {
    const state = parse(B.qualityStatus(), null);
    if (!state) return;
    status = state;

    if (owner !== "QUALITY") {
      clearInterval(pollTimer);
      pollTimer = 0;
      return;
    }

    if (pendingId) {
      const fresh =
        state.trackId === pendingId &&
        Number(state.heartbeat || 0) >= pendingAt;
      if (fresh) {
        pendingId = "";
        transition = false;
      } else if (Date.now() - pendingAt < 5000) {
        return;
      } else {
        pendingId = "";
        transition = false;
      }
    }

    if (state.qualityError) {
      const code = state.qualityError;
      pref = "DSP";
      savePreference();
      setOwner("DSP");
      status = null;
      say("GW Native қатесі: " + code);
      ensureTruthPanel();
      return;
    }

    if (state.trackId && currentTrackSafe()?.gwId !== state.trackId) {
      syncTrack(state.trackId);
    }

    paintTransport(state);
    ensureTruthPanel();
  }

  function paintTransport(state) {
    const native = state.native || {};
    const positionMs = Number(native.positionMs || 0);
    const durationMs = Number(native.durationMs || 0);
    const playing = native.state === "STARTED";
    const percent = durationMs > 0
      ? Math.min(100, 100 * positionMs / durationMs)
      : 0;

    const bar = $("seekBar");
    if (bar && durationMs > 0) bar.value = percent;
    const elapsed = $("elapsed");
    if (elapsed) elapsed.textContent = formatMs(positionMs);
    const duration = $("duration");
    if (duration && durationMs > 0) duration.textContent = formatMs(durationMs);
    const miniProgress = $("miniProgress");
    if (miniProgress) miniProgress.style.width = percent + "%";

    document.body.classList.toggle("is-playing", playing);
    try {
      if (typeof iconSvg === "function") {
        const icon = playing ? "pause" : "play";
        if ($("miniPlay")) $("miniPlay").innerHTML = iconSvg(icon, 18);
        if ($("bigPlay")) $("bigPlay").innerHTML = iconSvg(icon, 25);
      }
    } catch (_) {}
  }

  function syncToggle() {
    const toggle = $("hiResToggle");
    if (toggle) toggle.checked = owner === "QUALITY";
  }

  function row(label, value) {
    return `<div class="gwq-line"><span>${label}</span><b>${value}</b></div>`;
  }

  function sourceText() {
    const native = status?.native;
    if (owner === "QUALITY" && native?.nativeWav) {
      return `${native.sourceFormat} · ${(Number(native.sourceRate) / 1000).toFixed(1)} кГц · ${native.sourceBits} бит · ${native.sourceChannels} кан.`;
    }
    return currentTrackSafe()?.info?.label || "—";
  }

  function outputText() {
    const native = status?.native;
    if (owner !== "QUALITY") return "GLASS WAVE DSP · Web Audio";
    if (!native?.ok) return "—";
    return `${native.audioApi} · ${native.actualFormat} · ${native.actualRate} Гц · ${native.actualChannels} кан. · ${native.actualSharing} · dev ${native.actualDeviceId}`;
  }

  function createTruthCard() {
    const card = document.createElement("div");
    card.id = "gwQualityTruth";
    card.innerHTML = '<div class="gwq-title">GW NATIVE · QUALITY</div><div data-gwq-body></div>';
    return card;
  }

  function neutralizeMainCard() {
    const toggle = $("hiResToggle");
    const host = toggle?.closest?.(".settings-card");
    if (!host) return;
    const kicker = host.querySelector(".settings-kicker");
    if (kicker) kicker.textContent = "NATIVE OUTPUT";
    const title = host.querySelector("h2,h3");
    if (title) title.textContent = "GW Native · QUALITY";
    const paragraph = host.querySelector("p");
    if (paragraph) {
      paragraph.textContent = "WAV: GW Native → Oboe → AAudio → strict I32. FLAC/MP3/AAC/OGG: DSP режимі; EQ, Tone, Bass, 3D, 8D және Lab сақталады.";
    }
    const open = $("hrOpen");
    if (open) open.textContent = "GW Native SOURCE / OUTPUT ›";
  }

  function ensureTruthPanel() {
    if (panelWriting) return;
    panelWriting = true;
    neutralizeMainCard();

    const tabs = $("hrTabs");
    if (tabs) tabs.style.display = "none";

    const body = $("hrBody");
    if (body) {
      let card = body.querySelector(":scope > #gwQualityTruth");
      if (!card) card = createTruthCard();

      for (const child of Array.from(body.children)) {
        if (child !== card) child.remove();
      }
      if (card.parentElement !== body) body.appendChild(card);

      const native = status?.native || {};
      const box = card.querySelector("[data-gwq-body]");
      box.innerHTML =
        row("Режим", owner === "QUALITY" ? "QUALITY · GW Native" : "DSP · GLASS WAVE") +
        row("SOURCE", sourceText()) +
        row("OUTPUT", outputText()) +
        (owner === "QUALITY"
          ? row("Күй", native.state || "OPENING") +
            row("Растау", native.streamVerified === true ? "I32 + source rate ✓" : "—") +
            (native.error ? row("Қате", native.error) : "")
          : "") +
        '<small>I32 — қолданба stream форматы. Ол DAC bit-depth немесе bit-perfect output кепілі емес. QUALITY әзірге тек WAV.</small>';

      if (!panelObserver) {
        panelObserver = new MutationObserver(() => {
          if (!panelWriting) queueMicrotask(ensureTruthPanel);
        });
        panelObserver.observe(body, { childList: true });
      }
    }

    const title = $("hrTitle");
    if (title) title.textContent = "GW Native";
    const audioStatus = $("audioStatus");
    if (audioStatus) {
      audioStatus.textContent = owner === "QUALITY"
        ? (status?.native?.streamVerified === true
          ? `GW NATIVE · ${status.native.actualRate} Hz · I32`
          : "GW NATIVE · OPENING")
        : (currentTrackSafe()?.info?.label || "GLASS WAVE DSP");
    }

    syncToggle();
    panelWriting = false;
  }

  const css = document.createElement("style");
  css.id = "gw-quality-truth-v5";
  css.textContent = `
    #hrTabs { display: none !important; }
    #hrBody > :not(#gwQualityTruth) { display: none !important; }
    #gwQualityTruth { padding:14px;border:1px solid rgba(255,255,255,.14);border-radius:16px;background:rgba(255,255,255,.055);font:12px/1.5 system-ui;color:inherit; }
    .gwq-title { font-weight:900;letter-spacing:1px;margin-bottom:9px; }
    .gwq-line { display:flex;justify-content:space-between;gap:12px;padding:4px 0; }
    .gwq-line span { opacity:.58; }
    .gwq-line b { text-align:right; }
    #gwQualityTruth small { display:block;opacity:.55;margin-top:10px; }
  `;
  document.head.appendChild(css);

  window.addEventListener("change", event => {
    if (event.target?.id !== "hiResToggle") return;
    event.stopImmediatePropagation();
    event.preventDefault();
    setPreference(!!event.target.checked);
  }, true);

  window.addEventListener("click", event => {
    if (event.target?.closest?.("#hrOpen")) {
      setTimeout(ensureTruthPanel, 0);
    }
  }, true);

  let basePlay = null;
  try { basePlay = playTrack; } catch (_) {}

  if (typeof basePlay === "function") {
    playTrack = function(index) {
      let list = [];
      try { list = queue?.length ? queue : allTracks(); } catch (_) {}
      const track = list[index];

      if (pref === "QUALITY" && isWav(track) && track?.gwId) {
        try {
          queue = list;
          currentIndex = index;
          currentTrack = track;
          setTrackMeta(track);
        } catch (_) {}
        if (startQuality(track, 0, true)) return;
      }

      if (owner === "QUALITY") {
        void switchToDsp(index, 0, true);
        return;
      }

      if (pref === "QUALITY" && track && !isWav(track)) {
        pref = "DSP";
        savePreference();
        syncToggle();
        say("Бұл формат GLASS WAVE DSP режимінде ойнайды.");
      }

      basePlay(index);
    };
  }

  window.addEventListener("click", event => {
    if (owner !== "QUALITY") return;
    const play = event.target?.closest?.("#bigPlay,#miniPlay,#npPlay");
    const next = event.target?.closest?.("#nextBtn,[data-px='nextBtn']");
    const previous = event.target?.closest?.("#prevBtn,[data-px='prevBtn']");
    if (!play && !next && !previous) return;

    event.preventDefault();
    event.stopImmediatePropagation();

    if (next) B.qualityNext();
    else if (previous) B.qualityPrevious();
    else {
      const native = (status || parse(B.qualityStatus(), {})).native || {};
      if (native.state === "STARTED") B.qualityPause();
      else B.qualityPlay();
    }
    setTimeout(poll, 100);
  }, true);

  const initial = parse(B.qualityStatus(), {});
  if (
    initial.active &&
    initial.engine === "GW_NATIVE" &&
    !initial.qualityError
  ) {
    pref = "QUALITY";
    savePreference();
    setOwner("QUALITY");
    status = initial;
    startPolling();
    syncTrack(initial.trackId);
  } else {
    pref = "DSP";
    savePreference();
    setOwner("DSP");
  }

  window.setEngine = enabled => setPreference(!!enabled);
  ensureTruthPanel();

  window.GW_QUALITY = Object.freeze({
    mode: () => ({ pref, owner }),
    status: () => status
  });
})();
