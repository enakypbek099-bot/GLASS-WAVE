(() => {
  "use strict";

  if (window.__GW_NATIVE_JS_V9__) return;
  window.__GW_NATIVE_JS_V9__ = true;

  const B = window.GwBridge;
  const $ = id => document.getElementById(id);
  const STATE_KEY = "gw-playback-state-v9";

  let stored = [];
  let pendingLibrary = null;
  let libraryTimer = 0;
  let signature = "";
  let lastSave = 0;
  let reclaiming = false;
  let spatialAllowed = false;
  let lastRouteKey = "";

  const parse = (value, fallback) => {
    try {
      return JSON.parse(String(value));
    } catch (_) {
      return fallback;
    }
  };

  const extension = name =>
    String(name || "AUDIO")
      .split(".")
      .pop()
      .toUpperCase();

  const sourceFor = track =>
    "https://appassets.androidplatform.net/tracks/" +
    encodeURIComponent(track.gwId) +
    "/" +
    encodeURIComponent(track.file?.name || "audio");

  const style = document.createElement("style");
  style.id = "gw-android-ui-v9";
  style.textContent = `
    html,body,button,a,label,input,[role="button"],
    .tab,.nav-item,.music-card,.track-row {
      -webkit-tap-highlight-color:transparent!important;
    }

    button:focus:not(:focus-visible),
    a:focus:not(:focus-visible),
    input:focus:not(:focus-visible),
    [role="button"]:focus:not(:focus-visible) {
      outline:none!important;
      box-shadow:none!important;
    }

    button:focus-visible,
    a:focus-visible,
    input:focus-visible,
    [role="button"]:focus-visible {
      outline:2px solid rgba(244,234,208,.88)!important;
      outline-offset:3px!important;
    }

    .gw-route-locked {
      opacity:.46!important;
      filter:saturate(.35)!important;
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

        hi:
          verified &&
          bits > 16 &&
          sampleRate >= 44100,

        label:
          verified
            ? `${format} · ${(sampleRate / 1000).toFixed(1)} кГц · ${bits} бит`
            : format
      }
    };
  }

  function truthfulBadge(track) {
    const info = track?.info;
    if (!info) return "";

    const flac =
      info.fmt === "FLAC"
        ? ' <span class="flac-tag">FLAC</span>'
        : "";

    const hi =
      info.verified === true &&
      Number(info.bits || 0) > 16 &&
      Number(info.sr || 0) >= 44100;

    return flac +
      (
        hi
          ? ' <span class="hr3d" title="Hi-Res source">' +
            "<i>Hi-Res</i><b>SOURCE</b></span>"
          : ""
      );
  }

  try {
    hrBadge = truthfulBadge;
    engineLabel = track =>
      track?.info?.label || "GLASS WAVE";
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

    const activeId =
      typeof currentTrack !== "undefined" &&
      currentTrack
        ? currentTrack.gwId
        : "";

    try {
      localTracks = list.map(toTrack);
      queue = allTracks();
    } catch (_) {
      return;
    }

    if (activeId) {
      const fresh = localTracks.find(
        track => track.gwId === activeId
      );

      if (fresh) {
        currentTrack = fresh;
        currentIndex = queue.indexOf(fresh);

        try {
          setTrackMeta(fresh);
        } catch (_) {}
      }
    }

    try {
      renderAllTracks();
    } catch (_) {}
  }

  function replaceLibrary(list) {
    pendingLibrary = Array.isArray(list) ? list : [];

    clearTimeout(libraryTimer);

    libraryTimer = setTimeout(() => {
      const next = pendingLibrary;
      pendingLibrary = null;
      applyLibrary(next);
    }, 350);
  }

  window.GW_LIBRARY = Object.freeze({
    replace: replaceLibrary,
    tracks: () => stored.slice()
  });

  window.addEventListener(
    "gw:library-changed",
    event => replaceLibrary(event.detail)
  );

  function saveSnapshot(force) {
    if (window.__GW_QUALITY_OWNER === true) return;

    if (
      typeof currentTrack === "undefined" ||
      !currentTrack?.gwId ||
      typeof audio === "undefined" ||
      !audio
    ) {
      return;
    }

    const now = Date.now();

    if (!force && now - lastSave < 1500) return;
    lastSave = now;

    const positionMs = Math.max(
      0,
      Math.round((audio.currentTime || 0) * 1000)
    );

    try {
      B.updatePlaybackSnapshot(
        currentTrack.gwId,
        positionMs,
        !audio.paused && !audio.ended
      );
    } catch (_) {}

    try {
      localStorage.setItem(
        STATE_KEY,
        JSON.stringify({
          trackId: currentTrack.gwId,
          positionMs,
          paused: audio.paused
        })
      );
    } catch (_) {}
  }

  function playDsp(index, positionSeconds = 0, autoPlay = true) {
    try {
      if (!queue.length) queue = allTracks();
    } catch (_) {
      return;
    }

    if (index < 0 || index >= queue.length) return;

    const track = queue[index];

    currentIndex = index;
    currentTrack = track;

    try {
      setTrackMeta(track);
    } catch (_) {}

    /*
     * DSP режимінде owner — HTML-дің түпнұсқа media элементі (webEl).
     * Бұл createMediaElementSource() + EQ/Bass/Tone/3D/8D/Lab
     * тізбегін сақтайды.
     */
    try {
      audio = webEl;
    } catch (_) {
      return;
    }

    if (!audio) return;

    const same =
      audio.dataset?.gwTrackId === track.gwId &&
      audio.getAttribute("src");

    if (!same) {
      try {
        audio.pause();
      } catch (_) {}

      audio.dataset.gwTrackId = track.gwId;
      audio.src = sourceFor(track);
      audio.preload = "auto";
      audio.load();
    }

    const seek = () => {
      if (
        positionSeconds > 0 &&
        Number.isFinite(audio.duration)
      ) {
        try {
          audio.currentTime = Math.min(
            positionSeconds,
            Math.max(0, audio.duration - 0.25)
          );
        } catch (_) {}
      }
    };

    if (audio.readyState >= 1) {
      seek();
    } else {
      audio.addEventListener(
        "loadedmetadata",
        seek,
        { once: true }
      );
    }

    /*
     * HTML-дің түпнұсқа Web Audio graph-ын қайта қосамыз.
     * Жаңа AudioContext немесе MediaElementSource жасалмайды.
     */
    try {
      ensureAudioGraph();
    } catch (error) {
      try {
        B.jsError("ensureAudioGraph: " + error);
      } catch (_) {}
    }

    try {
      if (
        typeof audioCtx !== "undefined" &&
        audioCtx &&
        audioCtx.state === "suspended"
      ) {
        audioCtx.resume().catch(() => {});
      }
    } catch (_) {}

    if (autoPlay) {
      try {
        B.prepareBackgroundPlayback();
      } catch (_) {}

      audio.play().catch(() => {
        try {
          toast("Не удалось запустить трек.");
        } catch (_) {}
      });
    } else {
      try {
        audio.pause();
      } catch (_) {}
    }

    try {
      renderAllTracks();
      updatePlayButtons();
    } catch (_) {}

    saveSnapshot(true);
  }

  try {
    playTrack = index => playDsp(index, 0, true);
  } catch (_) {}

  window.GW_DSP = Object.freeze({
    play: playDsp,

    current: () => ({
      trackId:
        typeof currentTrack !== "undefined"
          ? currentTrack?.gwId || ""
          : "",

      positionMs:
        typeof audio !== "undefined" && audio
          ? Math.round((audio.currentTime || 0) * 1000)
          : 0,

      isPlaying:
        typeof audio !== "undefined" &&
        audio &&
        !audio.paused &&
        !audio.ended
    })
  });

  function installStableSeek() {
    const bar = $("seekBar");
    const media = $("audio");

    if (!bar || !media || bar.dataset.gwSeekV9) return;
    bar.dataset.gwSeekV9 = "1";

    let requested = null;
    let wasPlaying = false;
    let committed = false;

    const begin = () => {
      if (window.__GW_QUALITY_OWNER === true) return;
      wasPlaying = !media.paused && !media.ended;
      requested = null;
      committed = false;
    };

    const preview = event => {
      if (
        window.__GW_QUALITY_OWNER === true ||
        event.target !== bar
      ) {
        return;
      }

      event.stopPropagation();
      event.stopImmediatePropagation();

      if (
        !Number.isFinite(media.duration) ||
        media.duration <= 0
      ) {
        return;
      }

      requested =
        media.duration *
        Number(bar.value || 0) /
        100;

      const elapsed = $("elapsed");

      if (elapsed && typeof fmt === "function") {
        elapsed.textContent = fmt(requested);
      }
    };

    const commit = () => {
      if (
        window.__GW_QUALITY_OWNER === true ||
        committed ||
        requested === null ||
        !Number.isFinite(media.duration)
      ) {
        return;
      }

      committed = true;

      const target = Math.max(
        0,
        Math.min(
          requested,
          Math.max(0, media.duration - 0.05)
        )
      );

      requested = null;

      let finished = false;

      const finish = () => {
        if (finished) return;
        finished = true;

        media.removeEventListener("seeked", finish);

        if (wasPlaying && media.paused) {
          media.play().catch(() => {});
        }

        saveSnapshot(true);
      };

      media.addEventListener(
        "seeked",
        finish,
        { once: true }
      );

      try {
        media.currentTime = target;
      } catch (_) {
        finish();
      }

      setTimeout(finish, 1200);
    };

    bar.addEventListener("pointerdown", begin, true);
    bar.addEventListener(
      "touchstart",
      begin,
      { capture: true, passive: true }
    );

    window.addEventListener("input", preview, true);

    window.addEventListener(
      "change",
      event => {
        if (event.target !== bar) return;

        event.stopPropagation();
        event.stopImmediatePropagation();

        commit();
      },
      true
    );

    ["pointerup", "touchend", "mouseup"].forEach(name => {
      window.addEventListener(
        name,
        event => {
          if (event.target !== bar) return;

          setTimeout(commit, 0);
        },
        true
      );
    });
  }

  function reclaimDspBackground() {
    if (
      reclaiming ||
      window.__GW_QUALITY_OWNER === true
    ) {
      return;
    }

    const state = parse(
      B.backgroundState(),
      {}
    );

    if (
      !state.active ||
      state.engine === "GW_NATIVE" ||
      !state.trackId
    ) {
      return;
    }

    const track = (
      typeof localTracks !== "undefined"
        ? localTracks
        : []
    ).find(item => item.gwId === state.trackId);

    if (!track) return;

    try {
      queue = allTracks();
    } catch (_) {
      return;
    }

    const index = queue.indexOf(track);
    if (index < 0) return;

    reclaiming = true;

    const wasPlaying = state.isPlaying === true;

    /*
     * Алдымен фондағы MediaPlayer-ды pause етеміз.
     * Содан кейін WebView-ды service позициясында дайындаймыз.
     */
    try {
      B.qualityPause();
    } catch (_) {}

    const started = Date.now();

    const waitPaused = () => {
      const currentState = parse(
        B.backgroundState(),
        state
      );

      if (
        currentState.isPlaying &&
        Date.now() - started < 1200
      ) {
        setTimeout(waitPaused, 80);
        return;
      }

      playDsp(
        index,
        Number(
          currentState.positionMs ||
          state.positionMs ||
          0
        ) / 1000,
        false
      );

      const media = $("audio");

      const finish = () => {
        const stopService = () => {
          try {
            B.claimForegroundPlayback();
          } catch (_) {}

          reclaiming = false;
        };

        if (wasPlaying) {
          media.play()
            .then(stopService)
            .catch(() => {
              reclaiming = false;
            });
        } else {
          stopService();
        }
      };

      if (media?.readyState >= 2) {
        finish();
      } else {
        media?.addEventListener(
          "canplay",
          finish,
          { once: true }
        );
      }
    };

    waitPaused();
  }

  window.addEventListener(
    "gw:foreground-resume",
    reclaimDspBackground
  );

  document.addEventListener(
    "visibilitychange",
    () => {
      if (!document.hidden) {
        setTimeout(reclaimDspBackground, 180);
      }
    }
  );

  const media = $("audio");

  [
    "timeupdate",
    "play",
    "pause",
    "seeking",
    "seeked",
    "ended"
  ].forEach(name => {
    media?.addEventListener(
      name,
      () => saveSnapshot(name !== "timeupdate")
    );
  });

  document.addEventListener(
    "click",
    event => {
      const target = event.target?.closest?.(
        "#homeImport,#addMusicTab,[data-addmusic],#fileInput"
      );

      if (!target) return;

      event.preventDefault();
      event.stopPropagation();
      event.stopImmediatePropagation();

      try {
        B.pickTracks();
      } catch (_) {}
    },
    true
  );

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
      try {
        current = parse(B.outputSnapshot(), {});
      } catch (_) {
        current = {};
      }
    }

    const candidate =
      current?.candidate ||
      (current?.type ? current : null);

    spatialAllowed =
      candidate?.spatialCapable === true;

    window.__gwSpatialAllowed = spatialAllowed;

    /*
     * Route lock тек Cinema мен Reality-ге қатысты.
     * Қалған EQ/3D/8D/Lab басқару элементтері
     * тимейді.
     */
    document.querySelectorAll(
      "[data-scene='cinema']," +
      "[data-scene='reality']"
    ).forEach(node => {
      node.classList.toggle(
        "gw-route-locked",
        !spatialAllowed
      );
    });

    const key = candidate
      ? [
          candidate.id,
          candidate.type,
          candidate.address
        ].join("|")
      : "AUTO";

    if (key === lastRouteKey) return;
    lastRouteKey = key;

    const label = candidate
      ? routeLabel(candidate.type)
      : "AUTO";

    if ($("olType")) {
      $("olType").textContent = label;
    }

    if ($("olChip")) {
      $("olChip").textContent = "GW READY";
    }

    if ($("olLog")) {
      $("olLog").innerHTML =
        `<div>Текущий выход: ${label}</div>`;
    }
  }

  try {
    realityOutputAllowed = () => spatialAllowed;
    spOpen = () => spatialAllowed;
  } catch (_) {}

  window.addEventListener(
    "gw:route-changed",
    event => refreshRoute(event.detail)
  );

  document.addEventListener(
    "click",
    event => {
      const button = event.target?.closest?.(
        "button,[role='button'],.scene-card"
      );

      if (!button || spatialAllowed) return;

      const text = (button.textContent || "")
        .replace(/\s+/g, " ")
        .toLowerCase();

      if (
        !/3d\s*reality|reality sound|кино|cinema/
          .test(text)
      ) {
        return;
      }

      event.preventDefault();
      event.stopPropagation();
      event.stopImmediatePropagation();

      try {
        toast("Требуется внешний аудиовыход");
      } catch (_) {}
    },
    true
  );

  function restoreLocal() {
    const state = parse(
      localStorage.getItem(STATE_KEY),
      null
    );

    if (!state?.trackId) return;

    const track = (
      typeof localTracks !== "undefined"
        ? localTracks
        : []
    ).find(item => item.gwId === state.trackId);

    if (!track) return;

    try {
      queue = allTracks();
      const index = queue.indexOf(track);

      if (index >= 0) {
        playDsp(
          index,
          Number(state.positionMs || 0) / 1000,
          false
        );
      }
    } catch (_) {}
  }

  try {
    applyLibrary(
      parse(B.library(), [])
    );
  } catch (_) {}

  installStableSeek();

  setTimeout(
    installStableSeek,
    800
  );

  try {
    refreshRoute(
      parse(B.outputSnapshot(), {})
    );
  } catch (_) {}

  setTimeout(() => {
    const state = parse(
      B.backgroundState(),
      {}
    );

    if (
      state.active &&
      state.engine !== "GW_NATIVE"
    ) {
      reclaimDspBackground();
    } else if (!state.active) {
      restoreLocal();
    }
  }, 300);

  try {
    B.jsLog(
      "INFO",
      "GW_NATIVE_JS_V9 ready"
    );
  } catch (_) {}
})();
