(() => {
  "use strict";

  if (window.__GW_STAGE_BACKGROUND__) return;
  window.__GW_STAGE_BACKGROUND__ = true;

  const bridge = window.GwBridge;
  const $ = id => document.getElementById(id);

  let tracks = [];
  let currentIndex = -1;
  let lastSnapshotAt = 0;
  let backgroundHandoff = false;

  const parse = (value, fallback) => {
    try {
      return JSON.parse(String(value));
    } catch (_) {
      return fallback;
    }
  };

  const text = (id, value) => {
    const node = $(id);
    if (node) node.textContent = String(value);
  };

  function installStyle() {
    const style = document.createElement("style");

    style.textContent = `
      #settingsScreen .settings-tabs{
        position:relative!important;
        top:auto!important
      }

      #settingsScreen .settings-panel{
        animation:none!important
      }

      #localTracks{
        display:grid!important;
        grid-template-columns:
          repeat(auto-fit,minmax(145px,1fr));
        gap:14px!important
      }

      .gw-cover-card{
        min-width:0;
        border:1px solid rgba(225,233,238,.34);
        border-radius:28px;
        padding:12px;
        background:rgba(75,94,108,.24);
        box-shadow:
          inset 0 1px rgba(255,255,255,.34);
      }

      .gw-cover{
        aspect-ratio:1/1;
        border-radius:22px;
        display:grid;
        place-items:center;
        overflow:hidden;
        background:
          radial-gradient(
            circle at 28% 25%,
            rgba(247,233,192,.94),
            transparent 22%
          ),
          radial-gradient(
            circle at 72% 68%,
            rgba(100,166,181,.72),
            transparent 30%
          ),
          linear-gradient(
            135deg,
            #262337,
            #344b59 55%,
            #101922
          );
        color:#fff3ce;
        font-size:42px;
        text-shadow:0 5px 22px #000;
      }

      .gw-cover-title{
        margin:12px 4px 0;
        color:#f7f8fb;
        font-weight:800;
        font-size:15px;
        overflow:hidden;
        white-space:nowrap;
        text-overflow:ellipsis
      }

      .gw-cover-artist{
        margin:5px 4px 4px;
        color:#bdc5cd;
        font-size:12px;
        overflow:hidden;
        white-space:nowrap;
        text-overflow:ellipsis
      }

      body.gw-idle #rr8dBox,
      body.gw-idle #sceneCanvas,
      body.gw-ui-sleep #rr8dBox,
      body.gw-ui-sleep #sceneCanvas{
        animation-play-state:paused!important
      }

      body.gw-ui-sleep *{
        animation-play-state:paused!important
      }

      .settings-card,
      .gw-cover-card{
        content-visibility:auto;
        contain-intrinsic-size:220px
      }
    `;

    document.head.appendChild(style);

    document.getElementById(
      "nw-fx-pause"
    )?.remove();

    document.documentElement
      .classList.remove("nw-fx-off");

    document.documentElement
      .classList.add("nw-fx-on");
  }

  /*
   * Басты бетке жаңа тізім салынбайды.
   * GLASS_WAVE-тың өз artwork панелі сақталады.
   */
  function renderLibrary() {
    const host = $("localTracks");
    if (!host) return;

    host.innerHTML = "";

    tracks.forEach((track, index) => {
      const card = document.createElement("div");
      card.className = "gw-cover-card";

      const cover = document.createElement("div");
      cover.className = "gw-cover";
      cover.textContent = "♫";

      const title = document.createElement("div");
      title.className = "gw-cover-title";
      title.textContent =
        track.title || track.name || "Аудиотрек";

      const artist = document.createElement("div");
      artist.className = "gw-cover-artist";
      artist.textContent =
        track.artist || "Неизвестный исполнитель";

      card.append(cover, title, artist);
      card.addEventListener(
        "click",
        () => playAt(index)
      );

      host.appendChild(card);
    });

    text("trackCount", tracks.length);

    text(
      "artistCount",
      new Set(
        tracks.map(track => track.artist)
          .filter(Boolean)
      ).size
    );

    text(
      "albumCount",
      new Set(
        tracks.map(track => track.album)
          .filter(Boolean)
      ).size
    );
  }

  function replaceLibrary(value) {
    tracks = Array.isArray(value) ? value : [];
    renderLibrary();
  }

  window.GW_LIBRARY = Object.freeze({
    replace: replaceLibrary,
    tracks: () => tracks.slice(),
    play: index => playAt(index)
  });

  window.addEventListener(
    "gw:library-changed",
    event => replaceLibrary(event.detail)
  );

  function sourceFor(track) {
    return (
      "https://appassets.androidplatform.net/" +
      "tracks/" +
      encodeURIComponent(track.id) +
      "/" +
      encodeURIComponent(
        track.name || "audio"
      )
    );
  }

  function updateSnapshot(force = false) {
    const audio = $("audio");
    const track = tracks[currentIndex];

    if (!audio || !track) return;

    const now = Date.now();

    if (!force && now - lastSnapshotAt < 4000) {
      return;
    }

    lastSnapshotAt = now;

    try {
      bridge.updatePlaybackSnapshot(
        track.id,
        Math.round(audio.currentTime * 1000),
        !audio.paused && !audio.ended
      );
    } catch (_) {}
  }

  function syncIdle() {
    const audio = $("audio");

    document.body.classList.toggle(
      "gw-idle",
      !audio ||
      audio.paused ||
      audio.ended ||
      audio.readyState < 2
    );
  }

  function playAt(index, positionSeconds = 0) {
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

    audio.pause();
    audio.src = sourceFor(track);
    audio.preload = "auto";
    audio.load();

    const title =
      track.title || track.name || "Аудиотрек";

    const artist =
      track.artist || "Неизвестный исполнитель";

    [
      "miniTitle",
      "playerTitle",
      "npTitle"
    ].forEach(id => text(id, title));

    [
      "miniSub",
      "playerSub",
      "npArtist"
    ].forEach(id => text(id, artist));

    audio.addEventListener(
      "loadedmetadata",
      () => {
        if (
          Number.isFinite(positionSeconds) &&
          positionSeconds > 0
        ) {
          audio.currentTime =
            Math.min(
              positionSeconds,
              Math.max(
                0,
                audio.duration - 0.2
              )
            );
        }
      },
      { once: true }
    );

    audio.play()
      .then(() => {
        backgroundHandoff = false;
        syncIdle();
        updateSnapshot(true);
      })
      .catch(error => {
        bridge.jsError(
          "Playback failed: " +
          String(error)
        );
      });
  }

  const audio = $("audio");

  audio?.addEventListener(
    "timeupdate",
    () => updateSnapshot(false)
  );

  ["play", "playing", "pause", "ended", "error"]
    .forEach(eventName => {
      audio?.addEventListener(
        eventName,
        () => {
          syncIdle();
          updateSnapshot(true);
        }
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

  function handoffToBackground() {
    if (
      backgroundHandoff ||
      !audio ||
      audio.paused ||
      currentIndex < 0
    ) {
      return;
    }

    const track = tracks[currentIndex];
    if (!track) return;

    const started =
      bridge.startBackgroundPlayback(
        track.id,
        Math.round(audio.currentTime * 1000)
      );

    if (started) {
      backgroundHandoff = true;
      audio.pause();
    }
  }

  function claimFromBackground() {
    const state = parse(
      bridge.claimForegroundPlayback(),
      { active: false }
    );

    if (!state.active || !state.trackId) {
      return;
    }

    const index = tracks.findIndex(
      track => track.id === state.trackId
    );

    if (index < 0) return;

    currentIndex = index;

    if (state.isPlaying) {
      playAt(
        index,
        Number(state.positionMs || 0) / 1000
      );
    }
  }

  document.addEventListener(
    "visibilitychange",
    () => {
      document.body.classList.toggle(
        "gw-ui-sleep",
        document.hidden
      );

      if (document.hidden) {
        handoffToBackground();
      } else {
        claimFromBackground();
      }
    }
  );

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

  /*
   * Hidden WebView-де ауыр визуал frame-дер
   * 5 FPS шамасына түседі.
   */
  const nativeRaf =
    window.requestAnimationFrame.bind(window);

  window.requestAnimationFrame =
    callback => {
      return nativeRaf(timestamp => {
        if (document.hidden) {
          setTimeout(
            () => window.requestAnimationFrame(
              callback
            ),
            200
          );
        } else {
          callback(timestamp);
        }
      });
    };

  /*
   * GW Native әлі PCM алмайды:
   * үнсіз native stream ашық қалмайды.
   */
  try {
    bridge.closeStream();
  } catch (_) {}

  const hiRes = $("hiResToggle");

  if (hiRes) {
    hiRes.checked = false;
    hiRes.disabled = true;
    hiRes.title =
      "GW Native келесі PCM кезеңінде қосылады";
  }

  installStyle();
  syncIdle();

  replaceLibrary(
    parse(
      bridge.library(),
      []
    )
  );

  /*
   * Activity процессі қайта құрылған болса,
   * service-тегі трек foreground-қа қайтады.
   */
  claimFromBackground();
})();
