(() => {
  "use strict";

  if (window.__GW_NATIVE_JS_V5__) return;
  window.__GW_NATIVE_JS_V5__ = true;

  const bridge = window.GwBridge;
  const $ = id => document.getElementById(id);

  const STATE_KEY = "gw-playback-state-v5";

  let stored = [];
  let libraryTimer = 0;
  let libraryPending = null;
  let currentSignature = "";

  let handoffActive = false;
  let handoffInProgress = false;
  let suppressHandoffUntil = 0;

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

  /*
   * ------------------------------------------------------------
   * TOUCH / FOCUS
   * ------------------------------------------------------------
   */

  const touchStyle =
    document.createElement("style");

  touchStyle.id =
    "gw-touch-stability-v5";

  touchStyle.textContent = `
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
      -webkit-tap-highlight-color:
        transparent !important;
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
      outline:
        2px solid rgba(244,234,208,.88)
        !important;

      outline-offset:
        3px !important;
    }

    .gw-route-locked {
      opacity: .46 !important;
      filter: saturate(.35) !important;
    }
  `;

  document.head.appendChild(
    touchStyle
  );

  /*
   * ------------------------------------------------------------
   * TRACK METADATA
   * ------------------------------------------------------------
   */

  function strictHiRes(raw) {
    return (
      raw &&
      raw.sourceVerified === true &&
      Number(
        raw.bitsPerSample || 0
      ) > 16 &&
      Number(
        raw.sampleRate || 0
      ) >= 44100
    );
  }

  function legacyTrack(raw) {
    const seconds =
      Number(
        raw.durationMs || 0
      ) / 1000;

    const verified =
      raw.sourceVerified === true;

    const sampleRate =
      Number(
        raw.sampleRate || 0
      );

    const bits =
      Number(
        raw.bitsPerSample || 0
      );

    const channels =
      Number(
        raw.channels || 0
      );

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

      art: "vinyl",
      playlist: "local",
      duration: seconds,
      local: true,

      file: {
        name:
          raw.name ||
          "audio",

        size:
          Number(
            raw.size || 0
          ),

        lastModified: 0
      },

      coverUrl:
        raw.coverUrl ||
        null,

      info: {
        fmt: format,
        sr: sampleRate,
        bits,
        ch: channels,
        verified,

        /*
         * Бұл SOURCE Hi-Res.
         * Native output дәлелі емес.
         */
        hi:
          strictHiRes(raw),

        label:
          verified
            ? (
                format +
                " · " +
                (
                  sampleRate /
                  1000
                ).toFixed(1) +
                " кГц · " +
                bits +
                " бит"
              )
            : format
      }
    };
  }

  function truthfulBadge(track) {
    const info =
      track &&
      track.info;

    if (!info) {
      return "";
    }

    const flac =
      info.fmt === "FLAC"
        ? (
            ' <span class="flac-tag">' +
            "FLAC" +
            "</span>"
          )
        : "";

    const hi =
      info.verified === true &&
      Number(
        info.bits || 0
      ) > 16 &&
      Number(
        info.sr || 0
      ) >= 44100;

    return (
      flac +
      (
        hi
          ? (
              ' <span class="hr3d" ' +
              'title="Hi-Res source">' +
              "<i>Hi-Res</i>" +
              "<b>AUDIO</b>" +
              "</span>"
            )
          : ""
      )
    );
  }

  try {
    hrBadge =
      truthfulBadge;
  } catch (_) {}

  try {
    engineLabel =
      track => {
        const info =
          track &&
          track.info;

        if (!info) {
          return "GW READY";
        }

        if (
          info.verified === true
        ) {
          return (
            info.fmt +
            " · " +
            (
              Number(
                info.sr || 0
              ) /
              1000
            ).toFixed(1) +
            " кГц · " +
            Number(
              info.bits || 0
            ) +
            " бит"
          );
        }

        return (
          info.fmt ||
          "GW READY"
        );
      };
  } catch (_) {}

  /*
   * ------------------------------------------------------------
   * LIBRARY
   * ------------------------------------------------------------
   */

  function librarySignature(
    list
  ) {
    return list
      .map(item => [
        item.id,
        item.title,
        item.artist,
        item.album,
        item.durationMs,
        item.coverUrl,
        item.sampleRate,
        item.bitsPerSample,
        item.sourceVerified,
        item.hiRes
      ].join("|"))
      .join(";");
  }

  function applyLibrary(
    value
  ) {
    const list =
      Array.isArray(value)
        ? value
        : [];

    const signature =
      librarySignature(
        list
      );

    if (
      signature ===
      currentSignature
    ) {
      return;
    }

    currentSignature =
      signature;

    stored =
      list;

    const activeId =
      typeof currentTrack !==
        "undefined" &&
      currentTrack
        ? currentTrack.gwId
        : null;

    try {
      localTracks =
        stored.map(
          legacyTrack
        );

      queue =
        allTracks();
    } catch (_) {
      return;
    }

    if (activeId) {
      const fresh =
        localTracks.find(
          item =>
            item.gwId ===
            activeId
        );

      if (fresh) {
        currentTrack =
          fresh;

        currentIndex =
          queue.indexOf(
            fresh
          );

        try {
          setTrackMeta(
            fresh
          );
        } catch (_) {}
      }
    }

    try {
      renderAllTracks();
    } catch (_) {}
  }

  function replaceLibrary(
    value
  ) {
    libraryPending =
      Array.isArray(value)
        ? value
        : [];

    clearTimeout(
      libraryTimer
    );

    /*
     * Metadata/artwork бірнеше рет
     * келгенде Home DOM-ды әр жолы
     * қайта салмаймыз.
     */
    libraryTimer =
      setTimeout(
        () => {
          const next =
            libraryPending;

          libraryPending =
            null;

          applyLibrary(
            next
          );
        },
        450
      );
  }

  window.GW_LIBRARY =
    Object.freeze({
      replace:
        replaceLibrary,

      tracks:
        () =>
          stored.slice()
    });

  window.addEventListener(
    "gw:library-changed",
    event => {
      replaceLibrary(
        event.detail
      );
    }
  );

  /*
   * ------------------------------------------------------------
   * PLAYBACK STATE
   * ------------------------------------------------------------
   */

  function saveSnapshot(
    force
  ) {
    if (
      typeof currentTrack ===
        "undefined" ||
      !currentTrack ||
      !currentTrack.gwId ||
      typeof audio ===
        "undefined" ||
      !audio
    ) {
      return;
    }

    const now =
      Date.now();

    if (
      !force &&
      now -
        lastSnapshotAt <
        1500
    ) {
      return;
    }

    lastSnapshotAt =
      now;

    const positionMs =
      Math.max(
        0,
        Math.round(
          (
            audio.currentTime ||
            0
          ) *
          1000
        )
      );

    try {
      bridge
        .updatePlaybackSnapshot(
          currentTrack.gwId,
          positionMs,
          !audio.paused &&
            !audio.ended
        );
    } catch (_) {}

    try {
      localStorage.setItem(
        STATE_KEY,
        JSON.stringify({
          trackId:
            currentTrack.gwId,

          positionMs,

          paused:
            audio.paused,

          savedAt:
            now
        })
      );
    } catch (_) {}
  }

  /*
   * ------------------------------------------------------------
   * LOAD / PLAY TRACK
   * ------------------------------------------------------------
   */

  function loadTrack(
    index,
    positionSeconds,
    autoplay
  ) {
    try {
      if (!queue.length) {
        queue =
          allTracks();
      }
    } catch (_) {
      return;
    }

    if (
      index < 0 ||
      index >= queue.length
    ) {
      return;
    }

    const track =
      queue[index];

    const same =
      typeof currentTrack !==
        "undefined" &&
      currentTrack &&
      currentTrack.gwId ===
        track.gwId &&
      typeof audio !==
        "undefined" &&
      audio &&
      audio.dataset &&
      audio.dataset
        .gwTrackId ===
        track.gwId;

    currentIndex =
      index;

    currentTrack =
      track;

    try {
      setTrackMeta(
        track
      );
    } catch (_) {}

    try {
      audio =
        webEl;
    } catch (_) {
      return;
    }

    if (!audio) {
      return;
    }

    if (!same) {
      try {
        audio.pause();
      } catch (_) {}

      audio.dataset
        .gwTrackId =
        track.gwId;

      audio.src =
        sourceFor(
          track
        );

      audio.preload =
        "auto";

      try {
        audio.load();
      } catch (_) {}
    }

    const wanted =
      Number(
        positionSeconds ||
        0
      );

    const seek =
      () => {
        if (
          wanted > 0 &&
          Number.isFinite(
            audio.duration
          )
        ) {
          try {
            audio.currentTime =
              Math.min(
                wanted,
                Math.max(
                  0,
                  audio.duration -
                    0.25
                )
              );
          } catch (_) {}
        }
      };

    if (
      audio.readyState >= 1
    ) {
      seek();
    } else {
      audio.addEventListener(
        "loadedmetadata",
        seek,
        {
          once: true
        }
      );
    }

    try {
      ensureAudioGraph();
    } catch (_) {}

    try {
      if (
        typeof audioCtx !==
          "undefined" &&
        audioCtx &&
        audioCtx.state ===
          "suspended"
      ) {
        audioCtx
          .resume()
          .catch(
            () => {}
          );
      }
    } catch (_) {}

    if (autoplay) {
      try {
        bridge
          .prepareBackgroundPlayback();
      } catch (_) {}

      audio
        .play()
        .catch(
          () => {
            try {
              toast(
                "Не удалось запустить трек."
              );
            } catch (_) {}
          }
        );
    }

    try {
      renderAllTracks();
    } catch (_) {}

    try {
      updatePlayButtons();
    } catch (_) {}

    saveSnapshot(
      true
    );
  }

  try {
    playTrack =
      index =>
        loadTrack(
          index,
          0,
          true
        );
  } catch (_) {}

  /*
   * ------------------------------------------------------------
   * STABLE SEEK
   * ------------------------------------------------------------
   *
   * Original HTML:
   *
   * seekBar input ->
   * audio.currentTime = ...
   *
   * Drag кезінде ондаған seek жіберетін.
   *
   * Енді:
   * input = preview only
   * pointerup/change = ONE seek
   */

  function installStableSeek() {
    const bar =
      $("seekBar");

    const media =
      $("audio");

    if (
      !bar ||
      !media ||
      bar.dataset
        .gwStableSeek ===
        "1"
    ) {
      return;
    }

    bar.dataset
      .gwStableSeek =
      "1";

    let dragging =
      false;

    let resumeAfterSeek =
      false;

    let requestedTime =
      null;

    function preview() {
      if (
        !Number.isFinite(
          media.duration
        ) ||
        media.duration <= 0
      ) {
        return;
      }

      requestedTime =
        media.duration *
        Number(
          bar.value || 0
        ) /
        100;

      const elapsed =
        $("elapsed");

      if (
        elapsed &&
        typeof fmt ===
          "function"
      ) {
        elapsed.textContent =
          fmt(
            requestedTime
          );
      }
    }

    function commit() {
      if (
        requestedTime ===
          null ||
        !Number.isFinite(
          requestedTime
        ) ||
        !Number.isFinite(
          media.duration
        )
      ) {
        dragging =
          false;

        return;
      }

      const target =
        Math.max(
          0,
          Math.min(
            requestedTime,
            Math.max(
              0,
              media.duration -
                0.05
            )
          )
        );

      dragging =
        false;

      requestedTime =
        null;

      const shouldResume =
        resumeAfterSeek;

      resumeAfterSeek =
        false;

      let completed =
        false;

      const restart =
        function () {
          if (completed) {
            return;
          }

          completed =
            true;

          media
            .removeEventListener(
              "seeked",
              restart
            );

          if (
            shouldResume
          ) {
            media
              .play()
              .catch(
                () => {}
              );
          }

          saveSnapshot(
            true
          );
        };

      media.addEventListener(
        "seeked",
        restart,
        {
          once: true
        }
      );

      try {
        media.currentTime =
          target;
      } catch (_) {
        media
          .removeEventListener(
            "seeked",
            restart
          );

        if (
          shouldResume
        ) {
          media
            .play()
            .catch(
              () => {}
            );
        }
      }

      /*
       * Кей WebView seeked event
       * кешіктірсе fallback.
       */
      setTimeout(
        restart,
        1200
      );
    }

    function begin() {
      dragging =
        true;

      resumeAfterSeek =
        !media.paused &&
        !media.ended;
    }

    bar.addEventListener(
      "pointerdown",
      begin,
      true
    );

    bar.addEventListener(
      "touchstart",
      begin,
      {
        capture: true,
        passive: true
      }
    );

    /*
     * Capture phase:
     * HTML ішіндегі original
     * input listener-ге жетпейді.
     */
    document.addEventListener(
      "input",
      event => {
        if (
          event.target !==
          bar
        ) {
          return;
        }

        event.stopPropagation();
        event
          .stopImmediatePropagation();

        if (!dragging) {
          begin();
        }

        preview();
      },
      true
    );

    bar.addEventListener(
      "change",
      commit,
      true
    );

    bar.addEventListener(
      "pointerup",
      commit,
      true
    );

    bar.addEventListener(
      "pointercancel",
      commit,
      true
    );

    bar.addEventListener(
      "touchend",
      commit,
      true
    );
  }

  /*
   * ------------------------------------------------------------
   * BACKGROUND HANDOFF
   * ------------------------------------------------------------
   *
   * Ескі қате:
   *
   * WebView pause
   * -> service start
   *
   * Жаңа:
   *
   * service start
   * -> service isPlaying=true
   * -> WebView pause
   */

  function handoff() {
    if (
      handoffActive ||
      handoffInProgress ||
      Date.now() <
        suppressHandoffUntil ||
      typeof currentTrack ===
        "undefined" ||
      !currentTrack ||
      !currentTrack.gwId ||
      typeof audio ===
        "undefined" ||
      !audio ||
      audio.paused
    ) {
      return;
    }

    saveSnapshot(
      true
    );

    const trackId =
      currentTrack.gwId;

    const positionMs =
      Math.max(
        0,
        Math.round(
          (
            audio.currentTime ||
            0
          ) *
          1000
        )
      );

    let started =
      false;

    try {
      started =
        bridge
          .startBackgroundPlayback(
            trackId,
            positionMs
          );
    } catch (_) {
      started =
        false;
    }

    if (!started) {
      return;
    }

    handoffInProgress =
      true;

    const startedAt =
      Date.now();

    function verify() {
      let state = {
        active: false,
        isPlaying: false,
        trackId: ""
      };

      try {
        state =
          parse(
            bridge
              .backgroundState(),
            state
          );
      } catch (_) {}

      if (
        state.active ===
          true &&
        state.isPlaying ===
          true &&
        state.trackId ===
          trackId
      ) {
        handoffActive =
          true;

        handoffInProgress =
          false;

        /*
         * Service дәл қазір
         * ойнап тұрғаны расталды.
         * Енді ғана WebView тоқтайды.
         */
        try {
          audio.pause();
        } catch (_) {}

        return;
      }

      if (
        Date.now() -
          startedAt <
        3000
      ) {
        setTimeout(
          verify,
          120
        );

        return;
      }

      /*
       * Service 3 секундта
       * PLAYING болмады.
       * WebView owner болып қалады.
       */
      handoffInProgress =
        false;

      handoffActive =
        false;

      try {
        if (
          audio.paused
        ) {
          audio
            .play()
            .catch(
              () => {}
            );
        }
      } catch (_) {}
    }

    verify();
  }

  /*
   * ------------------------------------------------------------
   * FOREGROUND RECLAIM
   * ------------------------------------------------------------
   */

  function reclaim() {
    let state = {
      active: false
    };

    try {
      state =
        parse(
          bridge
            .backgroundState(),
          state
        );
    } catch (_) {}

    if (
      !state.active ||
      !state.trackId
    ) {
      handoffActive =
        false;

      handoffInProgress =
        false;

      return;
    }

    if (
      !stored.length
    ) {
      try {
        applyLibrary(
          parse(
            bridge.library(),
            []
          )
        );
      } catch (_) {}
    }

    try {
      queue =
        allTracks();
    } catch (_) {
      return;
    }

    const track =
      localTracks.find(
        item =>
          item.gwId ===
          state.trackId
      );

    if (!track) {
      return;
    }

    const index =
      queue.indexOf(
        track
      );

    if (index < 0) {
      return;
    }

    /*
     * Алдымен WebView track-ты
     * service позициясына дайындайды.
     */
    loadTrack(
      index,
      Number(
        state.positionMs ||
        0
      ) /
      1000,
      false
    );

    const media =
      $("audio");

    if (!media) {
      return;
    }

    const serviceWasPlaying =
      state.isPlaying ===
      true;

    const finish =
      function () {
        if (
          serviceWasPlaying
        ) {
          media
            .play()
            .then(
              () => {
                /*
                 * WebView ойнай бастағаннан
                 * кейін ғана service stop.
                 */
                try {
                  bridge
                    .claimForegroundPlayback();
                } catch (_) {}

                handoffActive =
                  false;

                handoffInProgress =
                  false;
              }
            )
            .catch(
              () => {}
            );
        } else {
          try {
            bridge
              .claimForegroundPlayback();
          } catch (_) {}

          handoffActive =
            false;

          handoffInProgress =
            false;
        }
      };

    if (
      media.readyState >= 2
    ) {
      setTimeout(
        finish,
        80
      );
    } else {
      media.addEventListener(
        "canplay",
        finish,
        {
          once: true
        }
      );
    }
  }

  window.GW_BACKGROUND =
    Object.freeze({
      handoff,
      reclaim
    });

  /*
   * ------------------------------------------------------------
   * VISIBILITY
   * ------------------------------------------------------------
   */

  document.addEventListener(
    "visibilitychange",
    () => {
      if (
        document.hidden
      ) {
        handoff();
      } else {
        setTimeout(
          reclaim,
          150
        );
      }
    }
  );

  window.addEventListener(
    "pagehide",
    () => {
      handoff();
    }
  );

  /*
   * ------------------------------------------------------------
   * MEDIA EVENTS
   * ------------------------------------------------------------
   */

  const media =
    $("audio");

  [
    "timeupdate",
    "play",
    "pause",
    "seeking",
    "seeked",
    "ended"
  ].forEach(
    name => {
      media?.addEventListener(
        name,
        () => {
          saveSnapshot(
            name !==
              "timeupdate"
          );
        }
      );
    }
  );

  [
    "error",
    "stalled",
    "waiting",
    "abort"
  ].forEach(
    name => {
      media?.addEventListener(
        name,
        () => {
          try {
            bridge.jsLog(
              "WARN",
              (
                "PLAYBACK " +
                name +
                " code=" +
                (
                  media.error
                    ?.code ||
                  0
                ) +
                " time=" +
                (
                  media.currentTime ||
                  0
                )
              )
            );
          } catch (_) {}

          saveSnapshot(
            true
          );
        }
      );
    }
  );

  /*
   * ------------------------------------------------------------
   * IMPORT
   * ------------------------------------------------------------
   */

  document.addEventListener(
    "click",
    event => {
      const target =
        event.target
          ?.closest?.(
            "#homeImport," +
            "#addMusicTab," +
            "[data-addmusic]," +
            "#fileInput"
          );

      if (!target) {
        return;
      }

      event.preventDefault();
      event.stopPropagation();
      event
        .stopImmediatePropagation();

      /*
       * Android picker ашылғанда
       * background handoff жасама.
       */
      suppressHandoffUntil =
        Date.now() +
        30000;

      try {
        bridge
          .pickTracks();
      } catch (_) {}
    },
    true
  );

  /*
   * ------------------------------------------------------------
   * ROUTE / 3D REALITY / CINEMA
   * ------------------------------------------------------------
   */

  function routeLabel(
    type
  ) {
    return ({
      BUILTIN_SPEAKER:
        "ДИНАМИК",

      BUILTIN_EARPIECE:
        "ТЕЛЕФОН",

      WIRED_HEADPHONES:
        "НАУШНИКИ",

      WIRED_HEADSET:
        "ГАРНИТУРА",

      USB_DEVICE:
        "USB DAC",

      USB_HEADSET:
        "USB",

      BLUETOOTH_A2DP:
        "BLUETOOTH",

      BLE_HEADSET:
        "BLUETOOTH",

      BLE_SPEAKER:
        "BLUETOOTH",

      HEARING_AID:
        "HEARING AID",

      HDMI:
        "HDMI",

      HDMI_ARC:
        "HDMI",

      HDMI_EARC:
        "HDMI",

      AUX_LINE:
        "AUX",

      LINE_ANALOG:
        "LINE",

      LINE_DIGITAL:
        "LINE"
    })[type] ||
      "AUTO";
  }

  function refreshRoute(
    snapshot
  ) {
    let current =
      snapshot;

    if (
      !current ||
      !Array.isArray(
        current.devices
      )
    ) {
      try {
        current =
          parse(
            bridge
              .outputSnapshot(),
            {
              devices: [],
              candidate: null
            }
          );
      } catch (_) {
        current = {
          devices: [],
          candidate: null
        };
      }
    }

    spatialAllowed =
      !!(
        current.candidate &&
        current.candidate
          .spatialCapable ===
          true
      );

    window
      .__gwSpatialAllowed =
      spatialAllowed;

    document
      .querySelectorAll(
        "[data-scene='cinema']," +
        "[data-scene='reality']"
      )
      .forEach(
        node => {
          node.classList
            .toggle(
              "gw-route-locked",
              !spatialAllowed
            );
        }
      );

    const candidate =
      current.candidate;

    const key =
      candidate
        ? [
            candidate.id,
            candidate.type,
            candidate.address
          ].join("|")
        : "AUTO";

    if (
      key ===
      lastRouteKey
    ) {
      return;
    }

    lastRouteKey =
      key;

    const label =
      candidate
        ? routeLabel(
            candidate.type
          )
        : "AUTO";

    if ($("olType")) {
      $("olType")
        .textContent =
        label;
    }

    if ($("olChip")) {
      $("olChip")
        .textContent =
        "GW READY";
    }

    if ($("olDetail")) {
      $("olDetail")
        .innerHTML =
        "<div>Выход: <b>" +
        label +
        "</b></div>" +
        "<div>Режим: <b>" +
        (
          spatialAllowed
            ? "EXTERNAL"
            : "READY"
        ) +
        "</b></div>";
    }

    if ($("olLog")) {
      $("olLog")
        .innerHTML =
        "<div>Текущий выход: " +
        label +
        "</div>";
    }
  }

  try {
    realityOutputAllowed =
      () =>
        spatialAllowed;
  } catch (_) {}

  try {
    spOpen =
      () =>
        spatialAllowed;
  } catch (_) {}

  window.addEventListener(
    "gw:route-changed",
    event => {
      /*
       * MainActivity emitRoute()
       * currentOutputDevice() ғана
       * жіберуі мүмкін.
       */
      const detail =
        event.detail;

      if (
        detail &&
        detail.type
      ) {
        refreshRoute({
          devices: [
            detail
          ],
          candidate:
            detail
        });
      } else {
        refreshRoute(
          detail
        );
      }
    }
  );

  document.addEventListener(
    "click",
    event => {
      const button =
        event.target
          ?.closest?.(
            "button," +
            "[role='button']," +
            ".scene-card"
          );

      if (
        !button ||
        spatialAllowed
      ) {
        return;
      }

      const text =
        (
          button.textContent ||
          ""
        )
          .replace(
            /\s+/g,
            " "
          )
          .toLowerCase();

      if (
        !/3d\s*reality|reality sound|кино|cinema/
          .test(text)
      ) {
        return;
      }

      event.preventDefault();
      event.stopPropagation();
      event
        .stopImmediatePropagation();

      try {
        toast(
          "Требуется внешний аудиовыход"
        );
      } catch (_) {}
    },
    true
  );

  document.addEventListener(
    "click",
    event => {
      if (
        !event.target
          ?.closest?.(
            "#olRefresh"
          )
      ) {
        return;
      }

      event.preventDefault();
      event
        .stopImmediatePropagation();

      try {
        refreshRoute(
          parse(
            bridge
              .outputSnapshot(),
            {
              devices: [],
              candidate: null
            }
          )
        );
      } catch (_) {}
    },
    true
  );

  /*
   * ------------------------------------------------------------
   * HI-RES
   * ------------------------------------------------------------
   *
   * Source 24-bit metadata бөлек.
   *
   * Native output тек status()
   * нақты streamVerified + STARTED
   * болса ғана расталады.
   */

  function nativeVerified() {
    let status = {};

    try {
      status =
        parse(
          bridge.status(),
          {}
        );
    } catch (_) {
      return false;
    }

    return (
      status.ok === true &&
      status.streamVerified ===
        true &&
      status.state ===
        "STARTED" &&
      status.actualFormat &&
      status.actualFormat !==
        "NONE" &&
      Number(
        status.actualRate ||
        0
      ) > 0
    );
  }

  /*
   * nativeOpen жоқ compatibility
   * build-та toggle-ды
   * "нақты native output" деп
   * көрсетпейміз.
   *
   * HTML toggle визуалды түрде
   * қосылып/өшсе де source badge
   * пен actual output бөлек.
   */

  const hiToggle =
    $("hiResToggle");

  if (hiToggle) {
    hiToggle.title =
      nativeVerified()
        ? "GW NATIVE"
        : "SOURCE / DSP";
  }

  document.addEventListener(
    "change",
    event => {
      if (
        event.target?.id !==
        "hiResToggle"
      ) {
        return;
      }

      /*
       * Toggle-ды күшпен өшірмейміз.
       * Бірақ native output деп
       * жалған status шығармаймыз.
       */
      if (
        !nativeVerified()
      ) {
        try {
          bridge.jsLog(
            "INFO",
            "Hi-Res toggle changed; " +
            "native output is not verified"
          );
        } catch (_) {}
      }
    },
    true
  );

  /*
   * ------------------------------------------------------------
   * FALSE NATIVE STATUS PROTECTION
   * ------------------------------------------------------------
   */

  const forbidden =
    /PCM WAV играет|Hi-Res движок:\s*прямой вывод/i;

  function sanitize(
    node
  ) {
    if (
      !node ||
      nativeVerified()
    ) {
      return;
    }

    if (
      forbidden.test(
        node.textContent ||
        ""
      )
    ) {
      node.textContent =
        "GW READY";
    }
  }

  [
    "audioStatus",
    "olChip",
    "olDetail",
    "olLog"
  ].forEach(
    id => {
      const node =
        $(id);

      if (!node) {
        return;
      }

      sanitize(
        node
      );

      new MutationObserver(
        () => {
          sanitize(
            node
          );
        }
      ).observe(
        node,
        {
          childList: true,
          subtree: true,
          characterData: true
        }
      );
    }
  );

  /*
   * ------------------------------------------------------------
   * SMART EQ VOLUME PROTECTION
   * ------------------------------------------------------------
   */

  const smart =
    $("seSw");

  function protectSmartEq() {
    if (!media) {
      return;
    }

    media.volume =
      smart?.checked
        ? 0.78
        : 0.96;
  }

  smart?.addEventListener(
    "change",
    protectSmartEq
  );

  media?.addEventListener(
    "play",
    protectSmartEq
  );

  /*
   * ------------------------------------------------------------
   * RESTORE LAST TRACK
   * ------------------------------------------------------------
   */

  function restoreLocalState() {
    let state =
      null;

    try {
      state =
        parse(
          localStorage
            .getItem(
              STATE_KEY
            ),
          null
        );
    } catch (_) {}

    if (
      !state ||
      !state.trackId
    ) {
      return;
    }

    try {
      if (
        !localTracks.length
      ) {
        return;
      }

      queue =
        allTracks();

      const track =
        localTracks.find(
          item =>
            item.gwId ===
            state.trackId
        );

      if (!track) {
        return;
      }

      const index =
        queue.indexOf(
          track
        );

      if (index < 0) {
        return;
      }

      loadTrack(
        index,
        Number(
          state.positionMs ||
          0
        ) /
        1000,
        false
      );
    } catch (_) {}
  }

  /*
   * ------------------------------------------------------------
   * INITIALIZATION
   * ------------------------------------------------------------
   */

  try {
    applyLibrary(
      parse(
        bridge.library(),
        []
      )
    );
  } catch (_) {}

  installStableSeek();

  try {
    refreshRoute(
      parse(
        bridge
          .outputSnapshot(),
        {
          devices: [],
          candidate: null
        }
      )
    );
  } catch (_) {}

  /*
   * Service шынымен active болса
   * reclaim жасаймыз.
   * Болмаса local state restore.
   */
  setTimeout(
    () => {
      let background = {
        active: false
      };

      try {
        background =
          parse(
            bridge
              .backgroundState(),
            background
          );
      } catch (_) {}

      if (
        background.active
      ) {
        reclaim();
      } else {
        restoreLocalState();
      }
    },
    300
  );

  /*
   * HTML кей DOM элементтерін
   * кеш жасаса seek listener-ді
   * тағы бір рет тексереміз.
   */
  setTimeout(
    installStableSeek,
    800
  );

  try {
    bridge.jsLog(
      "INFO",
      "GW_NATIVE_JS_V5 ready"
    );
  } catch (_) {}
})();
