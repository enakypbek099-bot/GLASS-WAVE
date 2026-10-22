(() => {
  "use strict";
  if (window.__GW_FINAL_BRIDGE__) return;
  window.__GW_FINAL_BRIDGE__ = true;
  const bridge = window.GwBridge, $ = id => document.getElementById(id);
  let stored = [], nativeActive = false, nativeTimer = 0, lastRoute = "";
  const parse = (v, f={}) => { try { return JSON.parse(String(v)); } catch (_) { return f; } };
  const sourceFor = t => "https://appassets.androidplatform.net/tracks/" + encodeURIComponent(t.gwId) + "/" + encodeURIComponent(t.file.name);
  const isWav = t => /\.wav(e)?$/i.test(t?.file?.name || "");

  function legacyTrack(t) {
    const seconds = Number(t.durationMs || 0) / 1000;
    return {gwId:t.id,title:t.title||t.name,artist:t.artist||"Неизвестный автор",album:t.album||"Без альбома",
      time:typeof fmt==="function"?fmt(seconds):"--:--",art:"vinyl",playlist:"local",duration:seconds,local:true,
      file:{name:t.name||"audio",size:Number(t.size||0),lastModified:0},coverUrl:null,
      info:{fmt:String(t.name||"").split(".").pop().toUpperCase(),hi:/\.(flac|wav|alac)$/i.test(t.name||"")}};
  }

  function replaceLibrary(value) {
    stored = Array.isArray(value) ? value : [];
    try {
      localTracks = stored.map(legacyTrack);
      queue = allTracks();
      renderAllTracks();
    } catch (e) { bridge.jsError("Library render: " + e); }
  }
  window.GW_LIBRARY = Object.freeze({replace:replaceLibrary,tracks:()=>stored.slice()});
  window.addEventListener("gw:library-changed", e => replaceLibrary(e.detail));

  function requestImport() { try { bridge.pickTracks(); } catch(e) { bridge.jsError("Picker: "+e); } }
  document.addEventListener("click", e => {
    const main = e.target?.closest?.("#homeImport,#addMusicTab,[data-addmusic],#fileInput");
    if (!main) return;
    e.preventDefault(); e.stopPropagation(); e.stopImmediatePropagation(); requestImport();
  }, true);
  // qaChoose/qaFiles әдейі ұсталмайды: Q-LAB өзінің анализін сақтайды.

  function normalPlay(index, position=0) {
    if (!queue.length) queue = allTracks();
    if (index < 0 || index >= queue.length) return;
    nativeActive = false; clearTimeout(nativeTimer); bridge.closeStream();
    hiRes = false; if ($("hiResToggle")) $("hiResToggle").checked = false;
    currentIndex=index; const t=queue[index]; currentTrack=t;
    $("playerScreen")?.classList.add("open");
    try { setTrackMeta(t); } catch(_) {}
    audio = webEl; audio.pause(); audio.src=sourceFor(t); audio.preload="auto"; audio.load();
    audio.addEventListener("loadedmetadata", function seek(){audio.removeEventListener("loadedmetadata",seek);if(position>0)audio.currentTime=Math.min(position,Math.max(0,audio.duration-.2));},{once:true});
    ensureAudioGraph(); if(audioCtx?.state==="suspended")audioCtx.resume().catch(()=>{});
    bridge.prepareBackgroundPlayback();
    audio.play().catch(()=>toast("Не удалось запустить трек."));
    renderAllTracks(); updatePlayButtons();
  }

  try { playTrack = normalPlay; } catch(e) { bridge.jsError("playTrack hook: "+e); }

  function nativeStatusLoop(){
    if(!nativeActive)return; const s=parse(bridge.status());
    renderNativeStatus(s);
    if(s.state==="ENDED"||!s.ok){nativeActive=false;$("hiResToggle").checked=false;hiRes=false;return;}
    nativeTimer=setTimeout(nativeStatusLoop,1000);
  }
  function renderNativeStatus(s){
    if($("olChip"))$("olChip").textContent=s.ok?"GW NATIVE":"GW READY";
    if($("olRate"))$("olRate").textContent=s.actualRate?String(s.actualRate/1000):"—";
    if($("olBits"))$("olBits").textContent=s.ok?"32":"—";
    if($("olDetail")&&nativeActive)$("olDetail").innerHTML=`<div>GW Native · WAV</div><div>${s.actualRate||0} Hz · 32 bit · ${s.actualSharing||""}</div>`;
  }
  function enableNative(){
    const t=currentTrack;if(!t?.gwId){$("hiResToggle").checked=false;toast("Сначала выбери WAV-трек.");return;}
    if(!isWav(t)){$("hiResToggle").checked=false;toast("GW Native: сейчас поддерживается PCM WAV.");return;}
    const at=Math.round((audio?.currentTime||0)*1000); audio?.pause();
    const s=parse(bridge.startNativeWav(t.gwId,at));
    if(!s.ok){hiRes=false;nativeActive=false;$("hiResToggle").checked=false;normalPlay(queue.indexOf(t),at/1000);toast("GW Native недоступен: "+(s.error||"выход отклонён"));return;}
    hiRes=true;nativeActive=true;renderNativeStatus(s);nativeStatusLoop();toast("GW Native · WAV · "+s.actualSharing);
  }
  function disableNative(resume=true){
    const s=parse(bridge.status()), rate=Number(s.sourceRate||0), frames=Number(s.positionFrames||0);
    nativeActive=false;clearTimeout(nativeTimer);bridge.closeStream();hiRes=false;$("hiResToggle").checked=false;
    if(resume&&currentTrack){queue=allTracks();normalPlay(Math.max(0,queue.indexOf(currentTrack)),rate>0?frames/rate:0);}
  }
  document.addEventListener("change",e=>{
    if(e.target?.id!=="hiResToggle")return;
    e.stopImmediatePropagation();e.target.checked?enableNative():disableNative(true);
  },true);
  const hi=$("hiResToggle");if(hi){hi.disabled=false;hi.checked=false;hi.title="GW Native PCM WAV";}

  function handoff(){
    let t=currentTrack;if(!t?.gwId)return;
    let ms=Math.round((audio?.currentTime||0)*1000);
    if(nativeActive){const s=parse(bridge.status());if(s.sourceRate)ms=Math.round(Number(s.positionFrames||0)*1000/Number(s.sourceRate));disableNative(false);}
    else if(!audio||audio.paused)return;
    if(bridge.startBackgroundPlayback(t.gwId,ms)){audio?.pause();}
  }
  function reclaim(){
    const s=parse(bridge.claimForegroundPlayback(),{active:false});if(!s.active||!s.trackId)return;
    replaceLibrary(parse(bridge.library(),[]));const i=localTracks.findIndex(t=>t.gwId===s.trackId);if(i>=0){queue=allTracks();normalPlay(queue.indexOf(localTracks[i]),Number(s.positionMs||0)/1000);if(!s.isPlaying)audio.pause();}
  }
  document.addEventListener("visibilitychange",()=>{
    document.body.classList.toggle("gw-ui-sleep",document.hidden);
    try{window.nwFx=!document.hidden;}catch(_){}
    if(document.hidden)handoff();else reclaim();
  });

  function routeLabel(t){return ({BUILTIN_SPEAKER:"ДИНАМИК",WIRED_HEADPHONES:"НАУШНИКИ",WIRED_HEADSET:"ГАРНИТУРА",USB_DEVICE:"USB DAC",USB_HEADSET:"USB",BLUETOOTH_A2DP:"BLUETOOTH",BLE_HEADSET:"BLUETOOTH",BLE_SPEAKER:"BLUETOOTH",HDMI:"HDMI",AUX_LINE:"AUX"})[t]||"AUTO";}
  function renderRoute(r,force=false){r=r||parse(bridge.currentOutputDevice(),{ok:false});const key=r.ok?[r.id,r.type,r.address].join("|"):"AUTO";if(!force&&key===lastRoute)return;lastRoute=key;const l=r.ok?routeLabel(r.type):"AUTO";if($("olType"))$("olType").textContent=l;if($("olLog"))$("olLog").innerHTML=`<div>Текущий выход: ${l}</div>`;}
  window.addEventListener("gw:route-changed",e=>renderRoute(e.detail));
  document.addEventListener("click",e=>{if(!e.target?.closest?.("#olRefresh"))return;e.preventDefault();e.stopImmediatePropagation();renderRoute(parse(bridge.currentOutputDevice()),true);},true);

  const style=document.createElement("style");style.textContent=`#settingsScreen .settings-tabs{position:relative!important;top:auto!important}#settingsScreen .settings-panel{animation:none!important}body.gw-ui-sleep *{animation-play-state:paused!important}.settings-card{content-visibility:auto;contain-intrinsic-size:240px}`;document.head.appendChild(style);
  try {
    setEngine(false, true);
    localStorage.setItem("neonwave-hires", "0");
    setEngine = function(on) {
      if (on) enableNative();
      else if (nativeActive) disableNative(true);
      else { hiRes = false; if ($("hiResToggle")) $("hiResToggle").checked = false; }
    };
  } catch (_) {}
  bridge.closeStream();
  replaceLibrary(parse(bridge.library(), []));
  renderRoute(parse(bridge.currentOutputDevice()), true);
  reclaim();
})();
