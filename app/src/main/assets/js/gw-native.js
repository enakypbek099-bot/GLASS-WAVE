(() => {
  "use strict";

  if (window.__GW_LIBRARY_V3__) return;
  window.__GW_LIBRARY_V3__ = true;

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

    if (node) {
      node.textContent = String(value);
    }
  }

  function installStyles() {
    const old =
      document.getElementById(
        "gw-library-style"
      );

    if (old) {
      old.remove();
    }

    const style =
      document.createElement("style");

    style.id = "gw-library-style";

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

      .gw-track-list{
        width:100%;
        margin:12px 0
      }

      .gw-track{
        display:flex;
        align-items:center;
        gap:14px;
        width:100%;
        box-sizing:border-box;
        padding:15px 16px;
        margin:10px 0;
        border:1px solid rgba(230,236,240,.32);
        border-radius:24px;
        background:rgba(93,111,124,.22);
        box-shadow:
          inset 0 1px rgba(255,255,255,.35);
        cursor:pointer
      }

      .gw-track.active{
        border-color:rgba(244,234,208,.85);
        background:rgba(124,142,154,.34)
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

      .gw-empty-mini{
        padding:22px;
        text-align:center;
        color:#bdc5cd
      }

      body.gw-signal-idle #rr8dBox{
        opacity:0!important;
        animation-play-state:paused!important;
        transition:none!important
      }

      body.gw-signal-idle #rr8dBox *{
        animation-play-state:paused!important
      }
    `;

    document.head.appendChild(style);

    document
      .getElementById("nw-fx-pause")
      ?.remove();

    document.documentElement
      .classList.remove("nw-fx-off");

    document.documentElement
      .classList.add("nw-fx-on");
  }

  function restoreEffects() {
    try {
      if (
        typeof setEngine === "function"
      ) {
        setEngine(false, true);
      }

      if (
        typeof hiRes !== "undefined"
      ) {
        hiRes = false;
      }

      localStorage.setItem(
        "neonwave-hires",
        "0"
      );
    } catch (_) {}
  }

  function requestImport() {
    try {
      bridge.pickTracks();
    } catch (error) {
      try {
        bridge.jsError(
          "Picker failed: " +
          String(error)
        );
      } catch (_) {}
    }
  }

  function requestScan() {
    try {
      bridge.scanDeviceMusic();
    } catch (error) {
      try {
        bridge.jsError(
          "Scan failed: " +
          String(error)
        );
      } catch (_) {}
    }
  }

  function isImportControl(target) {
    if (!target) {
      return false;
    }

    if (
      target.closest?.(
        "#homeImport," +
        "#addMusicTab," +
        "#fileInput"
      )
    ) {
      return true;
    }

    const button =
      target.closest?.(
        "button,a,label,[role='button']"
      );

    if (!button) {
      return false;
    }

    const text =
      (button.textContent || "")
        .replace(/\s+/g, " ")
        .trim()
        .toLowerCase();

    return (
      text === "+ добавить" ||
      text === "добавить" ||
      text.includes("добавить музыку")
    );
  }

  document.addEventListener(
    "click",
    event => {
      if (!isImportControl(event.target)) {
        return;
      }

      event.preventDefault();
      event.stopPropagation();
      event.stopImmediatePropagation();

      requestImport();
    },
    true
  );

  function createTools() {
    const tools =
      document.createElement("div");

    tools.className =
      "gw-library-tools";

    const add =
      document.createElement("button");

    add.type = "button";
    add.className =
      "gw-library-button";
    add.textContent =
      "ДОБАВИТЬ ФАЙЛЫ";

    const scan =
      document.createElement("button");

    scan.type = "button";
    scan.className =
      "gw-library-button";
    scan.textContent =
      "СКАНИРОВАТЬ УСТРОЙСТВО";

    add.addEventListener(
      "click",
      event => {
        event.preventDefault();
        event.stopPropagation();
        requestImport();
      }
    );

    scan.addEventListener(
      "click",
      event => {
        event.preventDefault();
        event.stopPropagation();
        requestScan();
      }
    );

    tools.append(add, scan);

    return tools;
  }

  function findHomeEmptyCard() {
    const button = $("homeImport");

    if (!button) {
      return null;
    }

    let node = button.parentElement;

    for (
      let depth = 0;
      node && depth < 6;
      depth++, node = node.parentElement
    ) {
      const text =
        node.textContent || "";

      if (
        text.includes("Пока здесь тихо")
      ) {
        return node;
      }
    }

    return button.parentElement;
  }

  function ensureHomeList() {
    let host = $("gwHomeLibrary");

    if (host) {
      return host;
    }

    const emptyCard =
      findHomeEmptyCard();

    if (!emptyCard?.parentNode) {
      return null;
    }

    host =
      document.createElement("div");

    host.id = "gwHomeLibrary";
    host.className =
      "gw-track-list";

    const tools = createTools();

    emptyCard.parentNode.insertBefore(
      tools,
      emptyCard
    );

    emptyCard.parentNode.insertBefore(
      host,
      emptyCard
    );

    return host;
  }

  function ensureLibraryTools() {
    const host = $("localTracks");

    if (!host || $("gwLibraryTools")) {
      return;
    }

    const tools = createTools();

    tools.id = "gwLibraryTools";

    host.parentNode?.insertBefore(
      tools,
      host
    );
  }

  function createTrackRow(
    track,
    index
  ) {
    const row =
      document.createElement("div");

    row.className =
      "gw-track";

    row.dataset.trackId =
      track.id;

    const number =
      document.createElement("div");

    number.className =
      "gw-track-index";

    number.textContent =
      String(index + 1);

    const main =
      document.createElement("div");

    main.className =
      "gw-track-main";

    const title =
      document.createElement("div");

    title.className =
      "gw-track-title";

    title.textContent =
      track.title ||
      track.name ||
      "Аудиотрек";

    const subtitle =
      document.createElement("div");

    subtitle.className =
      "gw-track-sub";

    subtitle.textContent =
      track.artist ||
      "Неизвестный исполнитель";

    main.append(title, subtitle);
    row.append(number, main);

    row.addEventListener(
      "click",
      () => {
        playAt(index);
      }
    );

    return row;
  }

  function renderInto(
    host,
    showEmpty
  ) {
    if (!host) {
      return;
    }

    host.innerHTML = "";

    if (!tracks.length) {
      if (showEmpty) {
        const empty =
          document.createElement("div");

        empty.className =
          "gw-empty-mini";

        empty.textContent =
          "Добавьте музыку с устройства";

        host.appendChild(empty);
      }

      return;
    }

    tracks.forEach(
      (track, index) => {
        host.appendChild(
          createTrackRow(
            track,
            index
          )
        );
      }
    );
  }

  function loadLibrary(value) {
    tracks =
      Array.isArray(value)
        ? value
        : [];

    renderLibrary();
  }

  function renderLibrary() {
    ensureLibraryTools();

    const homeHost =
      ensureHomeList();

    const libraryHost =
      $("localTracks");

    renderInto(
      homeHost,
      false
    );

    renderInto(
      libraryHost,
      true
    );

    const emptyCard =
      findHomeEmptyCard();

    if (emptyCard) {
      emptyCard.style.display =
        tracks.length ? "none" : "";
    }

    setText(
      "trackCount",
      tracks.length
    );

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
    const value =
      String(name || "");

    const dot =
      value.lastIndexOf(".");

    return dot >= 0
      ? value
          .slice(dot + 1)
          .toUpperCase()
      : "AUDIO";
  }

  function highlightCurrent() {
    document
      .querySelectorAll(
        ".gw-track"
      )
      .forEach(row => {
        row.classList.toggle(
          "active",
          row.dataset.trackId ===
            tracks[currentIndex]?.id
        );
      });
  }

  function playAt(index) {
    if (!tracks.length) {
      return;
    }

    currentIndex =
      (
        index +
        tracks.length
      ) % tracks.length;

    const track =
      tracks[currentIndex];

    const audio = $("audio");

    if (!audio) {
      return;
    }

    try {
      if (
        typeof ensureAudioGraph ===
        "function"
      ) {
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

    const format =
      extension(track.name);

    [
      "miniTitle",
      "playerTitle",
      "npTitle"
    ].forEach(id => {
      setText(id, title);
    });

    [
      "miniSub",
      "playerSub",
      "npArtist"
    ].forEach(id => {
      setText(id, artist);
    });

    setText(
      "codecRead",
      format
    );

    setText(
      "npFmt",
      format
    );

    highlightCurrent();
    syncSignalState();

    audio.play()
      .then(() => {
        syncSignalState();
      })
      .catch(error => {
        syncSignalState();

        try {
          bridge.jsError(
            "Playback failed: " +
            String(error)
          );
        } catch (_) {}
      });
  }

  function syncSignalState() {
    const audio = $("audio");

    const active =
      !!audio &&
      !audio.paused &&
      !audio.ended &&
      audio.readyState >= 2;

    document.body.classList.toggle(
      "gw-signal-idle",
      !active
    );
  }

  const audio = $("audio");

  [
    "play",
    "playing",
    "pause",
    "ended",
    "emptied",
    "error",
    "waiting",
    "canplay"
  ].forEach(eventName => {
    audio?.addEventListener(
      eventName,
      syncSignalState
    );
  });

  audio?.addEventListener(
    "ended",
    () => {
      if (tracks.length) {
        playAt(currentIndex + 1);
      }
    }
  );

  document.addEventListener(
    "click",
    event => {
      const id =
        event.target?.closest?.(
          "#miniNext," +
          "#nextBtn," +
          "#miniPrev," +
          "#prevBtn"
        )?.id;

      if (!id || !tracks.length) {
        return;
      }

      event.preventDefault();
      event.stopPropagation();
      event.stopImmediatePropagation();

      const direction =
        id === "miniNext" ||
        id === "nextBtn"
          ? 1
          : -1;

      playAt(
        currentIndex +
        direction
      );
    },
    true
  );

  window.GW_LIBRARY =
    Object.freeze({
      replace: loadLibrary,
      play: playAt,
      tracks: () =>
        tracks.slice()
    });

  window.addEventListener(
    "gw:library-changed",
    event => {
      loadLibrary(
        Array.isArray(event.detail)
          ? event.detail
          : []
      );
    }
  );

  function routeLabel(type) {
    const labels = {
      BUILTIN_SPEAKER:
        "ДИНАМИК",

      BUILTIN_EARPIECE:
        "ТЕЛЕФОН",

      WIRED_HEADPHONES:
        "ПРОВОДНЫЕ НАУШНИКИ",

      WIRED_HEADSET:
        "ПРОВОДНАЯ ГАРНИТУРА",

      USB_DEVICE:
        "USB DAC",

      USB_HEADSET:
        "USB ГАРНИТУРА",

      BLUETOOTH_A2DP:
        "BLUETOOTH",

      BLE_HEADSET:
        "BLUETOOTH",

      BLE_SPEAKER:
        "BLUETOOTH",

      HEARING_AID:
        "BLUETOOTH",

      HDMI:
        "HDMI",

      HDMI_ARC:
        "HDMI",

      HDMI_EARC:
        "HDMI",

      AUX_LINE:
        "AUX",

      LINE_ANALOG:
        "AUX",

      LINE_DIGITAL:
        "ЦИФРОВОЙ ВЫХОД"
    };

    return labels[type] || "AUTO";
  }

  function renderRoute(
    route,
    force
  ) {
    route =
      route ||
      parse(
        bridge.currentOutputDevice(),
        { ok: false }
      );

    const key =
      route.ok
        ? [
            route.id,
            route.type,
            route.name,
            route.address
          ].join("|")
        : "AUTO";

    if (
      !force &&
      key === lastRouteKey
    ) {
      return;
    }

    lastRouteKey = key;

    const label =
      route.ok
        ? routeLabel(route.type)
        : "AUTO";

    setText(
      "olChip",
      route.ok
        ? "GW READY"
        : "AUTO"
    );

    setText("olType", label);
    setText("olRate", "AUTO");
    setText("olBits", "AUTO");

    const detail =
      $("olDetail");

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
      renderRoute(
        event.detail,
        false
      );
    }
  );

  document.addEventListener(
    "click",
    event => {
      const target =
        event.target?.closest?.(
          "#olRefresh,#hrOpen"
        );

      if (!target) {
        return;
      }

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

      if (
        target.id === "hrOpen"
      ) {
        $("outLogCard")
          ?.scrollIntoView({
            behavior: "smooth",
            block: "center"
          });
      }
    },
    true
  );

  function installSmartEqProtection() {
    const smart = $("seSw");

    const apply = () => {
      if (audio) {
        audio.volume =
          smart?.checked
            ? 0.78
            : 0.96;
      }

      try {
        if (
          typeof compressorNode !==
            "undefined" &&
          compressorNode &&
          typeof audioCtx !==
            "undefined" &&
          audioCtx
        ) {
          const now =
            audioCtx.currentTime;

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
  }

  const hiResToggle =
    $("hiResToggle");

  if (hiResToggle) {
    hiResToggle.checked = false;
    hiResToggle.disabled = true;
    hiResToggle.title =
      "GW Native дайындалуда";
  }

  installStyles();
  restoreEffects();
  ensureLibraryTools();
  ensureHomeList();
  installSmartEqProtection();
  syncSignalState();

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
