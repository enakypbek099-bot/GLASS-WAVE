(() => {
  "use strict";

  if (window.__GW_NATIVE_V4__) return;
  window.__GW_NATIVE_V4__ = true;

  const bridge = window.GwBridge || null;
  const $ = id => document.getElementById(id);

  function parse(value, fallback = {}) {
    try {
      return JSON.parse(String(value));
    } catch (_) {
      return fallback;
    }
  }

  function log(message) {
    try {
      if (bridge && typeof bridge.jsLog === "function") {
        bridge.jsLog("INFO", String(message));
      }
    } catch (_) {}
  }

  function showMessage(message) {
    try {
      if (typeof window.toast === "function") {
        window.toast(message);
        return;
      }
    } catch (_) {}

    const node = $("toast");
    if (!node) return;

    node.textContent = message;
    node.classList.add("show");

    clearTimeout(window.__GW_MESSAGE_TIMER__);
    window.__GW_MESSAGE_TIMER__ = setTimeout(
      () => node.classList.remove("show"),
      1800
    );
  }

  function installUiFix() {
    document.getElementById("gw-ui-stability")?.remove();

    const style = document.createElement("style");
    style.id = "gw-ui-stability";
    style.textContent = `
      #settingsScreen .settings-tabs {
        position: relative !important;
        top: auto !important;
        z-index: 2 !important;
      }

      #settingsScreen .settings-panel {
        animation: none !important;
      }
    `;

    document.head.appendChild(style);

    /*
     * Ескі effect gate Settings ашылғаннан кейін
     * барлық animation-ды қайта қосып, визуалды секіріс туғызады.
     */
    document.getElementById("nw-fx-pause")?.remove();

    document.documentElement.classList.remove("nw-fx-off");
    document.documentElement.classList.add("nw-fx-on");

    try {
      window.nwFx = true;
    } catch (_) {}
  }

  function restoreSignalAnalysis() {
    /*
     * Соңғы патчтағы ensureAudioGraph override енді жоқ.
     * Түпнұсқа GLASS WAVE анализ және DSP графы қайта жұмыс істейді.
     */
    try {
      if (typeof window.setEngine === "function") {
        window.setEngine(false, true);
      } else if (typeof setEngine === "function") {
        setEngine(false, true);
      }
    } catch (error) {
      log("Signal restore failed: " + error);
    }

    try {
      if (typeof window.hiRes !== "undefined") {
        window.hiRes = false;
      }
    } catch (_) {}

    try {
      if (typeof hiRes !== "undefined") {
        hiRes = false;
      }
    } catch (_) {}

    try {
      localStorage.setItem("neonwave-hires", "0");
    } catch (_) {}
  }

  function prepareNativeCard() {
    const open = $("hrOpen");
    const card = open && open.closest(".settings-card");

    if (!card) return;

    const kicker = card.querySelector(".settings-kicker");
    const heading = card.querySelector("h3");
    const description = card.querySelector("p");
    const toggle = $("hiResToggle");

    if (kicker) {
      kicker.textContent = "GW NATIVE · HI-RES ENGINE";
    }

    if (heading) {
      heading.textContent = "GW Native";
    }

    if (description) {
      description.textContent =
        "Точный режим вывода и нативная обработка будут доступны " +
        "после подключения аудиодвижка.";
    }

    if (open) {
      open.textContent = "Состояние GW Native ›";
    }

    /*
     * Жалған белсенді toggle көрсетпейміз.
     * Native PCM қосылғанда ғана қайта ашылады.
     */
    if (toggle) {
      toggle.checked = false;
      toggle.disabled = true;
      toggle.setAttribute("aria-disabled", "true");
      toggle.title = "GW Native готовится к подключению";
    }
  }

  function escapeHtml(value) {
    return String(value).replace(/[&<>"']/g, char => ({
      "&": "&amp;",
      "<": "&lt;",
      ">": "&gt;",
      "\"": "&quot;",
      "'": "&#39;"
    })[char]);
  }

  function currentRoute() {
    try {
      if (
        bridge &&
        typeof bridge.currentOutputDevice === "function"
      ) {
        return parse(
          bridge.currentOutputDevice(),
          { ok: false }
        );
      }
    } catch (_) {}

    return { ok: false };
  }

  function renderRoute() {
    const route = currentRoute();

    const name =
      route.ok && route.name
        ? route.name
        : "AUTO";

    const type =
      route.ok && route.type
        ? route.type
        : "AUTO";

    if ($("olChip")) {
      $("olChip").textContent =
        route.ok ? "GW READY" : "AUTO";
    }

    if ($("olType")) {
      $("olType").textContent = name;
    }

    if ($("olRate")) {
      $("olRate").textContent = "—";
    }

    if ($("olBits")) {
      $("olBits").textContent = "—";
    }

    if ($("olDetail")) {
      $("olDetail").innerHTML =
        "<div>Выход: <b>" +
        escapeHtml(name) +
        "</b></div>" +
        "<div>Маршрут: <b>" +
        escapeHtml(type) +
        "</b></div>" +
        "<div>Режим: <b>DSP</b></div>";
    }
  }

  function ownNativeControls() {
    document.addEventListener(
      "change",
      event => {
        if (
          !event.target ||
          event.target.id !== "hiResToggle"
        ) {
          return;
        }

        event.preventDefault();
        event.stopPropagation();
        event.stopImmediatePropagation();

        event.target.checked = false;

        showMessage(
          "GW Native готовится к подключению"
        );
      },
      true
    );

    document.addEventListener(
      "click",
      event => {
        const target =
          event.target?.closest?.(
            "#hrOpen,#olRefresh"
          );

        if (!target) return;

        event.preventDefault();
        event.stopPropagation();
        event.stopImmediatePropagation();

        renderRoute();

        if (target.id === "hrOpen") {
          $("outLogCard")?.scrollIntoView({
            behavior: "smooth",
            block: "center"
          });
        }
      },
      true
    );
  }

  installUiFix();
  restoreSignalAnalysis();
  prepareNativeCard();
  ownNativeControls();
  renderRoute();

  /*
   * Ескі output detector енді техникалық немесе
   * қате мәндерді қайта жазбайды.
   */
  try {
    window.detect = renderRoute;
  } catch (_) {}

  try {
    detect = renderRoute;
  } catch (_) {}

  window.GW_NATIVE = Object.freeze({
    status: () => {
      try {
        return bridge &&
          typeof bridge.status === "function"
          ? parse(bridge.status(), {})
          : {};
      } catch (_) {
        return {};
      }
    },

    route: currentRoute,

    devices: () => {
      try {
        return bridge &&
          typeof bridge.listOutputDevices === "function"
          ? parse(
              bridge.listOutputDevices(),
              []
            )
          : [];
      } catch (_) {
        return [];
      }
    }
  });

  window.dispatchEvent(
    new CustomEvent("gw:compat-ready", {
      detail: {
        visualDesign: "preserved",
        signalAnalysis: "compatible",
        nativePlayback: false
      }
    })
  );
})();
