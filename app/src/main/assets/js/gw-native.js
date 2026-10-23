(() => {
  "use strict";

  if (window.__GW_NATIVE_JS_V6__) return;
  window.__GW_NATIVE_JS_V6__ = true;

  const bridge = window.GwBridge;
  const $ = id => document.getElementById(id);

  const STATE_KEY = "gw-playback-state-v6";

  let stored = [];
  let libraryTimer = 0;
  let libraryPending = null;
  let currentSignature = "";
  let lastSnapshotAt = 0;
  let spatialAllowed = false;
  let lastRouteKey = "";

  const parse = (value, fallback) => {
    try {
      return JSON.parse(String(value));
    } catch (_) {
      return fallback;
    }
  };

  const extension = name => {
    const value = String(name || "")
      .split(".")
      .pop();

    return value
      ? value.toUpperCase()
      : "AUDIO";
  };

  const sourceFor = track =>
    "https://appassets.androidplatform.net/" +
    "tracks/" +
    encodeURIComponent(track.gwId) +
    "/" +
    encodeURIComponent(
      track.file?.name || "audio"
    );

  const touchStyle =
    document.createElement("style");

  touchStyle.id =
    "gw-touch-stability-v6";

  touchStyle.textContent = `
    html,body,button,a,label,input,[role="button"],
    .tab,.nav-item,.music-card,.track-row {
      -webkit-tap-highlight-color:transparent!important;
    }

    button:focus:not(:focus-visible),
    a:focus:not(:focus-visible),
    label:focus:not(:focus-visible),
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

  document.head.appendChild(
    touchStyle
  );

  function strictHiRes(raw) {
    return (
      raw &&
      raw.sourceVerified === true &&
      Number(raw.bitsPerSample || 0) > 16 &&
      Number(raw.sampleRate || 0) >= 44100
    );
  }

  function legacyTrack(raw) {
    const seconds =
      Number(raw.durationMs || 0) / 1000;

    const verified =
      raw.sourceVerified === true;

    const sampleRate =
      Number(raw.sampleRate || 0);

    const bits =
      Number(raw.bitsPerSample || 0);

    const channels =
      Number(raw.channels || 0);

    const format =
      extension(raw.name);

    return {
      gwId: raw.id,

      title:
        raw.title ||
        raw.name ||
        "Аудиотрек",

      artist:
        raw.artist ||
        "Неизвестный автор",

      album:
        raw.album ||
        "Без альбома",

      time:
        typeof fmt === "function"
          ? fmt(seconds)
          : "--:--",

      art:
