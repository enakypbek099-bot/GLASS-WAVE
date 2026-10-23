(() => {
  "use strict";
  if (window.__GW_QUALITY_JS_V4__) return;
  window.__GW_QUALITY_JS_V4__ = true;
  const B = window.GwBridge; if (!B || typeof B.qualityStart !== "function") return;
  const $ = id => document.getElementById(id), PREF = "gw-quality-mode-v4";
  const parse = (v,f) => { try { return JSON.parse(String(v)); } catch (_) { return f; } };
  let pref = "DSP", owner = "DSP", status = null, pollId = 0, dragging = false, pendingId = "", pendingAt = 0, transition = false, renderingPanel = false;
  try { pref = localStorage.getItem(PREF) === "QUALITY" ? "QUALITY" : "DSP"; } catch (_) {}
  try { window.__GW_DISABLE_LEGACY_HIRES?.(); } catch (_) {}

  const say = m => { try { toast(m); } catch (_) {} };
  const log = (l,m) => { try { B.jsLog(l, "QUALITY " + m); } catch (_) {} };
  const fmtMs = ms => { const s = Math.max(0, Math.floor(Number(ms || 0) / 1000)); return Math.floor(s / 60) + ":" + String(s % 60).padStart(2,"0"); };
  const isWav = t => !!t && (/^(wav|wave)$/i.test(String(t.info?.fmt || "")) || /\.wave?$/i.test(String(t.file?.name || "")));
  const current = () => { try { return currentTrack || null; } catch (_) { return null; } };
  const indexNow = () => { try { return typeof currentIndex === "number" ? currentIndex : -1; } catch (_) { return -1; } };
  function setOwner(v) { owner = v; window.__GW_QUALITY_OWNER = v === "QUALITY"; syncToggle(); }
  function savePref() { try { localStorage.setItem(PREF, pref); localStorage.setItem("neonwave-hires", "0"); } catch (_) {} }

  function silenceWeb() { const list = []; try { if (webEl) list.push(webEl); } catch (_) {} try { if (audio && !list.includes(audio)) list.push(audio); } catch (_) {}
    list.forEach(a => { try { a.pause(); a.removeAttribute("src"); a.dataset.gwTrackId = ""; a.load(); } catch (_) {} }); try { if (typeof audioCtx !== "undefined" && audioCtx?.state === "running") audioCtx.suspend().catch(() => {}); } catch (_) {} }

  function startQuality(track, positionMs, autoPlay) {
    if (!track?.gwId || !isWav(track)) return false;
    silenceWeb(); setOwner("QUALITY"); pendingId = track.gwId; pendingAt = Date.now(); transition = true;
    let ok = false; try { ok = B.qualityStart(track.gwId, Math.max(0, Math.round(positionMs || 0)), !!autoPlay, -1, true); } catch (_) {}
    if (!ok) { transition = false; setOwner("DSP"); say("GW Native іске қосылмады"); return false; }
    startPoll(); return true;
  }

  function waitStopped(done) { const began = Date.now(); const check = () => { const s = parse(B.qualityStatus(), {}); if (!s.active) { done(); return; } if (Date.now() - began < 1800) { setTimeout(check,60); return; } transition = false; say("GW Native тоқтамады; DSP іске қосылмады"); }; check(); }
  function toDsp(index, positionMs, autoPlay) {
    if (transition) return; transition = true; try { B.qualityStop(); } catch (_) {}
    waitStopped(() => { setOwner("DSP"); status = null; try { window.__GW_DISABLE_LEGACY_HIRES?.(); } catch (_) {}
      try { window.GW_DSP.play(index, Number(positionMs || 0) / 1000, !!autoPlay); } catch (_) {} transition = false; renderTruth(); });
  }

  function setPreference(on) {
    const t = current(), idx = indexNow();
    if (on && t && !isWav(t)) { pref = "DSP"; savePref(); syncToggle(); try { window.__GW_DISABLE_LEGACY_HIRES?.(); } catch (_) {} say("GW QUALITY әзірге тек WAV. Бұл FLAC DSP режимінде қалады."); renderTruth(); return; }
    pref = on ? "QUALITY" : "DSP"; savePref(); syncToggle();
    if (!on) { if (owner === "QUALITY" && idx >= 0) { const n = status?.native || {}; toDsp(idx, Number(n.positionMs || 0), n.state === "STARTED"); } renderTruth(); return; }
    if (!t) { say("GW QUALITY қосылды. WAV трегін таңда."); renderTruth(); return; }
    let pos = 0, playing = false; const m = window.GW_DSP?.media?.(); if (m) { pos = Number(m.currentTime || 0) * 1000; playing = !m.paused && !m.ended; }
    startQuality(t, pos, playing); renderTruth();
  }

  function syncTrack(id) { let list = []; try { list = allTracks(); } catch (_) {} const t = list.find(x => x?.gwId === id); if (!t) return; try { queue = list; currentIndex = list.indexOf(t); currentTrack = t; setTrackMeta(t); renderAllTracks(); } catch (_) {} }
  function startPoll() { if (!pollId) { pollId = setInterval(poll,350); poll(); } }
  function poll() {
    const s = parse(B.qualityStatus(), null); if (!s) return; status = s; if (owner !== "QUALITY") { clearInterval(pollId); pollId = 0; return; }
    if (pendingId) { const fresh = s.trackId === pendingId && Number(s.heartbeat || 0) >= pendingAt; if (fresh) { pendingId = ""; transition = false; } else if (Date.now() - pendingAt < 5000) return; else { pendingId = ""; transition = false; } }
    if (s.qualityError) { const code = s.qualityError; pref = "DSP"; savePref(); setOwner("DSP"); status = null; say("GW Native қатесі: " + code); renderTruth(); return; }
    if (s.trackId && current()?.gwId !== s.trackId) syncTrack(s.trackId); paintTransport(s); renderTruth();
  }

  function paintTransport(s) { const n = s.native || {}, pos = Number(n.positionMs || 0), dur = Number(n.durationMs || 0), playing = n.state === "STARTED", pct = dur > 0 ? Math.min(100,100*pos/dur) : 0;
    const bar = $("seekBar"); if (bar && !dragging && dur > 0) bar.value = pct; const e = $("elapsed"); if (e && !dragging) e.textContent = fmtMs(pos); const d = $("duration"); if (d && dur > 0) d.textContent = fmtMs(dur);
    const mp = $("miniProgress"); if (mp) mp.style.width = pct + "%"; const nc = $("npCur"); if (nc && !dragging) nc.textContent = fmtMs(pos); const nd = $("npDur"); if (nd && dur > 0) nd.textContent = fmtMs(dur);
    document.body.classList.toggle("is-playing", playing); try { if (typeof iconSvg === "function") { const icon = playing ? "pause" : "play"; if ($("miniPlay")) $("miniPlay").innerHTML = iconSvg(icon,18); if ($("bigPlay")) $("bigPlay").innerHTML = iconSvg(icon,25); } } catch (_) {} }

  function syncToggle() { const sw = $("hiResToggle"); if (sw) sw.checked = owner === "QUALITY"; }
  function line(k,v) { return `<div class="gwq-line"><span>${k}</span><b>${v}</b></div>`; }
  function sourceText() { const n = status?.native; if (owner === "QUALITY" && n?.nativeWav) return `${n.sourceFormat} · ${(Number(n.sourceRate)/1000).toFixed(1)} кГц · ${n.sourceBits} бит · ${n.sourceChannels} кан.`; return current()?.info?.label || "—"; }
  function outputText() { const n = status?.native; if (owner !== "QUALITY") return "GLASS WAVE DSP · Web Audio"; if (!n?.ok) return "—"; return `${n.audioApi} · ${n.actualFormat} · ${n.actualRate} Гц · ${n.actualChannels} кан. · ${n.actualSharing} · dev ${n.actualDeviceId}`; }
  function makeCard() { const c = document.createElement("div"); c.id = "gwQualityTruth"; c.innerHTML = '<div class="gwq-title">GW NATIVE · QUALITY</div><div data-gwq-body></div>'; return c; }

  function neutralizeMainCard() { const sw = $("hiResToggle"), host = sw?.closest?.(".settings-card"); if (!host) return; const kicker = host.querySelector(".settings-kicker"); if (kicker) kicker.textContent = "NATIVE OUTPUT"; const title = host.querySelector("h2,h3"); if (title) title.textContent = "GW Native · QUALITY"; const p = host.querySelector("p"); if (p) p.textContent = "WAV: GW Native → Oboe → AAudio → strict I32. FLAC/MP3/AAC/OGG: DSP режимі; EQ, Tone, Bass, 3D, 8D және Lab сақталады."; const open = $("hrOpen"); if (open) open.textContent = "GW Native SOURCE / OUTPUT ›"; }
  function renderTruth() {
    if (renderingPanel) return; renderingPanel = true;
    neutralizeMainCard(); const tabs = $("hrTabs"); if (tabs) tabs.style.display = "none";
    const body = $("hrBody"); if (body) { let card = $("gwQualityTruth"); if (!card || card.parentElement !== body) { card = makeCard(); body.replaceChildren(card); }
      const n = status?.native || {}, box = card.querySelector("[data-gwq-body]");
      box.innerHTML = line("Режим", owner === "QUALITY" ? "QUALITY · GW Native" : "DSP · GLASS WAVE") + line("SOURCE",sourceText()) + line("OUTPUT",outputText()) +
        (owner === "QUALITY" ? line("Күй",n.state || "OPENING") + line("Растау",n.streamVerified === true ? "I32 + source rate ✓" : "—") + (n.error ? line("Қате",n.error) : "") : "") +
        '<small>I32 — қолданба stream форматы. Ол DAC bit-depth немесе bit-perfect output кепілі емес. QUALITY әзірге тек WAV.</small>';
    }
    const title = $("hrTitle"); if (title) title.textContent = "GW Native";
    const audioStatus = $("audioStatus"); if (audioStatus) audioStatus.textContent = owner === "QUALITY" ? (status?.native?.streamVerified === true ? `GW NATIVE · ${status.native.actualRate} Hz · I32` : "GW NATIVE · OPENING") : (current()?.info?.label || "GLASS WAVE DSP");
    syncToggle(); renderingPanel = false;
  }

  const css = document.createElement("style"); css.textContent = `#gwQualityTruth{padding:14px;border:1px solid rgba(255,255,255,.14);border-radius:16px;background:rgba(255,255,255,.055);font:12px/1.5 system-ui;color:inherit}.gwq-title{font-weight:900;letter-spacing:1px;margin-bottom:9px}.gwq-line{display:flex;justify-content:space-between;gap:12px;padding:4px 0}.gwq-line span{opacity:.58}.gwq-line b{text-align:right}#gwQualityTruth small{display:block;opacity:.55;margin-top:10px}`; document.head.appendChild(css);

  window.addEventListener("change", e => { if (e.target?.id !== "hiResToggle") return; e.stopImmediatePropagation(); e.preventDefault(); setPreference(!!e.target.checked); }, true);
  window.addEventListener("click", e => { if (e.target?.closest?.("#hrOpen")) setTimeout(renderTruth,0); }, true);

  let basePlay = null; try { basePlay = playTrack; } catch (_) {}
  if (typeof basePlay === "function") playTrack = function(index) { let list = []; try { list = queue?.length ? queue : allTracks(); } catch (_) {} const t = list[index];
    if (pref === "QUALITY" && isWav(t) && t?.gwId) { try { queue = list; currentIndex = index; currentTrack = t; setTrackMeta(t); } catch (_) {} if (startQuality(t,0,true)) return; }
    if (owner === "QUALITY") { toDsp(index,0,true); return; }
    if (pref === "QUALITY" && t && !isWav(t)) { pref = "DSP"; savePref(); syncToggle(); say("Бұл формат GLASS WAVE DSP режимінде ойнайды."); }
    basePlay(index);
  };

  window.addEventListener("click", e => { if (owner !== "QUALITY") return; const play = e.target?.closest?.("#bigPlay,#miniPlay,#npPlay"), next = e.target?.closest?.("#nextBtn,[data-px='nextBtn']"), prev = e.target?.closest?.("#prevBtn,[data-px='prevBtn']"); if (!play && !next && !prev) return; e.preventDefault(); e.stopImmediatePropagation(); if (next) B.qualityNext(); else if (prev) B.qualityPrevious(); else { const n = (status || parse(B.qualityStatus(),{})).native || {}; if (n.state === "STARTED") B.qualityPause(); else B.qualityPlay(); } setTimeout(poll,100); }, true);

  let seekValue = null, seekCommitted = false;
  ["pointerdown","touchstart","mousedown","input","change","pointerup","touchend","mouseup","pointercancel"].forEach(name => window.addEventListener(name,e => { if (owner !== "QUALITY" || e.target?.id !== "seekBar") return; e.stopImmediatePropagation(); e.preventDefault(); const dur = Number(status?.native?.durationMs || 0);
    if (["pointerdown","touchstart","mousedown"].includes(name)) { dragging = true; seekCommitted = false; }
    else if (name === "input") { dragging = true; seekValue = dur * Number(e.target.value || 0) / 100; const el = $("elapsed"); if (el && dur > 0) el.textContent = fmtMs(seekValue); }
    else if (["change","pointerup","touchend","mouseup"].includes(name) && !seekCommitted) { if (dur > 0) { seekCommitted = true; const value = seekValue == null ? dur * Number(e.target.value || 0) / 100 : seekValue; B.qualitySeek(Math.round(value)); } setTimeout(() => { dragging = false; seekValue = null; poll(); },250); }
    else if (name === "pointercancel") dragging = false;
  },true));

  const initial = parse(B.qualityStatus(),{}); if (initial.active && initial.engine === "GW_NATIVE" && !initial.qualityError) { pref = "QUALITY"; savePref(); setOwner("QUALITY"); status = initial; startPoll(); syncTrack(initial.trackId); } else { pref = "DSP"; savePref(); setOwner("DSP"); }
  neutralizeMainCard(); renderTruth();
  window.setEngine = on => setPreference(!!on);
  window.GW_QUALITY = Object.freeze({ mode: () => ({pref,owner}), status: () => status });
})();
