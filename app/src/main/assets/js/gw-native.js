(() => {
  "use strict";

  if (window.__GW_NATIVE_V2__) return;

  window.__GW_NATIVE_V2__ = true;
  window.GW_NATIVE_ACTIVE = true;

  const bridge = window.GwBridge || null;
  const $ = id => document.getElementById(id);

  const safeJson = value => {
    try {
      return JSON.parse(String(value));
    } catch (_) {
      return {
        ok: false,
        error: "INVALID_NATIVE_JSON",
        raw: String(value)
      };
    }
  };

  const call = (name, ...args) => {
    try {
      if (!bridge || typeof bridge[name] !== "function") {
        return {
          ok: false,
          error: "GW_BRIDGE_UNAVAILABLE",
          operation: name
        };
      }
      return safeJson(bridge[name](...args));
    } catch (error) {
      return {
        ok: false,
        error: "GW_BRIDGE_CALL_FAILED",
        operation: name,
        details: String(error)
      };
    }
  };

  const log = message => {
    try {
      if (bridge && typeof bridge.jsLog === "function") {
        bridge.jsLog("INFO", String(message));
      }
    } catch (_) {}
  };

  let requestedMode =
    localStorage.getItem("gw-native-mode") === "QUALITY"
      ? "QUALITY"
      : "DSP";

  let lastStatus = null;
  let rendering = false;

  function disableLegacyAudioPath() {
    try {
      ensureAudioGraph = () => false;
    } catch (_) {}

    try {
      if (
        typeof hiRes !== "undefined" &&
        hiRes &&
        typeof setEngine === "function"
      ) {
        setEngine(false, true);
      }
    } catch (error) {
      log("setEngine(false) failed: " + error);
    }

    try {
      if (typeof hiRes !== "undefined") {
        hiRes = false;
      }
    } catch (_) {}

    try {
      localStorage.setItem("neonwave-hires", "0");
    } catch (_) {}

    try {
      if (typeof sourceNode !== "undefined" && sourceNode) {
        sourceNode.disconnect();
      }
    } catch (_) {}

    try {
      if (typeof audioCtx !== "undefined" && audioCtx) {
        audioCtx.close();
      }
    } catch (_) {}

    try {
      if (typeof audioCtx !== "undefined") audioCtx = null;
      if (typeof sourceNode !== "undefined") sourceNode = null;
      if (typeof analyserNode !== "undefined") analyserNode = null;
      if (typeof compressorNode !== "undefined") compressorNode = null;
      if (typeof frequencyData !== "undefined") frequencyData = null;
      if (typeof timeData !== "undefined") timeData = null;
      if (typeof graphFailed !== "undefined") graphFailed = true;
    } catch (_) {}

    for (const name of ["AudioContext", "webkitAudioContext"]) {
      try {
        Object.defineProperty(window, name, {
          configurable: true,
          writable: true,
          value: undefined
        });
      } catch (_) {
        try { window[name] = undefined; } catch (_) {}
      }
    }

    try { audioVisualizerTick = () => {}; } catch (_) {}
    try { microscopeTick = () => {}; } catch (_) {}
    try { soundLabTick = () => {}; } catch (_) {}
    try { drawMicroscope = () => {}; } catch (_) {}
    try {
      updateSoundLab = () => renderStatus(lastStatus || nativeStatus());
    } catch (_) {}
    try {
      coreState = () => renderStatus(lastStatus || nativeStatus());
    } catch (_) {}
  }

  function nativeStatus() {
    const status = call("status");
    status.requestedMode = requestedMode;
    status.pcmConnected = status.pcmConnected === true;
    status.playbackOwner =
      status.pcmConnected ? "GW_NATIVE" : "HTML_MEDIA_ELEMENT";
    return status;
  }

  const setText = (id, value) => {
    const node = $(id);
    if (!node) return;
    const text = String(value);
    if (node.textContent !== text) node.textContent = text;
  };

  const setHtml = (id, value) => {
    const node = $(id);
    if (!node) return;
    if (node.innerHTML !== value) node.innerHTML = value;
  };

  const formatName = value =>
    ({ I16: "16", I24: "24", I32: "32 INT", FLOAT: "32 FLOAT" })[value] || "—";

  const rateName = value => {
    const rate = Number(value);
    if (rate <= 0) return "—";
    return (rate / 1000).toFixed(rate % 1000 ? 1 : 0);
  };

  function clearNativeMeters() {
    for (const id of ["slSub", "slBass", "slVocal", "slAir"]) {
      const node = $(id);
      if (node && node.style.height !== "0%") node.style.height = "0%";
    }
    for (const id of ["waveScope", "freqScope"]) {
      const canvas = $(id);
      if (canvas && canvas.getContext) {
        canvas.getContext("2d").clearRect(0, 0, canvas.width, canvas.height);
      }
    }
    setText("microPeak", "—");
    setText("microFreq", "—");
    setText("microWidth", "—");
  }

  function prepareCard() {
    const open = $("hrOpen");
    const card = open && open.closest(".settings-card");
    if (!card) return;
    const kicker = card.querySelector(".settings-kicker");
    const heading = card.querySelector("h3");
    const text = card.querySelector("p");
    if (kicker) kicker.textContent = "GW NATIVE · VERIFIED STATUS";
    if (heading) heading.textContent = "GW Native";
    if (text) {
      text.textContent =
        "Web Audio отключён. QUALITY/DSP подключатся к звуку " +
        "после добавления native decoder и PCM pipeline.";
    }
    open.textContent = "Статус GW Native ›";
  }

  function renderStatus(status) {
    if (rendering) return;
    rendering = true;
    try {
      status = status || nativeStatus();
      lastStatus = status;

      const pcm = status.pcmConnected === true;
      const streamOpen =
        Number(status.actualRate) > 0 &&
        status.actualFormat &&
        status.actualFormat !== "NONE";
      const modeLabel = requestedMode === "QUALITY" ? "QUALITY" : "DSP";
      const owner = pcm ? "GW NATIVE" : "HTML MEDIA";

      prepareCard();

      const toggle = $("hiResToggle");
      if (toggle && toggle.checked !== (requestedMode === "QUALITY")) {
        toggle.checked = requestedMode === "QUALITY";
      }

      setText("olChip", pcm ? "GW LIVE" : "GW WAIT");
      setText("olType", owner);
      setText("olRate", streamOpen ? rateName(status.actualRate) : "—");
      setText("olBits", streamOpen ? formatName(status.actualFormat) : "—");

      setHtml(
        "olDetail",
        "<div>Режим: " + modeLabel + " · Web Audio: OFF</div>" +
        "<div>Текущий playback: " + owner + "</div>" +
        "<div>GW Native PCM: " +
          (pcm ? "CONNECTED" : "NOT CONNECTED") + "</div>" +
        "<div>AAudio stream: " +
          (streamOpen
            ? (status.actualFormat + " · " + status.actualRate +
               " Hz · " + status.actualSharing)
            : "CLOSED") + "</div>" +
        (status.error
          ? ("<div>Ошибка: " +
             String(status.error).replace(/[<>&]/g, "") + "</div>")
          : "")
      );

      const lab = $("soundLabConsole");
      if (lab) lab.classList.add("native-bypass");

      setText("soundLabChip", pcm ? "GW PCM LIVE" : "NO NATIVE PCM");
      setHtml(
        "soundLabPath",
        pcm
          ? "<b>GW NATIVE</b> · PCM telemetry"
          : "<b>GW NATIVE</b> · PCM NOT CONNECTED · " +
            "анализатор ждёт native decoder"
      );

      setText("coreActive", pcm ? (status.activeModules || 0) : "0");
      setText("coreEngine", pcm ? modeLabel : "HTML MEDIA");
      setText("coreScene", pcm ? (status.scene || "OFF") : "OFF");
      setText("coreHeartText", pcm ? "GW PCM" : "PCM WAIT");
      setText("coreChip", pcm ? "GW CORE LIVE" : "GW CORE READY");

      clearNativeMeters();

      const audioNode = $("audio");
      if (audioNode && !audioNode.paused) {
        setText(
          "audioStatus",
          "PLAYBACK · HTMLMediaElement · WEB AUDIO OFF"
        );
      }
    } finally {
      rendering = false;
    }
  }

  function setRequestedMode(mode) {
    requestedMode = mode === "QUALITY" ? "QUALITY" : "DSP";
    try {
      localStorage.setItem("gw-native-mode", requestedMode);
    } catch (_) {}
    log(
      "Requested mode=" + requestedMode +
      "; native PCM is not connected yet"
    );
    renderStatus(nativeStatus());
    window.dispatchEvent(
      new CustomEvent("gw:mode-requested", {
        detail: { mode: requestedMode, pcmConnected: false }
      })
    );
  }

  function ownHiResControls() {
    document.addEventListener("click", event => {
      const target =
        event.target && event.target.closest
          ? event.target.closest("#hiResToggle,#hrOpen")
          : null;
      if (!target) return;
      event.stopImmediatePropagation();

      if (target.id === "hrOpen") {
        event.preventDefault();
        renderStatus(nativeStatus());
        const detail = $("olDetail");
        const card = detail && detail.closest(".settings-card");
        if (card) {
          card.scrollIntoView({ behavior: "smooth", block: "center" });
        }
      }
    }, true);

    document.addEventListener("change", event => {
      if (!event.target || event.target.id !== "hiResToggle") return;
      event.stopImmediatePropagation();
      setRequestedMode(event.target.checked ? "QUALITY" : "DSP");
    }, true);
  }

  function suppressLegacyToast() {
    const toast = $("toast");
    if (!toast) return;
    const clean = () => {
      const text = toast.textContent || "";
      if (/Web Audio DSP|прямой вывод без Web Audio/i.test(text)) {
        toast.textContent =
          "GW Native · " + requestedMode + " · PCM NOT CONNECTED";
      }
    };
    new MutationObserver(clean).observe(toast, {
      childList: true,
      subtree: true,
      characterData: true
    });
    clean();
  }

  window.addEventListener("error", event => {
    try {
      if (bridge && typeof bridge.jsError === "function") {
        bridge.jsError(String(event.message || "window.error"));
      }
    } catch (_) {}
  });

  disableLegacyAudioPath();
  ownHiResControls();
  suppressLegacyToast();
  prepareCard();
  clearNativeMeters();
  renderStatus(nativeStatus());

  setInterval(() => renderStatus(nativeStatus()), 1000);

  const owned = [
    "olType",
    "olRate",
    "olBits",
    "olDetail",
    "soundLabPath",
    "coreEngine",
    "coreScene"
  ].map($).filter(Boolean);

  if (owned.length) {
    let queued = false;
    const observer = new MutationObserver(() => {
      if (rendering || queued) return;
      queued = true;
      requestAnimationFrame(() => {
        queued = false;
        renderStatus(lastStatus || nativeStatus());
      });
    });
    owned.forEach(node =>
      observer.observe(node, {
        childList: true,
        subtree: true,
        characterData: true
      })
    );
  }

  window.GW_NATIVE = Object.freeze({
    status: nativeStatus,
    mode: setRequestedMode,
    devices: () => {
      try {
        return (bridge && bridge.listOutputDevices)
          ? JSON.parse(bridge.listOutputDevices())
          : [];
      } catch (_) { return []; }
    },
    logs: () => {
      try {
        return (bridge && bridge.runtimeLogs)
          ? bridge.runtimeLogs()
          : "";
      } catch (_) { return ""; }
    }
  });

  window.dispatchEvent(
    new CustomEvent("gw:native-ready", {
      detail: {
        engine: "GW Native",
        stage: "bridge-v2",
        webAudio: false,
        pcmConnected: false,
        playbackOwner: "HTML_MEDIA_ELEMENT"
      }
    })
  );
})();
