(() => {
  "use strict";
  if (window.__GW_NATIVE_JS_V8__) return;
  window.__GW_NATIVE_JS_V8__ = true;

  const B = window.GwBridge;
  const $ = id => document.getElementById(id);
  const STATE = "gw-playback-state-v8";
  let stored = [], renderTimer = 0, pendingLibrary = null, signature = "";
  let handoffBusy = false, handoffActive = false, suppressHandoffUntil = 0, lastSave = 0;
  let spatialAllowed = false, lastRoute = "";
  const parse = (v, f) => { try { return JSON.parse(String(v)); } catch (_) { return f; } };
  const ext = n => String(n || "AUDIO").split(".").pop().toUpperCase();
  const sourceFor = t => "https://appassets.androidplatform.net/tracks/" + encodeURIComponent(t.gwId) + "/" + encodeURIComponent(t.file?.name || "audio");

  const style = document.createElement("style");
  style.id = "gw-android-ui-v8";
  style.textContent = `html,body,button,a,label,input,[role="button"],.tab,.nav-item,.music-card,.track-row{-webkit-tap-highlight-color:transparent!important}
  button:focus:not(:focus-visible),a:focus:not(:focus-visible),input:focus:not(:focus-visible),[role="button"]:focus:not(:focus-visible){outline:none!important;box-shadow:none!important}
  button:focus-visible,a:focus-visible,input:focus-visible,[role="button"]:focus-visible{outline:2px solid rgba(244,234,208,.88)!important;outline-offset:3px!important}.gw-route-locked{opacity:.46!important;filter:saturate(.35)!important}`;
  document.head.appendChild(style);

  function toTrack(raw) {
    const sec = Number(raw.durationMs || 0) / 1000, verified = raw.sourceVerified === true;
    const sr = Number(raw.sampleRate || 0), bits = Number(raw.bitsPerSample || 0), fmtName = ext(raw.name);
    return { gwId: raw.id, title: raw.title || raw.name || "Аудиотрек", artist: raw.artist || "Неизвестный автор",
      album: raw.album || "Без альбома", time: typeof fmt === "function" ? fmt(sec) : "--:--", art: "vinyl", playlist: "local",
      duration: sec, local: true, file: { name: raw.name || "audio", size: Number(raw.size || 0), lastModified: 0 },
      coverUrl: raw.coverUrl || null,
      info: { fmt: fmtName, sr, bits, ch: Number(raw.channels || 0), verified,
        hi: verified && bits > 16 && sr >= 44100,
        label: verified ? `${fmtName} · ${(sr / 1000).toFixed(1)} кГц · ${bits} бит` : fmtName } };
  }

  function truthfulBadge(t) {
    const i = t?.info; if (!i) return "";
    const flac = i.fmt === "FLAC" ? ' <span class="flac-tag">FLAC</span>' : "";
    const hi = i.verified === true && Number(i.bits) > 16 && Number(i.sr) >= 44100;
    return flac + (hi ? ' <span class="hr3d" title="Hi-Res source"><i>Hi-Res</i><b>SOURCE</b></span>' : "");
  }
  try { hrBadge = truthfulBadge; } catch (_) {}
  try { engineLabel = t => t?.info?.label || "GLASS WAVE"; } catch (_) {}

  function sig(list) { return list.map(x => [x.id,x.title,x.artist,x.durationMs,x.coverUrl,x.sampleRate,x.bitsPerSample,x.sourceVerified].join("|")).join(";"); }
  function applyLibrary(list) {
    list = Array.isArray(list) ? list : []; const nextSig = sig(list); if (nextSig === signature) return; signature = nextSig; stored = list;
    const activeId = typeof currentTrack !== "undefined" && currentTrack ? currentTrack.gwId : "";
    try { localTracks = list.map(toTrack); queue = allTracks(); } catch (_) { return; }
    if (activeId) { const fresh = localTracks.find(t => t.gwId === activeId); if (fresh) { currentTrack = fresh; currentIndex = queue.indexOf(fresh); try { setTrackMeta(fresh); } catch (_) {} } }
    try { renderAllTracks(); } catch (_) {}
  }
  function replaceLibrary(list) { pendingLibrary = Array.isArray(list) ? list : []; clearTimeout(renderTimer); renderTimer = setTimeout(() => { const n=pendingLibrary; pendingLibrary=null; applyLibrary(n); }, 350); }
  window.GW_LIBRARY = Object.freeze({ replace: replaceLibrary, tracks: () => stored.slice() });
  window.addEventListener("gw:library-changed", e => replaceLibrary(e.detail));

  function save(force) {
    if (window.__GW_QUALITY_OWNER === true) return;
    if (typeof currentTrack === "undefined" || !currentTrack?.gwId || typeof audio === "undefined" || !audio) return;
    const now=Date.now(); if(!force && now-lastSave<1500)return; lastSave=now;
    const positionMs=Math.max(0,Math.round((audio.currentTime||0)*1000));
    try { B.updatePlaybackSnapshot(currentTrack.gwId,positionMs,!audio.paused&&!audio.ended); } catch (_) {}
    try { localStorage.setItem(STATE,JSON.stringify({trackId:currentTrack.gwId,positionMs,paused:audio.paused})); } catch (_) {}
  }

  function loadTrack(index, positionSec, autoPlay) {
    try { if (!queue.length) queue = allTracks(); } catch (_) { return; }
    if (index < 0 || index >= queue.length) return; const t=queue[index];
    const same=typeof currentTrack!=="undefined"&&currentTrack?.gwId===t.gwId&&typeof audio!=="undefined"&&audio?.dataset?.gwTrackId===t.gwId;
    currentIndex=index; currentTrack=t; try { setTrackMeta(t); } catch (_) {}
    try { audio=webEl; } catch (_) { return; }
    if(!same){ try{audio.pause();}catch(_){} audio.dataset.gwTrackId=t.gwId; audio.src=sourceFor(t); audio.preload="auto"; try{audio.load();}catch(_){} }
    const wanted=Number(positionSec||0), doSeek=()=>{if(wanted>0&&Number.isFinite(audio.duration))try{audio.currentTime=Math.min(wanted,Math.max(0,audio.duration-.25));}catch(_){}};
    if(audio.readyState>=1)doSeek();else audio.addEventListener("loadedmetadata",doSeek,{once:true});
    try{ensureAudioGraph();if(typeof audioCtx!=="undefined"&&audioCtx?.state==="suspended")audioCtx.resume().catch(()=>{});}catch(_){}
    if(autoPlay){try{B.prepareBackgroundPlayback();}catch(_){} audio.play().catch(()=>{try{toast("Не удалось запустить трек.");}catch(_){}});}
    try{renderAllTracks();updatePlayButtons();}catch(_){} save(true);
  }
  try { playTrack = i => loadTrack(i,0,true); } catch (_) {}

  function installSeek(){
    const bar=$("seekBar"),media=$("audio"); if(!bar||!media||bar.dataset.gwSeekV8)return; bar.dataset.gwSeekV8="1";
    let dragging=false,resume=false,target=null;
    const begin=()=>{dragging=true;resume=!media.paused&&!media.ended;};
    const preview=()=>{if(!Number.isFinite(media.duration)||media.duration<=0)return;target=media.duration*Number(bar.value||0)/100;const e=$("elapsed");if(e&&typeof fmt==="function")e.textContent=fmt(target);};
    const commit=()=>{if(target===null||!Number.isFinite(media.duration)){dragging=false;return;}const at=Math.max(0,Math.min(target,Math.max(0,media.duration-.05))),again=resume;target=null;dragging=false;resume=false;
      let done=false;const finish=()=>{if(done)return;done=true;media.removeEventListener("seeked",finish);if(again)media.play().catch(()=>{});save(true);};
      media.addEventListener("seeked",finish,{once:true});try{media.currentTime=at;}catch(_){finish();}setTimeout(finish,1200);};
    bar.addEventListener("pointerdown",begin,true);bar.addEventListener("touchstart",begin,{capture:true,passive:true});
    document.addEventListener("input",e=>{if(e.target!==bar||window.__GW_QUALITY_OWNER===true)return;e.stopPropagation();e.stopImmediatePropagation();if(!dragging)begin();preview();},true);
    ["change","pointerup","pointercancel","touchend"].forEach(n=>bar.addEventListener(n,()=>{if(window.__GW_QUALITY_OWNER!==true)commit();},true));
  }

  function handoff(){
    if(window.__GW_QUALITY_OWNER===true||handoffBusy||handoffActive||Date.now()<suppressHandoffUntil||typeof currentTrack==="undefined"||!currentTrack?.gwId||typeof audio==="undefined"||!audio||audio.paused)return;
    save(true);const id=currentTrack.gwId,ms=Math.max(0,Math.round((audio.currentTime||0)*1000));let ok=false;try{ok=B.startBackgroundPlayback(id,ms);}catch(_){}if(!ok)return;
    handoffBusy=true;let webPaused=false;const started=Date.now();
    const check=()=>{const s=parse(B.backgroundState(),{});const serviceOwns=s.active&&s.trackId===id&&s.engine!=="GW_NATIVE";
      if(serviceOwns&&!webPaused){webPaused=true;try{audio.pause();}catch(_){}}
      if(serviceOwns&&s.isPlaying){handoffBusy=false;handoffActive=true;return;}
      if(Date.now()-started<4500){setTimeout(check,100);return;}
      handoffBusy=false;handoffActive=false;try{B.claimForegroundPlayback();}catch(_){}if(webPaused)try{audio.play().catch(()=>{});}catch(_){}
    };check();
  }

  function reclaim(){
    if(window.__GW_QUALITY_OWNER===true)return;const state=parse(B.backgroundState(),{});if(!state.active||state.engine==="GW_NATIVE"||!state.trackId){handoffActive=false;handoffBusy=false;return;}
    const t=(typeof localTracks!=="undefined"?localTracks:[]).find(x=>x.gwId===state.trackId);if(!t)return;try{queue=allTracks();}catch(_){return;}const index=queue.indexOf(t);if(index<0)return;
    try{B.qualityPause();}catch(_){}
    const waitStart=Date.now();const transfer=()=>{const s=parse(B.backgroundState(),{});if(s.isPlaying&&Date.now()-waitStart<1000){setTimeout(transfer,80);return;}
      loadTrack(index,Number(s.positionMs||state.positionMs||0)/1000,false);const m=$("audio");const finish=()=>{if(state.isPlaying)m.play().then(()=>{try{B.claimForegroundPlayback();}catch(_){}}).catch(()=>{});else try{B.claimForegroundPlayback();}catch(_){}handoffActive=false;handoffBusy=false;};
      if(m?.readyState>=2)finish();else m?.addEventListener("canplay",finish,{once:true});};transfer();
  }
  window.GW_BACKGROUND=Object.freeze({handoff,reclaim});
  document.addEventListener("visibilitychange",()=>{if(document.hidden)handoff();else setTimeout(reclaim,150);});window.addEventListener("pagehide",handoff);

  const media=$("audio");["timeupdate","play","pause","seeking","seeked","ended"].forEach(n=>media?.addEventListener(n,()=>save(n!=="timeupdate")));
  document.addEventListener("click",e=>{const t=e.target?.closest?.("#homeImport,#addMusicTab,[data-addmusic],#fileInput");if(!t)return;e.preventDefault();e.stopPropagation();e.stopImmediatePropagation();suppressHandoffUntil=Date.now()+30000;try{B.pickTracks();}catch(_){}},true);

  function routeLabel(type){return({BUILTIN_SPEAKER:"ДИНАМИК",BUILTIN_EARPIECE:"ТЕЛЕФОН",WIRED_HEADPHONES:"НАУШНИКИ",WIRED_HEADSET:"ГАРНИТУРА",USB_DEVICE:"USB DAC",USB_HEADSET:"USB",BLUETOOTH_A2DP:"BLUETOOTH",BLE_HEADSET:"BLUETOOTH",BLE_SPEAKER:"BLUETOOTH",HEARING_AID:"HEARING AID",HDMI:"HDMI",HDMI_ARC:"HDMI",HDMI_EARC:"HDMI",AUX_LINE:"AUX",LINE_ANALOG:"LINE",LINE_DIGITAL:"LINE"})[type]||"AUTO";}
  function refreshRoute(s){
    if(!s?.candidate){try{s=parse(B.outputSnapshot(),{});}catch(_){s={};}}const c=s?.candidate||((s?.type)?s:null);spatialAllowed=!!c?.spatialCapable;window.__gwSpatialAllowed=spatialAllowed;
    document.querySelectorAll("[data-scene='cinema'],[data-scene='reality']").forEach(n=>n.classList.toggle("gw-route-locked",!spatialAllowed));const key=c?[c.id,c.type,c.address].join("|"):"AUTO";if(key===lastRoute)return;lastRoute=key;
    const label=c?routeLabel(c.type):"AUTO";if($("olType"))$("olType").textContent=label;if($("olChip"))$("olChip").textContent="GW READY";if($("olLog"))$("olLog").innerHTML=`<div>Текущий выход: ${label}</div>`;
  }
  try{realityOutputAllowed=()=>spatialAllowed;spOpen=()=>spatialAllowed;}catch(_){}
  window.addEventListener("gw:route-changed",e=>refreshRoute(e.detail));
  document.addEventListener("click",e=>{const b=e.target?.closest?.("button,[role='button'],.scene-card");if(!b||spatialAllowed)return;const t=(b.textContent||"").replace(/\s+/g," ").toLowerCase();if(!/3d\s*reality|reality sound|кино|cinema/.test(t))return;e.preventDefault();e.stopPropagation();e.stopImmediatePropagation();try{toast("Требуется внешний аудиовыход");}catch(_){}},true);

  function restore(){const s=parse(localStorage.getItem(STATE),null);if(!s?.trackId)return;const t=(typeof localTracks!=="undefined"?localTracks:[]).find(x=>x.gwId===s.trackId);if(!t)return;try{queue=allTracks();const i=queue.indexOf(t);if(i>=0)loadTrack(i,Number(s.positionMs||0)/1000,false);}catch(_){}}
  try{applyLibrary(parse(B.library(),[]));}catch(_){}installSeek();setTimeout(installSeek,800);try{refreshRoute(parse(B.outputSnapshot(),{}));}catch(_){}
  setTimeout(()=>{const s=parse(B.backgroundState(),{});if(s.active&&s.engine!=="GW_NATIVE")reclaim();else if(!s.active)restore();},300);
  try{B.jsLog("INFO","GW_NATIVE_JS_V8 ready");}catch(_){}
})();
