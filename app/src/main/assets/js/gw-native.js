(() => {
  "use strict";

  if (window.__GW_BG_MEDIA_V1__) return;
  window.__GW_BG_MEDIA_V1__ = true;

  const bridge = window.GwBridge;
  const $ = id => document.getElementById(id);
  const STATE_KEY = "gw-playback-state-v4";

  let stored = [];
  let libraryTimer = 0;
  let libraryPending = null;
  let currentSignature = "";
  let handoffActive = false;
  let suppressHandoffUntil = 0;
  let lastSnapshotAt = 0;
  let spatialAllowed = false;

  const parse = (value, fallback) => {
    try {
      return JSON.parse(String(value));
    } catch (_) {
      return fallback;
    }
  };

  const extension = name =>
    String(name || "AUDIO").split(".").pop().toUpperCase();

  const sourceFor = track =>
    "https://appassets.androidplatform.net/tracks/" +
    encodeURIComponent(track.gwId) + "/" +
    encodeURIComponent(track.file.name || "audio");

  /*
   * Touch-тағы кішкентай көк highlight жойылады.
   * Keyboard focus accessibility сақталады.
   */
  const style = document.createElement("style");
  style.id = "gw-touch-stability";

  style.textContent = `
    html,
    body,
    button,
    a,
    label,
    input,
    [role="button"],
    .tab,
    .nav-item,
    .music-card,
    .track-row {
      -webkit-tap-highlight-color: transparent !important;
    }

    button:focus:not(:focus-visible),
    a:focus:not(:focus-visible),
    label:focus:not(:focus-visible),
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

  function legacyTrack(raw) {
    const seconds = Number(raw.durationMs || 0) / 1000;

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
        fmt: extension(raw.name),
        sr: Number(raw.sampleRate || 0),
        bits: Number(raw.bitsPerSample || 0),
        ch: Number(raw.channels || 0),
        verified: raw.sourceVerified === true,
        hi: raw.hiRes === true
      }
    };
  }

  function librarySignature(list) {
    return list.map(item => [
      item.id,
      item.title,
      item.artist,
      item.album,
      item.durationMs,
      item.coverUrl,
      item.hiRes
    ].join("|")).join(";");
  }

  /*
   * Metadata әр әннен кейін келгенде Home DOM-ды
   * қайта-қайта қиратпайды. 450 мс batch render.
   */
  function applyLibrary(value) {
    const list = Array.isArray(value) ? value : [];
    const signature = librarySignature(list);
    if (signature === currentSignature) return;

    currentSignature = signature;
    stored = list;

    const activeId = currentTrack && currentTrack.gwId;

    localTracks = stored.map(legacyTrack);
    queue = allTracks();

    if (activeId) {
      const fresh = localTracks.find(
        item => item.gwId === activeId
      );
      if (fresh) {
        currentTrack = fresh;
        currentIndex = queue.indexOf(fresh);
        try { setTrackMeta(fresh); } catch (_) {}
      }
    }

    renderAllTracks();
  }

  function replaceLibrary(value) {
    libraryPending = Array.isArray(value) ? value : [];
    clearTimeout(libraryTimer);
    libraryTimer = setTimeout(() => {
      const next = libraryPending;
      libraryPending = null;
      applyLibrary(next);
    }, 450);
  }

  window.GW_LIBRARY = Object.freeze({
    replace: replaceLibrary,
    tracks: () => stored.slice()
  });

  window.addEventListener("gw:library-changed", event => {
    replaceLibrary(event.detail);
  });

  function saveSnapshot(force) {
    if (!currentTrack || !currentTrack.gwId || !audio) return;

    const now = Date.now();
    if (!force && now - lastSnapshotAt < 1500) return;
    lastSnapshotAt = now;

    const positionMs = Math.max(
      0,
      Math.round((audio.currentTime || 0) * 1000)
    );

    try {
      bridge.updatePlaybackSnapshot(
        currentTrack.gwId,
        positionMs,
        !audio.paused && !audio.ended
      );
    } catch (_) {}

    try {
      localStorage.setItem(STATE_KEY, JSON.stringify({
        trackId: currentTrack.gwId,
        positionMs,
        paused: audio.paused
      }));
    } catch (_) {}
  }

  function loadTrack(index, positionSeconds, autoplay) {
    if (!queue.length) queue = allTracks();
    if (index < 0 || index >= queue.length) return;

    const track = queue[index];
    const same =
      currentTrack &&
      currentTrack.gwId === track.gwId &&
      audio &&
      audio.dataset &&
      audio.dataset.gwTrackId === track.gwId;

    currentIndex = index;
    currentTrack = track;
    try { setTrackMeta(track); } catch (_) {}

    audio = webEl;

    if (!same) {
      audio.pause();
      audio.dataset.gwTrackId = track.gwId;
      audio.src = sourceFor(track);
      audio.preload = "auto";
      audio.load();
    }

    const wanted = Number(positionSeconds || 0);
    const seek = () => {
      if (wanted > 0 && Number.isFinite(audio.duration)) {
        audio.currentTime = Math.min(
          wanted,
          Math.max(0, audio.duration - .25)
        );
      }
    };

    if (audio.readyState >= 1) seek();
    else audio.addEventListener("loadedmetadata", seek, { once: true });

    try { ensureAudioGraph(); } catch (_) {}

    if (
      typeof audioCtx !== "undefined" &&
      audioCtx &&
      audioCtx.state === "suspended"
    ) {
      audioCtx.resume().catch(() => {});
    }

    if (autoplay) {
      try { bridge.prepareBackgroundPlayback(); } catch (_) {}
      audio.play().catch(() => {
        toast("Не удалось запустить трек.");
      });
    }

    renderAllTracks();
    updatePlayButtons();
    saveSnapshot(true);
  }

  try {
    playTrack = index => loadTrack(index, 0, true);
  } catch (_) {}

  function handoff() {
    if (
      handoffActive ||
      Date.now() < suppressHandoffUntil ||
      !currentTrack ||
      !currentTrack.gwId ||
      !audio ||
      audio.paused
    ) return;

    saveSnapshot(true);

    const positionMs = Math.max(
      0,
      Math.round((audio.currentTime || 0) * 1000)
    );

    audio.pause();

    let started = false;
    try {
      started = bridge.startBackgroundPlayback(
        currentTrack.gwId,
        positionMs
      );
    } catch (_) {}

    if (started) handoffActive = true;
    else audio.play().catch(() => {});
  }

  function reclaim() {
    let state = { active: false };
    try {
      state = parse(
        bridge.claimForegroundPlayback(),
        { active: false }
      );
    } catch (_) {}

    if (!state.active || !state.trackId) {
      handoffActive = false;
      return;
    }

    if (!localTracks.length) {
      applyLibrary(parse(bridge.library(), []));
    }

    queue = allTracks();

    const track = localTracks.find(
      item => item.gwId === state.trackId
    );
    if (!track) {
      handoffActive = false;
      return;
    }

    const index = queue.indexOf(track);
    loadTrack(
      index,
      Number(state.positionMs || 0) / 1000,
      state.isPlaying === true
    );
    handoffActive = false;
  }

  window.GW_BACKGROUND = Object.freeze({ handoff, reclaim });

  document.addEventListener("visibilitychange", () => {
    if (document.hidden) handoff();
    else setTimeout(reclaim, 120);
  });

  window.addEventListener("pagehide", handoff);

  const media = $("audio");

  ["timeupdate", "play", "pause", "seeking", "ended"].forEach(name => {
    media?.addEventListener(name, () => {
      saveSnapshot(name !== "timeupdate");
    });
  });

  document.addEventListener("click", event => {
    const target = event.target?.closest?.(
      "#homeImport,#addMusicTab,[data-addmusic],#fileInput"
    );
    if (!target) return;

    event.preventDefault();
    event.stopPropagation();
    event.stopImmediatePropagation();

    suppressHandoffUntil = Date.now() + 30_000;
    try { bridge.pickTracks(); } catch (_) {}
  }, true);

  /*
   * Келесі кезең бірге қосылды:
   * Cinema/Reality тек сыртқы output барда ашылады.
   */
  function refreshRoute(snapshot) {
    const current = snapshot && snapshot.devices
      ? snapshot
      : parse(bridge.outputSnapshot(), {
          devices: [],
          candidate: null
        });

    spatialAllowed =
      current.candidate &&
      current.candidate.spatialCapable === true;

    window.__gwSpatialAllowed = spatialAllowed;

    document
      .querySelectorAll(
        "[data-scene='cinema'],[data-scene='reality']"
      )
      .forEach(node => {
        node.classList.toggle("gw-route-locked", !spatialAllowed);
      });
  }

  try { realityOutputAllowed = () => spatialAllowed; } catch (_) {}
  try { spOpen = () => spatialAllowed; } catch (_) {}

  window.addEventListener("gw:route-changed", event => {
    refreshRoute(event.detail);
  });

  document.addEventListener("click", event => {
    const button = event.target?.closest?.(
      "button,[role='button'],.scene-card"
    );
    if (!button || spatialAllowed) return;

    const text = (button.textContent || "")
      .replace(/\s+/g, " ")
      .toLowerCase();

    if (!/3d\s*reality|reality sound|кино|cinema/.test(text)) return;

    event.preventDefault();
    event.stopPropagation();
    event.stopImmediatePropagation();

    toast("Требуется внешний аудиовыход");
  }, true);

  replaceLibrary(parse(bridge.library(), []));
  refreshRoute(parse(bridge.outputSnapshot(), {
    devices: [],
    candidate: null
  }));
  setTimeout(reclaim, 250);
})();
