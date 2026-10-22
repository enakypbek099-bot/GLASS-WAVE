(() => {
  "use strict";

  if (window.__GW_NATIVE_BOOTSTRAPPED__) return;
  window.__GW_NATIVE_BOOTSTRAPPED__ = true;
  window.GW_NATIVE_ACTIVE = true;

  const bridge = window.GwBridge || null;

  function nativeLog(level, message) {
    try {
      if (bridge && typeof bridge.jsLog === "function") {
        bridge.jsLog(level, String(message));
      }
    } catch (_) {}
  }

  function nativeError(message) {
    try {
      if (bridge && typeof bridge.jsError === "function") {
        bridge.jsError(String(message));
      }
    } catch (_) {}
  }

  window.addEventListener("error", event => {
    nativeError(
      "window.error: " +
      String(event.message || "unknown") +
      " at " +
      String(event.filename || "inline") +
      ":" +
      String(event.lineno || 0) +
      ":" +
      String(event.colno || 0)
    );
  });

  window.addEventListener("unhandledrejection", event => {
    const reason = event.reason;

    nativeError(
      "unhandledrejection: " +
      (reason && reason.stack
        ? reason.stack
        : String(reason))
    );
  });

  function parse(value) {
    try {
      return JSON.parse(value);
    } catch (error) {
      return {
        ok: false,
        error: "INVALID_NATIVE_JSON",
        raw: String(value),
        parser: String(error)
      };
    }
  }

  function call(method, ...args) {
    if (!bridge || typeof bridge[method] !== "function") {
      const result = {
        ok: false,
        error: "GW_BRIDGE_UNAVAILABLE",
        method
      };

      nativeError(JSON.stringify(result));
      return result;
    }

    try {
      return parse(bridge[method](...args));
    } catch (error) {
      const result = {
        ok: false,
        error: "GW_BRIDGE_CALL_FAILED",
        method,
        details: error && error.stack
          ? error.stack
          : String(error)
      };

      nativeError(JSON.stringify(result));
      return result;
    }
  }

  window.GW_NATIVE = Object.freeze({
    open: (
      format = "I32",
      rate = 48000,
      deviceId = -1,
      exclusive = true
    ) => call(
      "openStream",
      format,
      rate,
      deviceId,
      exclusive
    ),

    start: () => call("startStream"),
    stop: () => call("stopStream"),
    close: () => call("closeStream"),
    status: () => call("status"),

    devices: () => {
      try {
        return JSON.parse(bridge.listOutputDevices());
      } catch (error) {
        nativeError("devices: " + String(error));
        return [];
      }
    },

    logs: () => {
      try {
        return bridge.runtimeLogs();
      } catch (error) {
        return String(error);
      }
    },

    copyLogs: () => {
      try {
        return bridge.copyRuntimeLogs();
      } catch (_) {
        return false;
      }
    }
  });

  /*
   * GLASS WAVE интерфейсі мен CSS өзгермейді.
   * Web Audio графының келесі құрылуы тоқтатылады.
   */
  try {
    if (typeof window.ensureAudioGraph === "function") {
      window.ensureAudioGraph = function () {
        nativeLog(
          "INFO",
          "Web Audio graph blocked; GW Native path selected"
        );
        return false;
      };
    } else {
      nativeLog(
        "WARN",
        "ensureAudioGraph was not found on window"
      );
    }
  } catch (error) {
    nativeError(
      "Web Audio guard failed: " +
      (error && error.stack ? error.stack : String(error))
    );
  }

  const initialStatus = window.GW_NATIVE.status();

  nativeLog(
    "INFO",
    "GW Native bootstrap ready: " +
    JSON.stringify(initialStatus)
  );

  window.dispatchEvent(
    new CustomEvent("gw:native-ready", {
      detail: {
        engine: "GW Native",
        strictI32: true,
        designChanged: false,
        status: initialStatus
      }
    })
  );
})();
