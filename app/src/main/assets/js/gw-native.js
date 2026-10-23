(() => {
  "use strict";

  if (window.__GW_MEDIA_FIX_V1__) return;
  window.__GW_MEDIA_FIX_V1__ = true;

  const bridge = window.GwBridge;
  const $ = id => document.getElementById(id);
  const STATE_KEY = "gw-playback-state-v3";

  let stored = [];
  let restorePending = true;
  let lastSaved = 0;
  let lastRouteKey = "";

  const parse = (value, fallback) => {
    try { return JSON.parse(String(value)); }
    catch (_) { return fallback; }
  };

  const extension = name => {
    const value = String(name || "").split(".").pop();
    return value ? value.toUpperCase() : "AUDIO";
  };

  const sourceFor = track =>
    "https://appassets.androidplatform.net/" +
    "tracks/" +
    encodeURIComponent(track.gwId) + "/" +
    encodeURIComponent(track.file.name || "audio");

  function strictHiRes(raw) {
    return raw.sourceVerified === true &&
      Number(raw.bitsPerSample || 0) > 16 &&
      Number(raw.sampleRate || 0) >= 44100;
  }

  function legacyTrack(raw) {
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
        hi: strictHiRes(raw),
        label: verified
          ? `${format} · ${(sampleRate / 1000).toFixed(1)} кГц · ${bits} бит`
          : format
      }
    };
  }

  function truthfulBadge(track) {
    const info = track && track.info;
    if (!info) return "";

    const flac = info.fmt === "FLAC"
      ? ' <span class="flac-tag">FLAC</span>'
      : "";

    const hi = info.verified === true &&
      Number(info.bits || 0) > 16 &&
      Number(info.sr || 0) >= 44100;

    return flac + (hi
      ? ' <span class="hr3d" title="Hi-Res Audio">' +
        '<i>Hi-Res</i><b>AUDIO</b></span>'
      : "");
  }

  try { hrBadge = truthfulBadge; } catch (_) {}

  try {
    engineLabel = track => {
      const info = track && track.info;
      if (!info) return "GW READY";
      return info.verified
        ? `${info.fmt} · ${(Number(info.sr) / 1000).toFixed(1)} кГц · ${info.bits} бит`
        : info.fmt || "GW READY";
    };
  } catch (_) {}

  function replaceLibrary(value) {
    stored = Array.isArray(value) ? value : [];
    const activeId = currentTrack && currentTrack.gwId;

    localTracks = stored.map(legacyTrack);
    queue = allTracks();

    if (activeId) {
      const updated = localTracks.find(
        track => track.gwId === activeId
      );
      if (updated) {
        currentTrack = updated;
        currentIndex = queue.indexOf(updated);
        try { setTrackMeta(updated); } catch (_) {}
      }
    }

    renderAllTracks();
    if (restorePending) restoreSelection();
  }

  window.GW_LIBRARY = Object.freeze({
    replace: replaceLibrary,
    tracks: () => stored.slice()
  });

  window.addEventListener("gw:library-changed", event => {
    replaceLibrary(event.detail);
  });

  function saveState(force) {
    if (!currentTrack || !currentTrack.gwId || !audio) return;
    const now = Date.now();
    if (!force && now - lastSaved < 1500) return;
    lastSaved = now;

    try {
      localStorage.setItem(STATE_KEY, JSON.stringify({
        trackId: currentTrack.gwId,
        position: Number.isFinite(audio.currentTime)
          ? audio.currentTime
          : 0,
        paused: audio.paused,
        savedAt: now
      }));
    } catch (_) {}
  }

  function restoreSelection() {
    restorePending = false;
    const state = parse(localStorage.getItem(STATE_KEY), null);
    if (!state || !state.trackId) return;

    const track = localTracks.find(
      item => item.gwId === state.trackId
    );
    if (!track) return;

    queue = allTracks();
    currentTrack = track;
    currentIndex = queue.indexOf(track);
    try { setTrackMeta(track); } catch (_) {}

    audio = webEl;
    audio.pause();
    audio.dataset.gwTrackId = track.gwId;
    audio.src = sourceFor(track);
    audio.preload = "metadata";
    audio.load();

    audio.addEventListener("loadedmetadata", function restorePosition() {
      audio.removeEventListener("loadedmetadata", restorePosition);
      const wanted = Number(state.position || 0);
      if (Number.isFinite(wanted) && wanted > 0 &&
          Number.isFinite(audio.duration)) {
        audio.currentTime = Math.min(
          wanted,
          Math.max(0, audio.duration - 0.25)
        );
      }
      updatePlayButtons();
    }, { once: true });
  }

  function playPersistent(index, position = 0) {
    if (!queue.length) queue = allTracks();
    if (index < 0 || index >= queue.length) return;

    const track = queue[index];
    const same = currentTrack &&
      currentTrack.gwId === track.gwId &&
      audio &&
      audio.dataset.gwTrackId === track.gwId;

    currentIndex = index;
    currentTrack = track;
    try { setTrackMeta(track); } catch (_) {}

    if (!same) {
      audio = webEl;
      audio.pause();
      audio.dataset.gwTrackId = track.gwId;
      audio.src = sourceFor(track);
      audio.preload = "auto";
      audio.load();
    }

    const wanted = Number(position || 0);
    if (wanted > 0) {
      const apply = () => {
        if (Number.isFinite(audio.duration)) {
          audio.currentTime = Math.min(
            wanted,
            Math.max(0, audio.duration - 0.25)
          );
        }
      };
      if (audio.readyState >= 1) apply();
      else audio.addEventListener(
        "loadedmetadata", apply, { once: true }
      );
    }

    try { ensureAudioGraph(); } catch (_) {}
    if (typeof audioCtx !== "undefined" && audioCtx &&
        audioCtx.state === "suspended") {
      audioCtx.resume().catch(() => {});
    }

    audio.play().catch(() => {
      toast("Не удалось запустить трек.");
    });

    renderAllTracks();
    updatePlayButtons();
    saveState(true);
  }

  try {
    playTrack = index => playPersistent(index, 0);
  } catch (_) {}

  const media = document.getElementById("audio");

  ["timeupdate", "play", "pause", "ended", "seeking"].forEach(name => {
    media?.addEventListener(name, () => {
      saveState(name !== "timeupdate");
    });
  });

  ["error", "stalled", "waiting", "abort"].forEach(name => {
    media?.addEventListener(name, () => {
      try {
        bridge.jsLog(
          "WARN",
          `PLAYBACK ${name} ` +
          `code=${media.error?.code || 0} ` +
          `time=${media.currentTime || 0}`
        );
      } catch (_) {}
      saveState(true);
    });
  });

  window.addEventListener("pagehide", () => saveState(true));
  document.addEventListener("visibilitychange", () => {
    if (document.hidden) saveState(true);
  });

  document.addEventListener("click", event => {
    const target = event.target?.closest?.(
      "#homeImport,#addMusicTab,[data-addmusic],#fileInput"
    );
    if (!target) return;
    event.preventDefault();
    event.stopPropagation();
    event.stopImmediatePropagation();
    try { bridge.pickTracks(); } catch (_) {}
  }, true);

  function nativeVerified() {
    const status = parse(bridge.status(), {});
    return status.ok === true &&
      status.streamVerified === true &&
      status.state === "STARTED" &&
      status.actualFormat &&
      status.actualFormat !== "NONE" &&
      Number(status.actualRate || 0) > 0;
  }

  try { setEngine(false, true); } catch (_) {}
  try { hiRes = false; } catch (_) {}
  try { localStorage.setItem("neonwave-hires", "0"); } catch (_) {}
  try { bridge.closeStream(); } catch (_) {}

  const hiToggle = $("hiResToggle");
  if (hiToggle) {
    hiToggle.checked = false;
    hiToggle.disabled = !nativeVerified();
    hiToggle.title = hiToggle.disabled ? "GW READY" : "GW NATIVE";
  }

  document.addEventListener("change", event => {
    if (event.target?.id !== "hiResToggle") return;
    if (!nativeVerified()) {
      event.preventDefault();
      event.stopPropagation();
      event.stopImmediatePropagation();
      event.target.checked = false;
      try { hiRes = false; } catch (_) {}
      toast("GW READY");
    }
  }, true);

  document.addEventListener("click", event => {
    if (!event.target?.closest?.("#hrOpen")) return;
    if (!nativeVerified()) {
      event.preventDefault();
      event.stopPropagation();
      event.stopImmediatePropagation();
      toast("GW READY");
    }
  }, true);

  const forbidden =
    /PCM WAV|Hi-Res движок|прямой вывод|Web Audio|HTML MEDIA|NOT CONNECTED/i;

  function sanitize(node) {
    if (node && forbidden.test(node.textContent || "")) {
      node.textContent = "GW READY";
    }
  }

  ["audioStatus", "olChip", "olDetail", "olLog", "toast"].forEach(id => {
    const node = $(id);
    if (!node) return;
    sanitize(node);
    new MutationObserver(() => sanitize(node)).observe(node, {
      childList: true,
      subtree: true,
      characterData: true
    });
  });

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
      HDMI: "HDMI",
      AUX_LINE: "AUX"
    })[type] || "AUTO";
  }

  function renderRoute(route, force) {
    route = route || parse(
      bridge.currentOutputDevice(),
      { ok: false }
    );
    const key = route.ok
      ? [route.id, route.type, route.address].join("|")
      : "AUTO";
    if (!force && key === lastRouteKey) return;
    lastRouteKey = key;

    const label = route.ok ? routeLabel(route.type) : "AUTO";

    if ($("olType")) $("olType").textContent = label;
    if ($("olChip")) $("olChip").textContent = "GW READY";
    if ($("olRate")) $("olRate").textContent = "—";
    if ($("olBits")) $("olBits").textContent = "—";
    if ($("olDetail")) {
      $("olDetail").innerHTML =
        `<div>Выход: <b>${label}</b></div>` +
        `<div>Режим: <b>READY</b></div>`;
    }
    if ($("olLog")) {
      $("olLog").innerHTML =
        `<div>Текущий выход: ${label}</div>`;
    }
  }

  window.addEventListener("gw:route-changed", event => {
    renderRoute(event.detail, false);
  });

  document.addEventListener("click", event => {
    if (!event.target?.closest?.("#olRefresh")) return;
    event.preventDefault();
    event.stopImmediatePropagation();
    renderRoute(
      parse(bridge.currentOutputDevice(), { ok: false }),
      true
    );
  }, true);

  const smart = $("seSw");
  function protectSmartEq() {
    if (media) media.volume = smart?.checked ? 0.78 : 0.96;
  }
  smart?.addEventListener("change", protectSmartEq);
  media?.addEventListener("play", protectSmartEq);

  replaceLibrary(parse(bridge.library(), []));
  renderRoute(
    parse(bridge.currentOutputDevice(), { ok: false }),
    true
  );
})();
