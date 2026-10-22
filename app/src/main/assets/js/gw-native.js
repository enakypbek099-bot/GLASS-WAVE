(() => {
  "use strict";

  if (window.__GW_NATIVE_V3__) return;

  window.__GW_NATIVE_V3__ = true;
  window.GW_NATIVE_ACTIVE = true;

  const bridge = window.GwBridge || null;
  const $ = id => document.getElementById(id);

  let selectedMode =
    localStorage.getItem("gw-native-mode") === "QUALITY"
      ? "QUALITY"
      : "DSP";

  function parse(value) {
    try { return JSON.parse(String(value)); }
    catch (_) { return {}; }
  }

  function nativeStatus() {
    try {
      if (bridge && typeof bridge.status === "function") {
        return parse(bridge.status());
      }
    } catch (_) {}
    return {};
  }

  function devLog(message) {
    try {
      if (bridge && typeof bridge.jsLog === "function") {
        bridge.jsLog("INFO", String(message));
      }
    } catch (_) {}
  }

  /*
   * Тек Web Audio графының жаңа құрылуын ғана тоқтатамыз.
   *
   * МАҢЫЗДЫ — өшірілмейтін нәрселер:
   *   - audioVisualizerTick (8D orb, EQ meter визуалы)
   *   - microscopeTick / drawMicroscope
   *   - soundLabTick / updateSoundLab
   *   - scene canvas анимациясы
   *   - coreState live жаңартуы
   *   - EQ slider / preset / Tone slider handler-лері
   *   - AudioContext / webkitAudioContext constructor-лары
   *
   * Себебі: сары 8D шар, 3D scene анимациясы, EQ meter
   * қозғалысы — intended visual UX. Оларды жоюға болмайды.
   */
  function stopNewAudioGraphOnly() {
    try { window.ensureAudioGraph = () => false; } catch (_) {}
    try { ensureAudioGraph = () => false; } catch (_) {}

    // Legacy hiRes flag false күйде қалсын — сонда түпнұсқа
    // HTML өз-өзінен scene/analyser анимациясын тоқтатпайды.
    try { if (typeof hiRes !== "undefined") hiRes = false; } catch (_) {}
    try { localStorage.setItem("neonwave-hires", "0"); } catch (_) {}
  }

  function setText(id, value) {
    const node = $(id);
    if (!node) return;
    const text = String(value);
    if (node.textContent !== text) node.textContent = text;
  }

  function prepareNativeCard() {
    const open = $("hrOpen");
    const card = open && open.closest(".settings-card");
    if (!card) return;

    const kicker = card.querySelector(".settings-kicker");
    const heading = card.querySelector("h3");
    const description = card.querySelector("p");

    if (kicker) kicker.textContent = "GW NATIVE · HI-RES ENGINE";
    if (heading) heading.textContent = "GW Native";
    if (description) {
      description.textContent =
        "Режим высокой точности, цифровой обработки " +
        "и пространственного звучания.";
    }
    if (open) open.textContent = "Параметры GW Native ›";
  }

  // Қолданушы бетінде ешқашан көрінбеуі керек сөздер.
  const FORBIDDEN =
    /Web Audio|HTML MEDIA|AudioContext|HTMLMediaElement|PCM(\s|$)|AAudio|Oboe|NOT CONNECTED|CLOSED\b|STRICT_I32|bypass/i;

  function sanitizeNode(node) {
    if (!node) return;
    const text = node.textContent || "";
    if (!FORBIDDEN.test(text)) return;

    const cleaned = text
      .replace(/Web Audio[^.·]*[.·]?/gi, "")
      .replace(/HTML MEDIA/gi, "СИСТЕМНЫЙ ВЫХОД")
      .replace(/HTMLMediaElement/gi, "СИСТЕМНЫЙ ВЫХОД")
      .replace(/AudioContext/gi, "GW NATIVE")
      .replace(/AAudio stream[^.·]*[.·]?/gi, "")
      .replace(/Oboe[^.·]*[.·]?/gi, "")
      .replace(/PCM NOT CONNECTED/gi, "ОЖИДАНИЕ СИГНАЛА")
      .replace(/NOT CONNECTED/gi, "")
      .replace(/\bCLOSED\b/gi, "")
      .replace(/STRICT_I32[^\s.·]*/gi, "")
      .replace(/\s+/g, " ")
      .trim();

    node.textContent = cleaned || "GW NATIVE";
  }

  function renderPublicStatus() {
    const result = nativeStatus();

    const streamReady =
      Number(result.actualRate) > 0 &&
      result.actualFormat &&
      result.actualFormat !== "NONE";

    setText("olChip", streamReady ? "GW ACTIVE" : "GW READY");
    setText("olType", "AUTO");

    if (streamReady) {
      const rate = Number(result.actualRate);
      setText(
        "olRate",
        (rate / 1000).toFixed(rate % 1000 ? 1 : 0)
      );
      const bits = { I16: "16", I24: "24", I32: "32", FLOAT: "32F" };
      setText("olBits", bits[result.actualFormat] || "—");
    } else {
      setText("olRate", "—");
      setText("olBits", "—");
    }

    const detail = $("olDetail");
    if (detail) {
      const modeName = selectedMode === "QUALITY" ? "QUALITY" : "DSP";
      detail.innerHTML =
        "<div>Режим: <b>" + modeName + "</b></div>" +
        "<div>Выход: <b>AUTO</b></div>" +
        "<div>Формат и частота определяются автоматически.</div>";
    }

    setText("soundLabChip", "GW SIGNAL");
    const soundPath = $("soundLabPath");
    if (soundPath) {
      soundPath.innerHTML = "<b>GW NATIVE</b> · мониторинг сигнала";
    }

    setText("coreEngine", "GW NATIVE");
    setText("coreChip", "GW CORE");
  }

  function showSafeToast(message) {
    const toast = $("toast");
    if (!toast) return;
    toast.textContent = message;
    try {
      toast.classList.add("show");
      clearTimeout(window.__GW_SAFE_TOAST_TIMER__);
      window.__GW_SAFE_TOAST_TIMER__ = setTimeout(
        () => toast.classList.remove("show"),
        1800
      );
    } catch (_) {}
  }

  function setMode(mode) {
    selectedMode = mode === "QUALITY" ? "QUALITY" : "DSP";
    try { localStorage.setItem("gw-native-mode", selectedMode); } catch (_) {}

    // Legacy hiRes flag false болып қалады — визуалдар жұмысын
    // жалғастырады.
    try { if (typeof hiRes !== "undefined") hiRes = false; } catch (_) {}

    renderPublicStatus();
    devLog("GW mode selected: " + selectedMode);

    showSafeToast(
      selectedMode === "QUALITY"
        ? "GW Native · QUALITY"
        : "GW Native · DSP"
    );

    window.dispatchEvent(
      new CustomEvent("gw:mode-selected", {
        detail: { mode: selectedMode }
      })
    );
  }

  function ownNativeControls() {
    document.addEventListener("change", event => {
      if (!event.target || event.target.id !== "hiResToggle") return;

      // Ескі setEngine() listener-і орындалмайды —
      // бұрынғы техникалық toast шықпайды.
      event.preventDefault();
      event.stopPropagation();
      event.stopImmediatePropagation();

      setMode(event.target.checked ? "QUALITY" : "DSP");
    }, true);

    document.addEventListener("click", event => {
      const target =
        event.target && event.target.closest
          ? event.target.closest("#hrOpen")
          : null;
      if (!target) return;

      event.preventDefault();
      event.stopPropagation();
      event.stopImmediatePropagation();

      renderPublicStatus();

      const output = $("outLogCard");
      if (output) {
        output.scrollIntoView({ behavior: "smooth", block: "center" });
      }
    }, true);
  }

  function protectPublicText() {
    // Toast
    const toast = $("toast");
    if (toast) {
      const clean = () => {
        if (FORBIDDEN.test(toast.textContent || "")) {
          toast.textContent = "GW Native · " + selectedMode;
        }
      };
      new MutationObserver(clean).observe(toast, {
        childList: true, subtree: true, characterData: true
      });
      clean();
    }

    // Native card бағытталған мәтіндік ID-лар
    const guardedIds = [
      "olType", "olRate", "olBits", "olDetail", "olLog",
      "soundLabChip", "soundLabPath",
      "coreEngine", "coreScene", "coreChip", "coreHeartText",
      "audioStatus"
    ];

    for (const id of guardedIds) {
      const node = $(id);
      if (!node) continue;
      sanitizeNode(node);
      new MutationObserver(() => sanitizeNode(node)).observe(node, {
        childList: true, subtree: true, characterData: true
      });
    }
  }

  // Бастапқы Hi-Res toggle күйі
  function restoreToggleState() {
    const toggle = $("hiResToggle");
    if (toggle) toggle.checked = selectedMode === "QUALITY";
  }

  /*
   * UI анимациялары МҮЛДЕ ҚОЗҒАЛМАЙДЫ.
   *
   * Қайта анықталмайды:
   *   - audioVisualizerTick    (сары 8D orb, EQ meter)
   *   - microscopeTick / drawMicroscope
   *   - soundLabTick / updateSoundLab
   *   - scene canvas анимациясы
   *   - coreState live өрістері
   *
   * Қолданушының intended visual UX толық сақталады.
   */
  stopNewAudioGraphOnly();
  prepareNativeCard();
  ownNativeControls();
  protectPublicText();
  renderPublicStatus();
  restoreToggleState();

  // Status өрістерін сирек жаңартамыз — flicker-ды болдырмау үшін.
  setInterval(renderPublicStatus, 2000);

  window.addEventListener("error", event => {
    try {
      if (bridge && typeof bridge.jsError === "function") {
        bridge.jsError(String(event.message || "window.error"));
      }
    } catch (_) {}
  });

  window.GW_NATIVE = Object.freeze({
    status: nativeStatus,
    setMode,
    devices: () => {
      try {
        if (bridge && typeof bridge.listOutputDevices === "function") {
          return JSON.parse(bridge.listOutputDevices());
        }
      } catch (_) {}
      return [];
    },
    diagnostics: () => {
      try {
        if (bridge && typeof bridge.runtimeLogs === "function") {
          return bridge.runtimeLogs();
        }
      } catch (_) {}
      return "";
    }
  });

  window.dispatchEvent(
    new CustomEvent("gw:native-ready", {
      detail: {
        engine: "GW Native",
        mode: selectedMode,
        visualLayer: true
      }
    })
  );
})();
