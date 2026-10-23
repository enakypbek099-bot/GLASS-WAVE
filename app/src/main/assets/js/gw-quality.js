(() => {
  "use strict";
  if (window.__GW_QUALITY_JS_V3__) return;
  window.__GW_QUALITY_JS_V3__ = true;

  const B=window.GwBridge;if(!B||typeof B.qualityStart!=="function")return;
  const $=id=>document.getElementById(id),PREF="gw-quality-mode-v3";
  const parse=(v,f)=>{try{return JSON.parse(String(v));}catch(_){return f;}};
  let pref="DSP",owner="DSP",status=null,pollId=0,dragging=false,pendingId="",pendingAt=0,transition=false;
  try{pref=localStorage.getItem(PREF)==="QUALITY"?"QUALITY":"DSP";}catch(_){}

  /* The old HTML Hi-Res renderer is never allowed to own audio. */
  const legacySetEngine=typeof window.setEngine==="function"?window.setEngine:null;
  try{localStorage.setItem("neonwave-hires","0");legacySetEngine?.(false,true);}catch(_){}
  window.setEngine=function(on){setPreference(!!on);};

  const say=m=>{try{toast(m);}catch(_){}};
  const log=(l,m)=>{try{B.jsLog(l,"QUALITY "+m);}catch(_){}};
  const fmtMs=ms=>{const s=Math.max(0,Math.floor(Number(ms||0)/1000));return Math.floor(s/60)+":"+String(s%60).padStart(2,"0");};
  const isWav=t=>!!t&&(/^(wav|wave)$/i.test(String(t.info?.fmt||""))||/\.wave?$/i.test(String(t.file?.name||"")));
  const current=()=>{try{return currentTrack||null;}catch(_){return null;}};
  const indexNow=()=>{try{return typeof currentIndex==="number"?currentIndex:-1;}catch(_){return-1;}};
  function setOwner(v){owner=v;window.__GW_QUALITY_OWNER=v==="QUALITY";syncToggle();}
  function savePref(){try{localStorage.setItem(PREF,pref);localStorage.setItem("neonwave-hires","0");}catch(_){}}

  function silenceWeb(){
    const list=[];try{if(webEl)list.push(webEl);}catch(_){}try{if(audio&&!list.includes(audio))list.push(audio);}catch(_){}
    list.forEach(a=>{try{a.pause();a.removeAttribute("src");a.dataset.gwTrackId="";a.load();}catch(_){}});
  }

  function startQuality(track,positionMs,autoPlay){
    if(!track?.gwId||!isWav(track))return false;silenceWeb();setOwner("QUALITY");pendingId=track.gwId;pendingAt=Date.now();transition=true;
    let ok=false;try{ok=B.qualityStart(track.gwId,Math.max(0,Math.round(positionMs||0)),!!autoPlay,-1,true);}catch(_){}
    if(!ok){transition=false;setOwner("DSP");say("GW Native іске қосылмады");return false;}startPoll();return true;
  }

  function waitStopped(done){
    const begun=Date.now();const check=()=>{const s=parse(B.qualityStatus(),{});if(!s.active){done(s);return;}if(Date.now()-begun<2500){setTimeout(check,80);return;}log("ERROR","service stop acknowledgement timeout");say("GW Native тоқтамады; DSP іске қосылмады");transition=false;};check();
  }

  function toDsp(index,positionMs,autoPlay){
    if(transition)return;transition=true;try{B.qualityStop();}catch(_){}
    waitStopped(()=>{setOwner("DSP");status=null;try{origPlay(index);}catch(_){transition=false;return;}
      const apply=()=>{try{if(positionMs>0&&Number.isFinite(audio.duration))audio.currentTime=Math.min(positionMs/1000,Math.max(0,audio.duration-.25));if(!autoPlay)audio.pause();}catch(_){}transition=false;paint();};
      try{if(audio.readyState>=1)apply();else audio.addEventListener("loadedmetadata",apply,{once:true});}catch(_){transition=false;}});
  }

  function setPreference(on){
    pref=on?"QUALITY":"DSP";savePref();syncToggle();const t=current(),idx=indexNow();
    if(!on){if(owner==="QUALITY"&&idx>=0){const n=status?.native||{},pos=Number(n.positionMs||0),playing=n.state==="STARTED";toDsp(idx,pos,playing);}paint();return;}
    if(!t){say("GW QUALITY қосылды. WAV трегін таңда.");paint();return;}
    if(!isWav(t)){say("Бұл формат әзірге GLASS WAVE DSP режимінде ойнайды.");paint();return;}
    let pos=0,playing=false;try{pos=(audio.currentTime||0)*1000;playing=!audio.paused&&!audio.ended;}catch(_){}
    startQuality(t,pos,playing);paint();
  }

  function syncTrack(id){
    let list=[];try{list=allTracks();}catch(_){}const t=list.find(x=>x?.gwId===id);if(!t)return;
    try{queue=list;currentIndex=list.indexOf(t);currentTrack=t;setTrackMeta(t);renderAllTracks();}catch(_){}
  }

  function startPoll(){if(!pollId){pollId=setInterval(poll,350);poll();}}
  function poll(){
    const s=parse(B.qualityStatus(),null);if(!s)return;status=s;
    if(owner!=="QUALITY"){clearInterval(pollId);pollId=0;return;}
    if(pendingId){const fresh=s.trackId===pendingId&&Number(s.heartbeat||0)>=pendingAt;if(fresh){pendingId="";transition=false;}else if(Date.now()-pendingAt<5000)return;else{pendingId="";transition=false;}}
    if(s.qualityError){const code=s.qualityError;log("WARN",code);const t=current(),idx=indexNow();setOwner("DSP");status=null;say(code==="WAV_RIFF_REQUIRED"?"Бұл WAV нұсқасын GW Native оқымады; DSP режиміне қайтты.":"GW Native қатесі: "+code);if(t&&idx>=0)try{origPlay(idx);}catch(_){}paint();return;}
    if(s.trackId&&current()?.gwId!==s.trackId)syncTrack(s.trackId);paintTransport(s);paint();
  }

  function paintTransport(s){
    const n=s.native||{},pos=Number(n.positionMs||0),dur=Number(n.durationMs||0),playing=n.state==="STARTED",pct=dur>0?Math.min(100,100*pos/dur):0;
    const bar=$("seekBar");if(bar&&!dragging&&dur>0)bar.value=pct;const e=$("elapsed");if(e&&!dragging)e.textContent=fmtMs(pos);const d=$("duration");if(d&&dur>0)d.textContent=fmtMs(dur);
    const mp=$("miniProgress");if(mp)mp.style.width=pct+"%";const nc=$("npCur");if(nc&&!dragging)nc.textContent=fmtMs(pos);const nd=$("npDur");if(nd&&dur>0)nd.textContent=fmtMs(dur);
    document.body.classList.toggle("is-playing",playing);try{const icon=playing?"pause":"play";if(typeof iconSvg==="function"){if($("miniPlay"))$("miniPlay").innerHTML=iconSvg(icon,18);if($("bigPlay"))$("bigPlay").innerHTML=iconSvg(icon,25);if($("npPlay"))$("npPlay").textContent=playing?"❚❚":"▶";}}catch(_){}
  }

  function syncToggle(){const sw=$("hiResToggle");if(sw)sw.checked=pref==="QUALITY";}
  function line(k,v){return `<div class="gwq-line"><span>${k}</span><b>${v}</b></div>`;}
  function sourceText(){const n=status?.native;if(owner==="QUALITY"&&n?.nativeWav)return `${n.sourceFormat} · ${(Number(n.sourceRate)/1000).toFixed(1)} кГц · ${n.sourceBits} бит · ${n.sourceChannels} кан.`;return current()?.info?.label||"—";}
  function outputText(){const n=status?.native;if(owner!=="QUALITY")return"GLASS WAVE DSP";if(!n?.ok)return"—";return `${n.audioApi} · ${n.actualFormat} · ${n.actualRate} Гц · ${n.actualChannels} кан. · ${n.actualSharing} · dev ${n.actualDeviceId}`;}
  function card(){
    const c=document.createElement("div");c.id="gwQualityTruth";c.innerHTML=`<div class="gwq-title">GW NATIVE · QUALITY</div><div data-gwq-body></div>`;return c;
  }
  function paint(){
    syncToggle();const body=$("hrBody");if(!body)return;let c=$("gwQualityTruth");if(!c){body.replaceChildren(card());c=$("gwQualityTruth");}const box=c.querySelector("[data-gwq-body]");const n=status?.native||{};
    box.innerHTML=line("Режим",owner==="QUALITY"?"QUALITY · GW Native":"DSP · GLASS WAVE")+line("SOURCE",sourceText())+line("OUTPUT",outputText())+(owner==="QUALITY"?line("Күй",n.state||"OPENING")+line("Растау",n.streamVerified===true?"I32 + source rate ✓":"—"):"")+`<small>I32 — қолданба stream форматы; DAC bit-depth немесе bit-perfect кепілі емес. QUALITY: WAV.</small>`;
    const title=$("hrTitle");if(title)title.textContent="GW Native";const tabs=$("hrTabs");if(tabs)tabs.style.display="none";
    const st=$("audioStatus");if(st)st.textContent=owner==="QUALITY"?(n.streamVerified===true?`GW NATIVE · ${n.actualRate} Hz · I32`:"GW NATIVE · OPENING"):current()?.info?.label||"GLASS WAVE DSP";
  }
  const css=document.createElement("style");css.textContent=`#gwQualityTruth{padding:14px;border:1px solid rgba(255,255,255,.14);border-radius:16px;background:rgba(255,255,255,.055);font:12px/1.5 system-ui;color:inherit}.gwq-title{font-weight:900;letter-spacing:1px;margin-bottom:9px}.gwq-line{display:flex;justify-content:space-between;gap:12px;padding:4px 0}.gwq-line span{opacity:.58}.gwq-line b{text-align:right}#gwQualityTruth small{display:block;opacity:.55;margin-top:10px}`;document.head.appendChild(css);

  /* Existing HTML switch becomes the only mode control; legacy listener is blocked. */
  window.addEventListener("change",e=>{if(e.target?.id!=="hiResToggle")return;e.stopImmediatePropagation();e.preventDefault();setPreference(!!e.target.checked);},true);
  window.addEventListener("click",e=>{if(e.target?.closest?.("#hrOpen"))setTimeout(paint,0);},true);

  let origPlay=null;try{origPlay=playTrack;}catch(_){}
  if(typeof origPlay==="function")playTrack=function(index){
    let list=[];try{list=queue?.length?queue:allTracks();}catch(_){}const t=list[index];
    if(pref==="QUALITY"&&isWav(t)&&t?.gwId){try{queue=list;currentIndex=index;currentTrack=t;setTrackMeta(t);}catch(_){}if(startQuality(t,0,true))return;}
    if(owner==="QUALITY"){toDsp(index,0,true);return;}if(pref==="QUALITY"&&t&&!isWav(t))say("Бұл формат GLASS WAVE DSP режимінде ойнайды.");origPlay(index);
  };

  window.addEventListener("click",e=>{
    if(owner!=="QUALITY")return;const play=e.target?.closest?.("#bigPlay,#miniPlay,#npPlay");const next=e.target?.closest?.("#nextBtn,[data-px='nextBtn']");const prev=e.target?.closest?.("#prevBtn,[data-px='prevBtn']");if(!play&&!next&&!prev)return;
    e.preventDefault();e.stopImmediatePropagation();if(next)B.qualityNext();else if(prev)B.qualityPrevious();else{const n=(status||parse(B.qualityStatus(),{})).native||{};if(n.state==="STARTED")B.qualityPause();else B.qualityPlay();}setTimeout(poll,100);
  },true);

  let seekValue=null,seekCommitted=false;
  ["pointerdown","touchstart","mousedown","input","change","pointerup","touchend","mouseup","pointercancel"].forEach(name=>window.addEventListener(name,e=>{
    if(owner!=="QUALITY"||e.target?.id!=="seekBar")return;e.stopImmediatePropagation();e.preventDefault();const dur=Number(status?.native?.durationMs||0);
    if(name==="pointerdown"||name==="touchstart"||name==="mousedown"){dragging=true;seekCommitted=false;}
    else if(name==="input"){dragging=true;seekValue=dur*Number(e.target.value||0)/100;const el=$("elapsed");if(el&&dur>0)el.textContent=fmtMs(seekValue);}
    else if((name==="change"||name==="pointerup"||name==="touchend"||name==="mouseup")&&!seekCommitted){if(dur>0){seekCommitted=true;const value=seekValue==null?dur*Number(e.target.value||0)/100:seekValue;B.qualitySeek(Math.round(value));}setTimeout(()=>{dragging=false;seekValue=null;poll();},250);}
    else if(name==="pointercancel")dragging=false;
  },true));

  /* Rewrite misleading main-card copy but preserve the existing visual card. */
  function repairCopy(){const sw=$("hiResToggle");const host=sw?.closest?.(".settings-card");if(host){const h=host.querySelector("h3");const p=host.querySelector("p");if(h)h.textContent="GW Native · QUALITY";if(p)p.textContent="WAV: GW Native → Oboe → AAudio → strict I32. DSP режимінде EQ, Bass, Tone, 3D, 8D және Lab сақталады.";}const open=$("hrOpen");if(open)open.textContent="GW Native күйін ашу ›";}

  const initial=parse(B.qualityStatus(),{});if(initial.active&&initial.engine==="GW_NATIVE"&&!initial.qualityError){pref="QUALITY";savePref();setOwner("QUALITY");status=initial;startPoll();syncTrack(initial.trackId);}else setOwner("DSP");
  repairCopy();syncToggle();paint();setInterval(()=>{repairCopy();paint();},700);
  window.GW_QUALITY=Object.freeze({mode:()=>({pref,owner}),status:()=>status});
})();
