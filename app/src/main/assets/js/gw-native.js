(() => {
  "use strict";

  if (window.__GW_LIBRARY_V2__) return;
  window.__GW_LIBRARY_V2__ = true;

  const bridge = window.GwBridge;
  const $ = id => document.getElementById(id);

  let tracks = [];
  let currentIndex = -1;
  let lastRouteKey = "";

  function parse(value, fallback) {
    try {
      return JSON.parse(String(value));
    } catch (_) {
      return fallback;
    }
  }

  function setText(id, value) {
    const node = $(id);
    if (node) node.textContent = String(value);
  }

  function installStyles() {
    const style = document.createElement("style");

    style.textContent = `
      #settingsScreen .settings-tabs{
        position:relative!important;
        top:auto!important
      }

      #settingsScreen .settings-panel{
        animation:none!important
      }

      .gw-library-tools{
        display:grid;
        grid-template-columns:1fr 1fr;
        gap:10px;
        margin:12px 0 18px
      }

      .gw-library-button{
        border:1px solid rgba(235,240,244,.34);
        border-radius:22px;
        padding:14px 10px;
        background:rgba(91,110,124,.25);
        color:#f7eed7;
        font-weight:800;
        font-size:13px
      }

      .gw-track{
        display:flex;
        align-items:center;
        gap:14px;
        padding:15px 16px;
        margin:10px 0;
        border:1px solid rgba(230,236,240,.32);
        border-radius:24px;
        background:rgba(93,111,124,.22);
        box-shadow:inset 0 1px rgba(255,255,255,.35);
        cursor:pointer
      }

      .gw-track-index{
        width:44px;
        height:44px;
        flex:0 0 44px;
        border-radius:16px;
        display:grid;
        place-items:center;
        background:#f4ead0;
        color:#17212a;
        font-weight:800
      }

      .gw-track-main{
        min-width:0;
        flex:1
      }

      .gw-track-title{
        color:#f7f8fb;
        font-weight:800;
        font-size:16px;
        white-space:nowrap;
        overflow:hidden;
        text-overflow:ellipsis
      }

      .gw-track-sub{
        color:#bdc5cd;
        font-size:13px;
        margin-top:4px;
        white-space:nowrap;
        overflow:hidden;
        text-overflow:ellipsis
      }
    `;

    document.head.appendChild(style);

    document.getElementById("nw-fx-pause")?.remove();
    document.documentElement.classList.remove("nw-fx-off");
    document.documentElement.classList.add("nw-fx-on");
  }

  function restoreEffects() {
    try {
      if (typeof setEngine === "function") {
        setEngine(false, true);
      }

      if (typeof hiRes !== "undefined") {
        hiRes = false;
      }

      localStorage.setItem(
        "neonwave-hires",
        "0"
      );
    } catch (_) {}
  }

  function ensureLibraryTools() {
    const host = $("localTracks");

    if (!host || $("gwLibraryTools")) {
      return;
    }

    const tools = document.createElement("div");
    tools.id = "gwLibraryTools";
    tools.className = "gw-library-tools";

    const add = document.createElement("button");
    add.className = "gw-library-button";
    add.textContent = "ДОБАВИТЬ ФАЙЛЫ";

    const scan = document.createElement("button");
    scan.className = "gw-library-button";
    scan.textContent = "СКАНИРОВАТЬ УСТРОЙСТВО";

    add.addEventListener("click", () => {
      bridge.pickTracks();
    });

    scan.addEventListener("click", () => {
      bridge.scanDeviceMusic();
    });

    tools.append(add, scan);
    host.parentNode.insertBefore(tools, host);
  }

  document.addEventListener(
    "click",
    event => {
      const target = event.target?.closest?.(
        "#homeImport,#addMusicTab,#fileInput"
      );

      if (!target) return;

      event.preventDefault();
      event.stopPropagation();
      event.stopImmediatePropagation();

      bridge.pickTracks();
    },
    true
  );

  function loadLibrary(value) {
    tracks = Array.isArray(value)
      ? value
      : parse(bridge.library(), []);

    renderLibrary();
  }

  function renderLibrary() {
    const host = $("localTracks");

    if (!host) return;

    ensureLibraryTools();
    host.innerHTML = "";

    tracks.forEach((track, index) => {
      const row = document.createElement("div");
      row.className = "gw-track";

      const number = document.createElement("div");
      number.className = "gw-track-index";
      number.textContent = String(index + 1);

      const main = document.createElement("div");
      main.className = "gw-track-main";

      const title = document.createElement("div");
      title.className = "gw-track-title";
      title.textContent =
        track.title || track.name || "Аудиотрек";

      const subtitle = document.createElement("div");
      subtitle.className = "gw-track-sub";
      subtitle.textContent =
        track.artist || "Неизвестный исполнитель";

      main.append(title, subtitle);
      row.append(number, main);

      row.addEventListener(
        "click",
        () => playAt(index)
      );

      host.appendChild(row);
    });

    setText("trackCount", tracks.length);

    setText(
      "artistCount",
      new Set(
        tracks
          .map(track => track.artist)
          .filter(Boolean)
      ).size
    );

    setText(
      "albumCount",
      new Set(
        tracks
          .map(track => track.album)
          .filter(Boolean)
      ).size
    );
  }

  function extension(name) {
    const value = String(name || "");
    const dot = value.lastIndexOf(".");

    return dot >= 0
      ? value.slice(dot + 1).toUpperCase()
      : "AUDIO";
  }

  function playAt(index) {
    if (!tracks.length) return;

    currentIndex =
      (index + tracks.length) % tracks.length;

    const track = tracks[currentIndex];
    const audio = $("audio");

    if (!audio) return;

    try {
      if (typeof ensureAudioGraph === "function") {
        ensureAudioGraph();
      }
    } catch (_) {}

    const fileName =
      encodeURIComponent(
        track.name || "audio"
      );

    const source =
      "https://appassets.androidplatform.net/" +
      "tracks/" +
      encodeURIComponent(track.id) +
      "/" +
      fileName;

    audio.pause();
    audio.removeAttribute("crossorigin");
    audio.preload = "auto";
    audio.src = source;
    audio.load();

    const title =
      track.title ||
      track.name ||
      "Аудиотрек";

    const artist =
      track.artist ||
      "Неизвестный исполнитель";

    const format = extension(track.name);

    [
      "miniTitle",
      "playerTitle",
      "npTitle"
    ].forEach(id => setText(id, title));

    [
      "miniSub",
      "playerSub",
      "npArtist"
    ].forEach(id => setText(id, artist));

    setText("codecRead", format);
    setText("npFmt", format);

    audio.play().catch(error => {
      try {
        bridge.jsError(
          "Playback failed: " +
          String(error)
        );
      } catch (_) {}
    });
  }

  document.addEventListener(
    "click",
    event => {
      const id = event.target?.closest?.(
        "#miniNext,#nextBtn,#miniPrev,#prevBtn"
      )?.id;

      if (!id || !tracks.length) return;

      event.preventDefault();
      event.stopPropagation();
      event.stopImmediatePropagation();

      const direction =
        id === "miniNext" ||
        id === "nextBtn"
          ? 1
          : -1;

      playAt(currentIndex + direction);
    },
    true
  );

  $("audio")?.addEventListener(
    "ended",
    () => {
      if (tracks.length) {
        playAt(currentIndex + 1);
      }
    }
  );

  $("audio")?.addEventListener(
    "error",
    () => {
      try {
        const error = $("audio").error;

        bridge.jsError(
          "Audio element error code=" +
          String(error?.code || 0)
        );
      } catch (_) {}
    }
  );

  window.addEventListener(
    "gw:library-changed",
    event => {
      loadLibrary(event.detail);
    }
  );

  function routeLabel(type) {
    const labels = {
      BUILTIN_SPEAKER: "ДИНАМИК",
      BUILTIN_EARPIECE: "ТЕЛЕФОН",
      WIRED_HEADPHONES: "ПРОВОДНЫЕ НАУШНИКИ",
      WIRED_HEADSET: "ПРОВОДНАЯ ГАРНИТУРА",
      USB_DEVICE: "USB DAC",
      USB_HEADSET: "USB ГАРНИТУРА",
      BLUETOOTH_A2DP: "BLUETOOTH",
      BLE_HEADSET: "BLUETOOTH",
      BLE_SPEAKER: "BLUETOOTH",
      HEARING_AID: "BLUETOOTH",
      HDMI: "HDMI",
      HDMI_ARC: "HDMI",
      HDMI_EARC: "HDMI",
      AUX_LINE: "AUX",
      LINE_ANALOG: "AUX",
      LINE_DIGITAL: "ЦИФРОВОЙ ВЫХОД"
    };

    return labels[type] || "AUTO";
  }

  function renderRoute(route, force = false) {
    route = route || parse(
      bridge.currentOutputDevice(),
      { ok: false }
    );

    const key = route.ok
      ? [
          route.id,
          route.type,
          route.name,
          route.address
        ].join("|")
      : "AUTO";

    if (!force && key === lastRouteKey) {
      return;
    }

    lastRouteKey = key;

    const label = route.ok
      ? routeLabel(route.type)
      : "AUTO";

    setText(
      "olChip",
      route.ok ? "GW READY" : "AUTO"
    );

    setText("olType", label);
    setText("olRate", "AUTO");
    setText("olBits", "AUTO");

    const detail = $("olDetail");

    if (detail) {
      detail.innerHTML =
        "<div>Выход: <b>" +
        label +
        "</b></div>" +
        "<div>Режим: <b>AUTO</b></div>" +
        "<div>Параметры выбираются " +
        "по возможностям устройства.</div>";
    }

    const log = $("olLog");

    if (log) {
      log.innerHTML =
        "<div>Текущий выход: " +
        label +
        "</div>";
    }
  }

  window.addEventListener(
    "gw:route-changed",
    event => {
      renderRoute(event.detail, false);
    }
  );

  document.addEventListener(
    "click",
    event => {
      const target = event.target?.closest?.(
        "#olRefresh,#hrOpen"
      );

      if (!target) return;

      event.preventDefault();
      event.stopPropagation();
      event.stopImmediatePropagation();

      renderRoute(
        parse(
          bridge.currentOutputDevice(),
          { ok: false }
        ),
        true
      );

      if (target.id === "hrOpen") {
        $("outLogCard")?.scrollIntoView({
          behavior: "smooth",
          block: "center"
        });
      }
    },
    true
  );

  function installSmartEqProtection() {
    const audio = $("audio");
    const smart = $("seSw");

    const apply = () => {
      if (audio) {
        audio.volume =
          smart?.checked ? 0.78 : 0.96;
      }

      try {
        if (
          typeof compressorNode !== "undefined" &&
          compressorNode &&
          typeof audioCtx !== "undefined" &&
          audioCtx
        ) {
          const now = audioCtx.currentTime;

          compressorNode.threshold
            .setTargetAtTime(
              -10,
              now,
              0.03
            );

          compressorNode.knee
            .setTargetAtTime(
              24,
              now,
              0.03
            );

          compressorNode.ratio
            .setTargetAtTime(
              4,
              now,
              0.03
            );

          compressorNode.attack
            .setTargetAtTime(
              0.008,
              now,
              0.03
            );

          compressorNode.release
            .setTargetAtTime(
              0.2,
              now,
              0.03
            );
        }
      } catch (_) {}
    };

    smart?.addEventListener(
      "change",
      apply
    );

    audio?.addEventListener(
      "play",
      apply
    );

    document.addEventListener(
      "input",
      event => {
        if (
          event.target?.closest?.(
            "#eqBands,#eqMainCard"
          )
        ) {
          apply();
        }
      },
      true
    );
  }

  const hiResToggle = $("hiResToggle");

  if (hiResToggle) {
    hiResToggle.checked = false;
    hiResToggle.disabled = true;
    hiResToggle.title =
      "GW Native дайындалуда";
  }

  installStyles();
  restoreEffects();
  ensureLibraryTools();
  installSmartEqProtection();

  loadLibrary(
    parse(
      bridge.library(),
      []
    )
  );

  renderRoute(
    parse(
      bridge.currentOutputDevice(),
      { ok: false }
    ),
    true
  );
})();
