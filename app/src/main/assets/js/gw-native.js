(() => {
  "use strict";

  if (window.__GW_NATIVE_JS_V11__) return;
  window.__GW_NATIVE_JS_V11__ = true;

  const B = window.GwBridge;
  const $ = id => document.getElementById(id);
  const STATE_KEY = "gw-playback-state-v11";

  let stored = [];
  let pendingLibrary = null;
  let libraryTimer = 0;
  let signature = "";
  let lastSave = 0;
  let backgroundTransition = false;
  let backgroundStarted = false;
  let reclaiming = false;
  let spatialAllowed = false;
  let lastRouteKey = "";

  const parse = (value, fallback) => {
    try { return JSON.parse(String(value)); }
    catch (_) { return fallback; }
  };

  const extension = name =>
    String(name || "AUDIO").split(".").pop().toUpperCase();

  const sourceFor = track =>
    "https://appassets.androidplatform.net/tracks/" +
    encodeURIComponent(track.gwId) + "/" +
    encodeURIComponent(track.file?.name || "audio");

  const style = document.createElement("style");
  style.id = "gw-android-ui-v11";
  style.textContent = `
    html,body,button,a,label,input,[role="button"],
    .tab,.nav-item,.music-card,.track-row {
      -webkit-tap-highlight-color: transparent !important;
    }
    button:focus:not(:focus-visible),
    a:focus:not(:focus-visible),
    input:focus:not(:focus-visible),
    [role="button"]:focus:not(:focus-visible) {
      outline: none !important;
      box-shadow: none !important;
    }
    button:focus-visible,
    a:focus-visible,
    input:focus-visible,
    [role="button"]:focus-visible {
      outline: 2px solid rgba(244,234,208,.88) !important;
      outline-offset: 3px !important;
    }
    .gw-route-locked {
      opacity: .46 !important;
      filter: saturate(.35) !important;
    }
  `;
  document.head.appendChild(style);

  function toTrack(raw) {
    const seconds = Number(raw.durationMs || 0) / 1000;
    const verified = raw.sourceVerified === true;
    const sampleRate = Number(raw.sampleRate || 0);
    const bits = Number(raw.bitsPerSample || 0);
    const channels = Number(raw.channels || 0);
    const format = extension(raw.name);

    return {
      gwId: raw.id,
      title: raw.title || raw.name || "Аудиотрек",
      artist: raw.artist || "Неизвестный автор",
      album: raw.album || "Без альбома",
      time: typeof fmt === "function" ? fmt(seconds) : "--:--",
      art: "vinyl",
      playlist: "local",
      duration: seconds,
      local: true,
      file: {
        name: raw.name || "audio",
        size: Number(raw.size || 0),
        lastModified: 0
      },
      coverUrl: raw.coverUrl || null,
      info: {
        fmt: format,
        sr: sampleRate,
        bits,
        ch: channels,
        verified,
        hi: verified && bits > 16 && sampleRate >= 44100,
        label: verified
          ? `${format} · ${(sampleRate / 1000).toFixed(1)} кГц · ${bits} бит`
          : format
      }
    };
  }

  function truthfulBadge(track) {
    const info = track?.info;
    if (!info) return "";
    const flac = info.fmt === "FLAC"
      ? ' <span class="flac-tag">FLAC</span>'
      : "";
    const hi = info.verified === true &&
      Number(info.bits || 0) > 16 &&
      Number(info.sr || 0) >= 44100;
    return flac + (hi
      ? ' <span class="hr3d" title="Hi-Res source"><i>Hi-Res</i><b>SOURCE</b></span>'
      : "");
  }

  try {
    hrBadge = truthfulBadge;
    engineLabel = track => track?.info?.label || "GLASS WAVE";
  } catch (_) {}

  function librarySignature(list) {
    return list.map(item => [
      item.id,
      item.title,
      item.artist,
      item.durationMs,
      item.coverUrl,
      item.sampleRate,
      item.bitsPerSample,
      item.sourceVerified
    ].join("|")).join(";");
  }

  function applyLibrary(list) {
    list = Array.isArray(list) ? list : [];
    const nextSignature = librarySignature(list);
    if (nextSignature === signature) return;
    signature = nextSignature;
    stored = list;

    const activeId = typeof currentTrack !== "undefined" && currentTrack
      ? currentTrack.gwId
      : "";

    try {
      localTracks = list.map(toTrack);
      queue = allTracks();
    } catch (_) {
      return;
    }

    if (activeId) {
      const fresh = localTracks.find(track => track.gwId === activeId);
      if (fresh) {
        currentTrack = fresh;
        currentIndex = queue.indexOf(fresh);
        try { setTrackMeta(fresh); } catch (_) {}
      }
    }

    try { renderAllTracks(); } catch (_) {}
  }

  function replaceLibrary(list) {
    pendingLibrary = Array.isArray(list) ? list : [];
    clearTimeout(libraryTimer);
    libraryTimer = setTimeout(() => {
      const next = pendingLibrary;
      pendingLibrary = null;
      applyLibrary(next);
    }, 300);
  }

  window.GW_LIBRARY = Object.freeze({
    replace: replaceLibrary,
    tracks: () => stored.slice()
  });

  window.addEventListener("gw:library-changed", event => {
    replaceLibrary(event.detail);
  });

  function dspMedia() {
    try { if (typeof webEl !== "undefined" && webEl) return webEl; }
    catch (_) {}
    return $("audio");
  }

  const boundMedia = new WeakSet();

  function bindMediaEvents(media) {
    if (!media || boundMedia.has(media)) return;
    boundMedia.add(media);

    ["timeupdate", "play", "pause", "seeking", "seeked", "ended"]
      .forEach(name => {
        media.addEventListener(name, () => {
          if (backgroundTransition && name === "pause") return;
          saveSnapshot(name !== "timeupdate");
        });
      });
  }

  function saveSnapshot(force, playingOverride) {
    if (window.__GW_QUALITY_OWNER === true || reclaiming) return;
    const media = dspMedia();

    if (
      typeof currentTrack === "undefined" ||
      !currentTrack?.gwId ||
      !media
    ) return;

    const now = Date.now();
    if (!force && now - lastSave < 1200) return;
    lastSave = now;

    const positionMs = Math.max(
      0,
      Math.round((media.currentTime || 0) * 1000)
    );

    const playing = typeof playingOverride === "boolean"
      ? playingOverride
      : (!media.paused && !media.ended);

    try {
      B.updatePlaybackSnapshot(
        currentTrack.gwId,
        positionMs,
        playing
      );
    } catch (_) {}

    try {
      localStorage.setItem(
        STATE_KEY,
        JSON.stringify({
          trackId: currentTrack.gwId,
          positionMs,
          paused: !playing
        })
      );
    } catch (_) {}
  }

  function prepareDsp(index, positionSeconds = 0) {
    try {
      if (!queue.length) queue = allTracks();
    } catch (_) {
      return null;
    }

    if (index < 0 || index >= queue.length) return null;

    const track = queue[index];
    currentIndex = index;
    currentTrack = track;
    try { setTrackMeta(track); } catch (_) {}

    const media = dspMedia();
    if (!media) return null;
    try { audio = media; } catch (_) {}
    bindMediaEvents(media);

    const same =
      media.dataset?.gwTrackId === track.gwId &&
      media.getAttribute("src");

    if (!same) {
      try { media.pause(); } catch (_) {}
      media.dataset.gwTrackId = track.gwId;
      media.src = sourceFor(track);
      media.preload = "auto";
      try { media.load(); } catch (_) {}
    }

    const seek = () => {
      if (positionSeconds > 0 && Number.isFinite(media.duration)) {
        try {
          media.currentTime = Math.min(
            positionSeconds,
            Math.max(0, media.duration - .25)
          );
        } catch (_) {}
      }
    };

    if (media.readyState >= 1) seek();
    else media.addEventListener("loadedmetadata", seek, { once: true });

    try {
      renderAllTracks();
      updatePlayButtons();
    } catch (_) {}

    return { media, track };
  }

  async function activateDspGraph(media) {
    try { audio = media; } catch (_) {}
    try { ensureAudioGraph(); }
    catch (error) {
      try { B.jsError("ensureAudioGraph: " + error); } catch (_) {}
    }

    try {
      if (
        typeof audioCtx !== "undefined" &&
        audioCtx &&
        audioCtx.state === "suspended"
      ) {
        await audioCtx.resume();
      }
    } catch (_) {}
  }

  async function playDsp(index, positionSeconds = 0, autoPlay = true) {
    const prepared = prepareDsp(index, positionSeconds);
    if (!prepared) return false;

    const { media } = prepared;
    await activateDspGraph(media);

    if (autoPlay) {
      try {
        await media.play();
      } catch (_) {
        try { toast("Не удалось запустить трек."); } catch (_) {}
        return false;
      }
    } else {
      try { media.pause(); } catch (_) {}
    }

    saveSnapshot(true);
    return true;
  }

  try { playTrack = index => { void playDsp(index, 0, true); }; }
  catch (_) {}

  window.GW_DSP = Object.freeze({
    play: playDsp,
    prepare: prepareDsp,
    activate: activateDspGraph,
    media: dspMedia
  });

  function installSingleSeekOwner() {
    const original = $("seekBar");
    if (!original || original.dataset.gwSeekOwner === "1") return;

    /*
     * HTML seek listener-лерін тасымалдамайтын дәл көшірме.
     * Осыдан кейін seek-ті тек осы файл басқарады.
     */
    const bar = original.cloneNode(true);
    bar.dataset.gwSeekOwner = "1";
    original.replaceWith(bar);

    let targetMs = null;
    let wasPlaying = false;
    let committed = false;

    const begin = () => {
      committed = false;
      targetMs = null;
      if (window.__GW_QUALITY_OWNER === true) {
        const state = parse(B.qualityStatus(), {});
        wasPlaying = state?.native?.state === "STARTED";
      } else {
        const media = dspMedia();
        wasPlaying = !!media && !media.paused && !media.ended;
      }
    };

    const preview = () => {
      if (window.__GW_QUALITY_OWNER === true) {
        const state = parse(B.qualityStatus(), {});
        const durationMs = Number(state?.native?.durationMs || 0);
        if (durationMs <= 0) return;
        targetMs = durationMs * Number(bar.value || 0) / 100;
      } else {
        const media = dspMedia();
        if (!media || !Number.isFinite(media.duration) || media.duration <= 0) return;
        targetMs = media.duration * 1000 * Number(bar.value || 0) / 100;
      }

      const elapsed = $("elapsed");
      if (elapsed && typeof fmt === "function") {
        elapsed.textContent = fmt(Number(targetMs || 0) / 1000);
      }
    };

    const commit = () => {
      if (committed || targetMs === null) return;
      committed = true;
      const wanted = Math.max(0, Math.round(targetMs));
      targetMs = null;

      if (window.__GW_QUALITY_OWNER === true) {
        try { B.qualitySeek(wanted); } catch (_) {}
        return;
      }

      const media = dspMedia();
      if (!media || !Number.isFinite(media.duration)) return;

      const seconds = Math.min(
        wanted / 1000,
        Math.max(0, media.duration - .05)
      );

      let finished = false;
      const finish = () => {
        if (finished) return;
        finished = true;
        media.removeEventListener("seeked", finish);
        if (wasPlaying && media.paused) media.play().catch(() => {});
        saveSnapshot(true);
      };

      media.addEventListener("seeked", finish, { once: true });
      try { media.currentTime = seconds; }
      catch (_) { finish(); }
      setTimeout(finish, 1000);
    };

    ["pointerdown", "touchstart", "mousedown"]
      .forEach(name => bar.addEventListener(name, begin, { capture: true, passive: name === "touchstart" }));

    bar.addEventListener("input", event => {
      event.stopImmediatePropagation();
      preview();
    }, true);

    bar.addEventListener("change", event => {
      event.stopImmediatePropagation();
      commit();
    }, true);

    ["pointerup", "touchend", "mouseup"]
      .forEach(name => bar.addEventListener(name, commit, true));
  }

  function handoffToBackground() {
    if (
      backgroundStarted ||
      window.__GW_QUALITY_OWNER === true ||
      reclaiming
    ) return false;

    const media = dspMedia();
    if (
      typeof currentTrack === "undefined" ||
      !currentTrack?.gwId ||
      !media ||
      media.paused
    ) return false;

    backgroundTransition = true;
    backgroundStarted = true;

    saveSnapshot(true, true);
    try { media.pause(); } catch (_) {}
    try {
      if (typeof audioCtx !== "undefined" && audioCtx?.state === "running") {
        audioCtx.suspend().catch(() => {});
      }
    } catch (_) {}

    /*
     * Service-ті JS іске қоспайды. MainActivity.onStop()
     * GwPlaybackState snapshot-ынан ACTION_START-ты бір рет
     * жібереді. Бұл duplicate ACTION_START / track reopen
     * race-ын жояды.
     */
    return true;
  }

  async function reclaimFromBackground() {
    if (reclaiming || window.__GW_QUALITY_OWNER === true) return;

    const state = parse(B.backgroundState(), {});
    if (
      !state.active ||
      state.engine === "GW_NATIVE" ||
      !state.trackId
    ) {
      backgroundStarted = false;
      backgroundTransition = false;
      return;
    }

    const track = (typeof localTracks !== "undefined" ? localTracks : [])
      .find(item => item.gwId === state.trackId);
    if (!track) return;

    try { queue = allTracks(); }
    catch (_) { return; }

    const index = queue.indexOf(track);
    if (index < 0) return;

    reclaiming = true;
    const prepared = prepareDsp(
      index,
      Number(state.positionMs || 0) / 1000
    );

    if (!prepared) {
      reclaiming = false;
      return;
    }

    const { media } = prepared;

    await new Promise(resolve => {
      if (media.readyState >= 2) resolve();
      else {
        const done = () => resolve();
        media.addEventListener("canplay", done, { once: true });
        setTimeout(done, 5000);
      }
    });

    let latest = parse(B.backgroundState(), state);
    try {
      if (
        Number(latest.positionMs || 0) > 0 &&
        Number.isFinite(media.duration)
      ) {
        media.currentTime = Math.min(
          Number(latest.positionMs) / 1000,
          Math.max(0, media.duration - .2)
        );
      }
    } catch (_) {}

    /* Service дыбысын тоқтатқаннан кейін ғана Web Audio-ны оятамыз. */
    try { B.qualityPause(); } catch (_) {}

    const pauseStarted = Date.now();
    while (Date.now() - pauseStarted < 500) {
      latest = parse(B.backgroundState(), latest);
      if (!latest.isPlaying) break;
      await new Promise(resolve => setTimeout(resolve, 40));
    }

    await activateDspGraph(media);

    let foregroundStarted = false;
    if (state.isPlaying === true) {
      try {
        await media.play();
        foregroundStarted = true;
      } catch (_) {}
    } else {
      foregroundStarted = true;
    }

    if (foregroundStarted) {
      try { B.claimForegroundPlayback(); } catch (_) {}
      saveSnapshot(true, state.isPlaying === true);
    }

    reclaiming = false;
    backgroundStarted = false;
    backgroundTransition = false;
  }

  window.GW_DSP_BACKGROUND = Object.freeze({
    handoff: handoffToBackground,
    reclaim: reclaimFromBackground
  });

  document.addEventListener("visibilitychange", () => {
    if (document.hidden) handoffToBackground();
    else setTimeout(() => { void reclaimFromBackground(); }, 30);
  });

  window.addEventListener("pagehide", handoffToBackground);
  window.addEventListener("gw:foreground-resume", () => {
    setTimeout(() => { void reclaimFromBackground(); }, 30);
  });

  bindMediaEvents(dspMedia());

  document.addEventListener("click", event => {
    const target = event.target?.closest?.(
      "#homeImport,#addMusicTab,[data-addmusic],#fileInput"
    );
    if (!target) return;
    event.preventDefault();
    event.stopPropagation();
    event.stopImmediatePropagation();
    try { B.pickTracks(); } catch (_) {}
  }, true);

  function routeLabel(type) {
    return ({
      BUILTIN_SPEAKER: "ДИНАМИК",
      BUILTIN_EARPIECE: "ТЕЛЕФОН",
      WIRED_HEADPHONES: "НАУШНИКИ",
      WIRED_HEADSET: "ГАРНИТУРА",
      USB_DEVICE: "USB DAC",
      USB_HEADSET: "USB",
      BLUETOOTH_A2DP: "BLUETOOTH",
      BLE_HEADSET: "BLUETOOTH",
      BLE_SPEAKER: "BLUETOOTH",
      HEARING_AID: "HEARING AID",
      HDMI: "HDMI",
      HDMI_ARC: "HDMI",
      HDMI_EARC: "HDMI",
      AUX_LINE: "AUX",
      LINE_ANALOG: "LINE",
      LINE_DIGITAL: "LINE"
    })[type] || "AUTO";
  }

  function refreshRoute(snapshot) {
    let current = snapshot;
    if (!current?.candidate) {
      try { current = parse(B.outputSnapshot(), {}); }
      catch (_) { current = {}; }
    }

    const candidate = current?.candidate || (current?.type ? current : null);
    spatialAllowed = candidate?.spatialCapable === true;
    window.__gwSpatialAllowed = spatialAllowed;

    document.querySelectorAll(
      "[data-scene='cinema'],[data-scene='reality']"
    ).forEach(node => {
      node.classList.toggle("gw-route-locked", !spatialAllowed);
    });

    const key = candidate
      ? [candidate.id, candidate.type, candidate.address].join("|")
      : "AUTO";
    if (key === lastRouteKey) return;
    lastRouteKey = key;

    const label = candidate ? routeLabel(candidate.type) : "AUTO";
    if ($("olType")) $("olType").textContent = label;
    if ($("olChip")) $("olChip").textContent = "GW READY";
    if ($("olLog")) $("olLog").innerHTML = `<div>Текущий выход: ${label}</div>`;
  }

  try {
    realityOutputAllowed = () => spatialAllowed;
    spOpen = () => spatialAllowed;
  } catch (_) {}

  window.addEventListener("gw:route-changed", event => {
    refreshRoute(event.detail);
  });

  document.addEventListener("click", event => {
    const button = event.target?.closest?.("button,[role='button'],.scene-card");
    if (!button || spatialAllowed) return;
    const text = (button.textContent || "").replace(/\s+/g, " ").toLowerCase();
    if (!/3d\s*reality|reality sound|кино|cinema/.test(text)) return;
    event.preventDefault();
    event.stopPropagation();
    event.stopImmediatePropagation();
    try { toast("Требуется внешний аудиовыход"); } catch (_) {}
  }, true);

  function restoreLocal() {
    const state = parse(localStorage.getItem(STATE_KEY), null);
    if (!state?.trackId) return;
    const track = (typeof localTracks !== "undefined" ? localTracks : [])
      .find(item => item.gwId === state.trackId);
    if (!track) return;
    try {
      queue = allTracks();
      const index = queue.indexOf(track);
      if (index >= 0) void playDsp(index, Number(state.positionMs || 0) / 1000, false);
    } catch (_) {}
  }

  try { applyLibrary(parse(B.library(), [])); } catch (_) {}
  installSingleSeekOwner();
  setTimeout(installSingleSeekOwner, 700);
  try { refreshRoute(parse(B.outputSnapshot(), {})); } catch (_) {}

  setTimeout(() => {
    const state = parse(B.backgroundState(), {});
    if (state.active && state.engine !== "GW_NATIVE") {
      void reclaimFromBackground();
    } else if (!state.active) {
      restoreLocal();
    }
  }, 250);

  try { B.jsLog("INFO", "GW_NATIVE_JS_V11 ready"); } catch (_) {}
})();
