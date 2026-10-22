(() => {
  "use strict";

  if (window.__GW_NATIVE_BOOTSTRAPPED__) return;
  window.__GW_NATIVE_BOOTSTRAPPED__ = true;
  window.GW_NATIVE_ACTIVE = true;

  const bridge = window.GwBridge;

  const parse = value => {
    try { return JSON.parse(value); }
    catch (_) { return { ok: false, error: String(value) }; }
  };

  const api = Object.freeze({
    open: (format = "I32", rate = 48000, deviceId = -1, exclusive = true) =>
      parse(bridge.openStream(format, rate, deviceId, exclusive)),
    start: () => parse(bridge.startStream()),
    stop: () => parse(bridge.stopStream()),
    close: () => parse(bridge.closeStream()),
    status: () => parse(bridge.status()),
    devices: () => parse(bridge.listOutputDevices()),
    logs: () => parse(bridge.logs())
  });

  window.GW_NATIVE = api;

  /* Web Audio графын құрмаймыз. UI мен оның стильдері сақталады. */
  try {
    if (typeof window.ensureAudioGraph === "function") {
      window.ensureAudioGraph = () => false;
    }
  } catch (_) {}

  const $ = id => document.getElementById(id);

  function formatBits(name) {
    return {
      I16: "16",
      I24: "24",
      I32: "32 INT",
      FLOAT: "32 FLOAT"
    }[name] || "—";
  }

  function showStatus(status) {
    if (!status) return;

    const chip = $("olChip");
    const type = $("olType");
    const rate = $("olRate");
    const bits = $("olBits");
    const detail = $("olDetail");
    const log = $("olLog");

    if (chip) chip.textContent = status.ok ? "GW NATIVE" : "GW ERROR";
    if (type) type.textContent =
      status.deviceId >= 0 ? `ID ${status.deviceId}` : "AUTO";
    if (rate) rate.textContent =
      status.actualRate ? (status.actualRate / 1000).toFixed(1) : "—";
    if (bits) bits.textContent = formatBits(status.actualFormat);

    if (detail) {
      detail.textContent =
        `Запрошено: ${status.requestedFormat} / ${status.requestedRate} Hz / ` +
        `${status.requestedSharing}. Фактически: ` +
        `${status.actualFormat} / ${status.actualRate} Hz / ` +
        `${status.actualSharing} / ${status.api}. ` +
        `Состояние: ${status.state}.`;
    }

    if (log) {
      const line = document.createElement("div");
      line.textContent = status.error
        ? `GW Native: ${status.error}`
        : `GW Native: ${status.state}, xRun=${status.xRun}`;
      log.prepend(line);
      while (log.children.length > 20) log.lastElementChild.remove();
    }

    window.dispatchEvent(
      new CustomEvent("gw:native-status", { detail: status })
    );
  }

  function prepareCard() {
    const open = $("hrOpen");
    const card = open && open.closest(".settings-card");

    if (card) {
      const heading = card.querySelector("h3");
      const text = card.querySelector("p");
      if (heading) heading.textContent = "GW Native";
      if (text) {
        text.textContent =
          "Нативный вывод Oboe / AAudio. QUALITY использует строгий PCM I32; " +
          "Float32 выбирается отдельно.";
      }
    }

    if (open) open.textContent = "Проверить GW Native ›";
  }

  document.addEventListener("change", event => {
    if (event.target && event.target.id !== "hiResToggle") return;
    if (!event.target) return;

    event.stopImmediatePropagation();

    if (event.target.checked) {
      const result = api.open("I32", 48000, -1, true);
      showStatus(result);
      if (result.ok) showStatus(api.start());
    } else {
      showStatus(api.stop());
      showStatus(api.close());
    }
  }, true);

  document.addEventListener("click", event => {
    const id = event.target && event.target.id;
    if (id !== "hrOpen" && id !== "olRefresh") return;

    event.preventDefault();
    event.stopImmediatePropagation();
    showStatus(api.status());
  }, true);

  prepareCard();
  showStatus(api.status());

  window.dispatchEvent(new CustomEvent("gw:native-ready", {
    detail: {
      engine: "GW Native",
      stage: 1,
      nativeDecoder: false,
      dsp: false,
      strictI32: true,
      float32Separate: true
    }
  }));
})();
