package kz.glasswave.player

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.res.AssetFileDescriptor
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.MediaPlayer
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.net.Uri
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import org.json.JSONArray
import org.json.JSONObject

object GwPlaybackState {
    private const val PREFS = "gw-playback-service"
    @Volatile var active=false
    @Volatile var trackId=""
    @Volatile var positionMs=0L
    @Volatile var durationMs=0L
    @Volatile var playing=false
    @Volatile var title=""
    @Volatile var artist=""
    @Volatile var heartbeat=0L
    @Volatile var engine=""
    @Volatile var qualityError=""
    @Volatile var nativeStatus=""

    @Synchronized fun update(context:Context,isActive:Boolean,id:String,position:Long,duration:Long,isPlaying:Boolean,trackTitle:String,trackArtist:String){
        active=isActive; trackId=id; positionMs=position.coerceAtLeast(0); durationMs=duration.coerceAtLeast(0); playing=isPlaying
        title=trackTitle; artist=trackArtist; heartbeat=System.currentTimeMillis()
        context.getSharedPreferences(PREFS,Context.MODE_PRIVATE).edit()
            .putBoolean("active",active).putString("trackId",trackId).putLong("positionMs",positionMs)
            .putLong("durationMs",durationMs).putBoolean("playing",playing).putString("title",title)
            .putString("artist",artist).putLong("heartbeat",heartbeat).apply()
    }

    @Synchronized fun json(context:Context):String{
        val p=context.getSharedPreferences(PREFS,Context.MODE_PRIVATE)
        val savedBeat=p.getLong("heartbeat",0); val live=System.currentTimeMillis()-savedBeat<10_000
        val obj=JSONObject()
            .put("active",active||(p.getBoolean("active",false)&&live))
            .put("trackId",trackId.ifBlank{p.getString("trackId","").orEmpty()})
            .put("positionMs",if(positionMs>0)positionMs else p.getLong("positionMs",0))
            .put("durationMs",if(durationMs>0)durationMs else p.getLong("durationMs",0))
            .put("isPlaying",if(active)playing else p.getBoolean("playing",false)&&live)
            .put("title",title.ifBlank{p.getString("title","").orEmpty()})
            .put("artist",artist.ifBlank{p.getString("artist","").orEmpty()})
            .put("heartbeat",if(heartbeat>0)heartbeat else savedBeat)
            .put("engine",engine).put("qualityError",qualityError)
        obj.put("native",runCatching{JSONObject(nativeStatus)}.getOrNull()?:JSONObject())
        return obj.toString()
    }
}

class GwPlaybackService:Service(){
    companion object{
        const val ACTION_START="kz.glasswave.player.background.START"
        const val ACTION_PLAY="kz.glasswave.player.background.PLAY"
        const val ACTION_PAUSE="kz.glasswave.player.background.PAUSE"
        const val ACTION_PREVIOUS="kz.glasswave.player.background.PREVIOUS"
        const val ACTION_NEXT="kz.glasswave.player.background.NEXT"
        const val ACTION_STOP="kz.glasswave.player.background.STOP"
        const val ACTION_SEEK="kz.glasswave.player.background.SEEK"
        const val EXTRA_QUALITY="quality"
        const val EXTRA_AUTOPLAY="autoPlay"
        const val EXTRA_DEVICE_ID="deviceId"
        const val EXTRA_EXCLUSIVE="exclusive"
        const val EXTRA_TRACK_ID="trackId"
        const val EXTRA_POSITION_MS="positionMs"
        private const val CHANNEL_ID="glass_wave_playback"
        private const val NOTIFICATION_ID=7412
    }

    private data class Track(val id:String,val uri:String,val name:String,val mime:String,val title:String,val artist:String){
        fun isWav()=mime.contains("wav",true)||name.endsWith(".wav",true)||name.endsWith(".wave",true)
    }

    private val handler=Handler(Looper.getMainLooper())
    private lateinit var audioManager:AudioManager
    private lateinit var mediaSession:MediaSession
    private lateinit var focusRequest:AudioFocusRequest
    private lateinit var libraryStore:GwLibraryStore
    private lateinit var metadataStore:GwMediaMetadataStore
    private var player:MediaPlayer?=null
    private var queue:List<Track> = emptyList()
    private var currentIndex=-1
    private var prepared=false
    private var foreground=false
    private var resumeAfterFocus=false
    private var pendingPlay=false
    private var pendingPosition=0L
    private var artwork:Bitmap?=null
    private var quality=false
    private var nativeLoaded=false
    private var nativeDevice=-1
    private var nativeExclusive=true
    private var nativeAutoPlay=true
    private var endedHandled=false
    private var native=JSONObject()
    private var wakeLock:PowerManager.WakeLock?=null

    private val attrs=AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build()
    private val ticker=object:Runnable{
        override fun run(){ if(quality)pollQuality(); publish(false); if(hasEngine())handler.postDelayed(this,if(quality)500 else 1000) }
    }

    override fun onCreate(){
        super.onCreate(); audioManager=getSystemService(AUDIO_SERVICE) as AudioManager
        libraryStore=GwLibraryStore(applicationContext); metadataStore=GwMediaMetadataStore(applicationContext)
        createChannel(); createSession()
        focusRequest=AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN).setAudioAttributes(attrs).setAcceptsDelayedFocusGain(true)
            .setOnAudioFocusChangeListener({onFocus(it)},handler).build()
    }

    override fun onStartCommand(intent:Intent?,flags:Int,startId:Int):Int{
        when(intent?.action){
            ACTION_START->{
                ensureForeground(); quality=intent.getBooleanExtra(EXTRA_QUALITY,false)
                nativeDevice=intent.getIntExtra(EXTRA_DEVICE_ID,-1); nativeExclusive=intent.getBooleanExtra(EXTRA_EXCLUSIVE,true)
                nativeAutoPlay=intent.getBooleanExtra(EXTRA_AUTOPLAY,true)
                loadQueue(intent.getStringExtra(EXTRA_TRACK_ID).orEmpty(),intent.getLongExtra(EXTRA_POSITION_MS,0))
            }
            ACTION_PLAY->play(); ACTION_PAUSE->pause(); ACTION_PREVIOUS->previous(); ACTION_NEXT->next(); ACTION_STOP->stopPlayback(true)
            ACTION_SEEK->seek(intent.getLongExtra(EXTRA_POSITION_MS,0))
            else->stopSelf(startId)
        }
        return START_NOT_STICKY
    }

    private fun allTracks():List<Track>{
        val arr=runCatching{JSONArray(libraryStore.asJson())}.getOrElse{JSONArray()}; val list=ArrayList<Track>()
        for(i in 0 until arr.length()){
            val o=arr.optJSONObject(i)?:continue; val id=o.optString("id"); val uri=o.optString("uri"); if(id.isBlank()||uri.isBlank())continue
            list+=Track(id,uri,o.optString("name"),o.optString("mime"),o.optString("title").ifBlank{o.optString("name","Аудиотрек")},o.optString("artist").ifBlank{"Неизвестный исполнитель"})
        }
        return list
    }

    private fun loadQueue(id:String,position:Long){
        val all=allTracks(); queue=if(quality)all.filter{it.isWav()} else all
        if(queue.isEmpty()){ fail(if(quality)"QUALITY_WAV_QUEUE_EMPTY" else "QUEUE_EMPTY"); return }
        currentIndex=queue.indexOfFirst{it.id==id}
        if(currentIndex<0){ if(quality){ fail("QUALITY_TRACK_NOT_WAV"); return } else currentIndex=0 }
        openTrack(currentIndex,position,if(quality)nativeAutoPlay else true)
    }

    private fun openTrack(index:Int,position:Long,autoPlay:Boolean){
        if(queue.isEmpty())return; currentIndex=(index+queue.size)%queue.size; val t=queue[currentIndex]
        if(quality){ openNative(t,position,autoPlay); return }
        closeNative(); GwPlaybackState.engine="MEDIA_PLAYER"; GwPlaybackState.qualityError=""; prepared=false; pendingPlay=autoPlay; pendingPosition=position.coerceAtLeast(0)
        runCatching{player?.release()}; player=null; setArtwork(t); ensureForeground(); startTicker()
        val p=MediaPlayer(); player=p
        try{
            p.setAudioAttributes(attrs); p.setWakeMode(applicationContext,PowerManager.PARTIAL_WAKE_LOCK); p.setDataSource(applicationContext,Uri.parse(t.uri))
            p.setOnPreparedListener{prepared=true; val at=pendingPosition; pendingPosition=0; publishMetadata(t,runCatching{it.duration.toLong()}.getOrDefault(0L)); if(at>0)runCatching{it.seekTo(at,MediaPlayer.SEEK_CLOSEST)}; if(pendingPlay)play() else publish(true)}
            p.setOnSeekCompleteListener{publish(true)}; p.setOnCompletionListener{next()}
            p.setOnErrorListener{_,what,extra->GwRuntimeLog.error("BACKGROUND_PLAYER","MediaPlayer error $what/$extra");prepared=false;pendingPlay=false;publish(true);true}
            p.prepareAsync()
        }catch(e:Throwable){ GwRuntimeLog.error("BACKGROUND_PLAYER","Open failed ${t.uri}",e); fail("MEDIA_PLAYER_OPEN_FAILED") }
    }

    private fun openNative(t:Track,position:Long,autoPlay:Boolean){
        runCatching{player?.release()}; player=null; closeNative(); prepared=false; pendingPlay=false; endedHandled=false
        GwPlaybackState.engine="GW_NATIVE"; GwPlaybackState.qualityError=""; setArtwork(t); ensureForeground(); startTicker()
        if(!t.isWav()){fail("QUALITY_TRACK_NOT_WAV");return}; if(!GwNativeAudio.isLoaded()){fail("GW_NATIVE_NOT_LOADED");return}
        var afd:AssetFileDescriptor?=null
        try{
            afd=contentResolver.openAssetFileDescriptor(Uri.parse(t.uri),"r")?:run{fail("FILE_OPEN_FAILED");return}
            val result=GwNativeAudio.nativeLoadWav(afd.parcelFileDescriptor.fd,afd.startOffset,afd.length,nativeDevice,nativeExclusive,position.coerceAtLeast(0),false)
            applyNative(result); val error=native.optString("error")
            if(!native.optBoolean("ok",false)||error.isNotEmpty()){fail(error.ifEmpty{"NATIVE_OPEN_FAILED"});return}
            nativeLoaded=true; prepared=true; publishMetadata(t,native.optLong("durationMs",0L)); if(autoPlay)play() else publish(true)
        }catch(e:Throwable){GwRuntimeLog.error("GW_NATIVE","Open failed ${t.uri}",e);fail(e.javaClass.simpleName)}finally{runCatching{afd?.close()}}
    }

    private fun applyNative(json:String){ native=runCatching{JSONObject(json)}.getOrDefault(JSONObject()); GwPlaybackState.nativeStatus=native.toString() }
    private fun refreshNative(){ if(nativeLoaded)applyNative(GwNativeAudio.statusSafely()) }
    private fun pollQuality(){
        refreshNative(); if(nativeLoaded&&!endedHandled&&native.optString("state")=="ENDED"){endedHandled=true;releaseWake();next()}
    }
    private fun hasEngine()=if(quality)nativeLoaded else player!=null
    private fun isPlaying()=if(quality)nativeLoaded&&native.optString("state")=="STARTED" else runCatching{player?.isPlaying==true}.getOrDefault(false)
    private fun position()=if(quality)native.optLong("positionMs",0) else runCatching{player?.currentPosition?.toLong()}.getOrNull()?:0
    private fun duration()=if(quality)native.optLong("durationMs",0) else runCatching{player?.duration?.toLong()}.getOrNull()?.takeIf{it>0}?:0

    private fun play(){
        if(quality){
            if(!nativeLoaded)return; if(!requestFocus()){pendingPlay=true;publish(true);return}; pendingPlay=false;endedHandled=false;acquireWake()
            val result=runCatching{if(native.optString("state")=="ENDED")GwNativeAudio.nativeSeek(0,true) else GwNativeAudio.nativeStart()}.getOrElse{""}
            if(result.isNotEmpty())applyNative(result); publish(true); return
        }
        val p=player?:return; if(!prepared){pendingPlay=true;return}; if(!requestFocus()){pendingPlay=true;publish(true);return}; pendingPlay=false
        runCatching{p.setVolume(1f,1f);if(!p.isPlaying)p.start()}.onFailure{GwRuntimeLog.error("BACKGROUND_PLAYER","Play failed",it)};publish(true)
    }

    private fun pause(){
        pendingPlay=false
        if(quality){ if(nativeLoaded)runCatching{GwNativeAudio.nativeStop()}.getOrNull()?.let(::applyNative);releaseWake();publish(true);return }
        runCatching{if(player?.isPlaying==true)player?.pause()};publish(true)
    }

    private fun seek(to:Long){
        if(quality){
            if(!nativeLoaded)return;refreshNative();val dur=duration();val target=if(dur>0)to.coerceIn(0,dur) else to.coerceAtLeast(0);val resume=isPlaying()
            runCatching{GwNativeAudio.nativeSeek(target,resume)}.getOrNull()?.let(::applyNative);endedHandled=false;publish(true);return
        }
        val p=player?:return;if(!prepared){pendingPosition=to.coerceAtLeast(0);return};val dur=duration();val target=if(dur>0)to.coerceIn(0,dur) else to.coerceAtLeast(0);runCatching{p.seekTo(target,MediaPlayer.SEEK_CLOSEST)}
    }

    private fun next(){if(queue.isNotEmpty())openTrack(currentIndex+1,0,true)}
    private fun previous(){if(queue.isEmpty())return;if(position()>3000)seek(0) else openTrack(currentIndex-1,0,true)}

    private fun requestFocus()=when(audioManager.requestAudioFocus(focusRequest)){
        AudioManager.AUDIOFOCUS_REQUEST_GRANTED->{resumeAfterFocus=false;true}
        AudioManager.AUDIOFOCUS_REQUEST_DELAYED->{resumeAfterFocus=true;false}
        else->{resumeAfterFocus=false;false}
    }
    private fun onFocus(change:Int){
        when(change){
            AudioManager.AUDIOFOCUS_GAIN->{if(!quality)runCatching{player?.setVolume(1f,1f)};if(resumeAfterFocus||pendingPlay){resumeAfterFocus=false;pendingPlay=false;play()}}
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT->{resumeAfterFocus=isPlaying();pause()}
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK->{if(quality){resumeAfterFocus=isPlaying();pause()}else runCatching{player?.setVolume(.25f,.25f)}}
            AudioManager.AUDIOFOCUS_LOSS->{resumeAfterFocus=false;pendingPlay=false;pause()}
        }
    }

    private fun createSession(){
        mediaSession=MediaSession(this,"GLASS_WAVE").apply{
            setFlags(MediaSession.FLAG_HANDLES_MEDIA_BUTTONS or MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS)
            setCallback(object:MediaSession.Callback(){override fun onPlay()=play();override fun onPause()=pause();override fun onSkipToNext()=next();override fun onSkipToPrevious()=previous();override fun onSeekTo(pos:Long)=seek(pos);override fun onStop()=stopPlayback(true)})
            isActive=true
        }
    }

    private fun setArtwork(t:Track){ artwork=loadArtwork(t.id); publishMetadata(t,0L) }
    private fun publishMetadata(t:Track,durationMs:Long){
        val m=MediaMetadata.Builder().putString(MediaMetadata.METADATA_KEY_TITLE,t.title).putString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE,t.title)
            .putString(MediaMetadata.METADATA_KEY_ARTIST,t.artist).putString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE,t.artist)
        if(durationMs>0)m.putLong(MediaMetadata.METADATA_KEY_DURATION,durationMs)
        artwork?.let{m.putBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON,it).putBitmap(MediaMetadata.METADATA_KEY_ART,it).putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART,it)}
        mediaSession.setMetadata(m.build())
    }
    private fun loadArtwork(id:String):Bitmap?{
        val f=runCatching{metadataStore.coverFileFor(id)}.getOrNull()?:return null;if(!f.isFile)return null
        return runCatching{val b=BitmapFactory.Options().apply{inJustDecodeBounds=true};BitmapFactory.decodeFile(f.absolutePath,b);if(b.outWidth<=0||b.outHeight<=0)return null
            var sample=1;while(maxOf(b.outWidth,b.outHeight)/sample>512)sample*=2
            BitmapFactory.decodeFile(f.absolutePath,BitmapFactory.Options().apply{inSampleSize=sample;inPreferredConfig=Bitmap.Config.RGB_565})}.getOrNull()
    }

    private fun publish(updateNotification:Boolean){
        if(quality)refreshNative();val open=hasEngine();val playing=isPlaying();val pos=position();val dur=duration();val t=queue.getOrNull(currentIndex)
        GwPlaybackState.update(applicationContext,open,t?.id.orEmpty(),pos,dur,playing,t?.title.orEmpty(),t?.artist.orEmpty())
        val state=when{playing->PlaybackState.STATE_PLAYING;prepared->PlaybackState.STATE_PAUSED;open->PlaybackState.STATE_BUFFERING;else->PlaybackState.STATE_NONE}
        mediaSession.setPlaybackState(PlaybackState.Builder().setActions(PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE or PlaybackState.ACTION_SKIP_TO_PREVIOUS or PlaybackState.ACTION_SKIP_TO_NEXT or PlaybackState.ACTION_SEEK_TO or PlaybackState.ACTION_STOP).setState(state,pos,if(playing)1f else 0f,SystemClock.elapsedRealtime()).build())
        if(updateNotification)ensureForeground()
    }

    private fun pending(action:String,code:Int)=PendingIntent.getService(this,code,Intent(this,GwPlaybackService::class.java).setAction(action),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    private fun notification():Notification{
        val t=queue.getOrNull(currentIndex);val playing=isPlaying();val openApp=PendingIntent.getActivity(this,40,Intent(this,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val b=Notification.Builder(this,CHANNEL_ID).setSmallIcon(android.R.drawable.ic_media_play).setContentTitle(t?.title?:"GLASS WAVE").setContentText(t?.artist?:"Музыка")
            .setContentIntent(openApp).setDeleteIntent(pending(ACTION_STOP,45)).setVisibility(Notification.VISIBILITY_PUBLIC).setCategory(Notification.CATEGORY_TRANSPORT).setOnlyAlertOnce(true).setOngoing(playing)
            .addAction(Notification.Action.Builder(android.R.drawable.ic_media_previous,"Алдыңғы",pending(ACTION_PREVIOUS,41)).build())
            .addAction(Notification.Action.Builder(if(playing)android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play,if(playing)"Пауза" else "Ойнату",pending(if(playing)ACTION_PAUSE else ACTION_PLAY,42)).build())
            .addAction(Notification.Action.Builder(android.R.drawable.ic_media_next,"Келесі",pending(ACTION_NEXT,43)).build())
            .setStyle(Notification.MediaStyle().setMediaSession(mediaSession.sessionToken).setShowActionsInCompactView(0,1,2))
        artwork?.let{b.setLargeIcon(it)};return b.build()
    }
    private fun ensureForeground(){val n=notification();if(!foreground){startForeground(NOTIFICATION_ID,n);foreground=true}else getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID,n)}
    private fun createChannel(){getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel(CHANNEL_ID,"GLASS WAVE media",NotificationManager.IMPORTANCE_LOW).apply{setSound(null,null);enableVibration(false);setShowBadge(false)})}
    private fun startTicker(){handler.removeCallbacks(ticker);handler.post(ticker)}

    private fun acquireWake(){
        if(wakeLock==null)wakeLock=(getSystemService(POWER_SERVICE) as PowerManager).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"GlassWave:GwNative").apply{setReferenceCounted(false)}
        if(wakeLock?.isHeld!=true)wakeLock?.acquire(6*60*60*1000L)
    }
    private fun releaseWake(){runCatching{if(wakeLock?.isHeld==true)wakeLock?.release()}}
    private fun closeNative(){releaseWake();if(GwNativeAudio.isLoaded())GwNativeAudio.closeSafely();nativeLoaded=false;native=JSONObject();GwPlaybackState.nativeStatus=""}

    private fun fail(code:String){
        GwRuntimeLog.warn("GW_PLAYBACK","Failure: $code");closeNative();runCatching{player?.release()};player=null;prepared=false;pendingPlay=false
        GwPlaybackState.qualityError=if(quality)code else "";GwPlaybackState.update(applicationContext,false,queue.getOrNull(currentIndex)?.id.orEmpty(),0,0,false,queue.getOrNull(currentIndex)?.title.orEmpty(),queue.getOrNull(currentIndex)?.artist.orEmpty())
        if(foreground){stopForeground(STOP_FOREGROUND_REMOVE);foreground=false};stopSelf()
    }

    private fun stopPlayback(clearError:Boolean){
        handler.removeCallbacks(ticker);closeNative();runCatching{player?.release()};player=null;prepared=false;pendingPlay=false;resumeAfterFocus=false;runCatching{audioManager.abandonAudioFocusRequest(focusRequest)}
        if(clearError)GwPlaybackState.qualityError="";GwPlaybackState.engine="";GwPlaybackState.nativeStatus=""
        GwPlaybackState.update(applicationContext,false,"",0,0,false,"","");runCatching{mediaSession.setPlaybackState(PlaybackState.Builder().setState(PlaybackState.STATE_STOPPED,0,0f).build())}
        artwork=null;if(foreground){stopForeground(STOP_FOREGROUND_REMOVE);foreground=false};stopSelf()
    }

    override fun onTaskRemoved(rootIntent:Intent?){GwRuntimeLog.info("GW_PLAYBACK","Task removed; service retained")}
    override fun onBind(intent:Intent?):IBinder?=null
    override fun onDestroy(){handler.removeCallbacksAndMessages(null);closeNative();runCatching{player?.release()};player=null;releaseWake();runCatching{audioManager.abandonAudioFocusRequest(focusRequest)};runCatching{mediaSession.release()};artwork=null;super.onDestroy()}
}
