#include "GwAudioEngine.h"
#include <algorithm>
#include <chrono>
#include <cmath>
#include <cstring>
#include <sstream>
#include <sys/stat.h>
#include <unistd.h>

namespace {
uint16_t le16(const uint8_t *p){ return uint16_t(p[0])|(uint16_t(p[1])<<8); }
uint32_t le32(const uint8_t *p){ return uint32_t(p[0])|(uint32_t(p[1])<<8)|(uint32_t(p[2])<<16)|(uint32_t(p[3])<<24); }
bool readAt(int fd,int64_t off,void *dst,size_t n){ auto *p=static_cast<uint8_t*>(dst); size_t done=0; while(done<n){ const ssize_t r=pread(fd,p+done,n-done,off+int64_t(done)); if(r<=0)return false; done+=size_t(r); } return true; }
const char *sharingName(oboe::SharingMode m){ return m==oboe::SharingMode::Exclusive?"EXCLUSIVE":"SHARED"; }
std::string esc(const std::string &s){ std::string o; for(char c:s){ if(c=='"'||c=='\\')o+='\\'; if(c=='\n')o+="\\n"; else o+=c; } return o; }
}

GwAudioEngine &GwAudioEngine::instance(){ static GwAudioEngine e; return e; }
GwAudioEngine::~GwAudioEngine(){ std::lock_guard<std::mutex> lock(mutex_); closeLocked(); }

bool GwAudioEngine::parseWav(int fd,int64_t base,int64_t length,WavInfo &out,std::string &error){
    uint8_t head[12];
    if(!readAt(fd,base,head,sizeof(head))||std::memcmp(head,"RIFF",4)!=0||std::memcmp(head+8,"WAVE",4)!=0){ error="WAV_RIFF_REQUIRED"; return false; }
    struct stat st{}; if(length<=0&&fstat(fd,&st)==0)length=st.st_size-base;
    const int64_t limit=length>0?base+length:base+int64_t(le32(head+4))+8;
    int64_t pos=base+12; bool haveFmt=false,haveData=false;
    while(pos+8<=limit){
        uint8_t chunk[8]; if(!readAt(fd,pos,chunk,8))break;
        const uint32_t size=le32(chunk+4); const int64_t payload=pos+8;
        if(payload+int64_t(size)>limit){ error="WAV_CHUNK_RANGE"; return false; }
        if(std::memcmp(chunk,"fmt ",4)==0&&size>=16){
            uint8_t f[40]{}; const size_t take=std::min<size_t>(size,sizeof(f));
            if(!readAt(fd,payload,f,take)){ error="WAV_FMT_READ"; return false; }
            out.format=le16(f); out.channels=le16(f+2); out.sampleRate=le32(f+4); out.blockAlign=le16(f+12); out.bits=le16(f+14);
            /* WAVE_FORMAT_EXTENSIBLE: accept only PCM or IEEE float subformat. */
            if(out.format==0xFFFE&&size>=40){ const uint32_t sub=le32(f+24); if(sub==1||sub==3)out.format=uint16_t(sub); }
            haveFmt=true;
        }else if(std::memcmp(chunk,"data",4)==0){ out.dataOffset=payload; out.dataSize=size; haveData=true; }
        if(haveFmt&&haveData)break;
        pos=payload+size+(size&1u);
    }
    if(!haveFmt||!haveData){ error="WAV_CHUNKS_MISSING"; return false; }
    if(out.channels<1||out.channels>2){ error="WAV_CHANNELS_UNSUPPORTED"; return false; }
    const bool pcm=out.format==1&&(out.bits==16||out.bits==24||out.bits==32);
    const bool f32=out.format==3&&out.bits==32;
    if(!pcm&&!f32){ error="WAV_FORMAT_UNSUPPORTED"; return false; }
    const uint16_t expected=uint16_t(out.channels*(out.bits/8));
    if(out.sampleRate<8000||out.sampleRate>384000||out.blockAlign<expected){ error="WAV_LAYOUT_INVALID"; return false; }
    out.totalFrames=out.dataSize/out.blockAlign; return out.totalFrames>0;
}

bool GwAudioEngine::openStreamLocked(int32_t deviceId,bool exclusive){
    requestedExclusive_=exclusive; requestedDeviceId_=deviceId;
    oboe::AudioStreamBuilder b; b.setDirection(oboe::Direction::Output); b.setAudioApi(oboe::AudioApi::AAudio);
    b.setFormat(oboe::AudioFormat::I32); b.setSampleRate(int32_t(wav_.sampleRate)); b.setChannelCount(2);
    b.setPerformanceMode(oboe::PerformanceMode::LowLatency); b.setUsage(oboe::Usage::Media); b.setContentType(oboe::ContentType::Music);
    b.setSharingMode(exclusive?oboe::SharingMode::Exclusive:oboe::SharingMode::Shared); b.setDataCallback(this); if(deviceId>=0)b.setDeviceId(deviceId);
    oboe::Result r=b.openStream(stream_);
    if(r!=oboe::Result::OK&&exclusive){ stream_.reset(); b.setSharingMode(oboe::SharingMode::Shared); r=b.openStream(stream_); }
    if(r!=oboe::Result::OK||!stream_){ error_=oboe::convertToText(r); stream_.reset(); return false; }
    if(stream_->getFormat()!=oboe::AudioFormat::I32){ error_="STRICT_I32_REJECTED"; stream_->close(); stream_.reset(); return false; }
    if(stream_->getSampleRate()!=int32_t(wav_.sampleRate)){ error_="SOURCE_RATE_REJECTED"; stream_->close(); stream_.reset(); return false; }
    return true;
}

void GwAudioEngine::startDecoderLocked(int64_t startFrame){
    positionFrames_=startFrame; readFrame_=0; writeFrame_=0; decoderEof_=false; ended_=false; stopDecoder_=false;
    decoderThread_=std::thread(&GwAudioEngine::decoderLoop,this);
}
void GwAudioEngine::stopDecoderLocked(){ stopDecoder_=true; if(decoderThread_.joinable())decoderThread_.join(); }
void GwAudioEngine::waitStreamState(oboe::StreamState wanted,int64_t timeoutMs){
    if(!stream_) return;
    const auto until=std::chrono::steady_clock::now()+std::chrono::milliseconds(timeoutMs);
    while(std::chrono::steady_clock::now()<until){ const auto s=stream_->getState(); if(s==wanted||s==oboe::StreamState::Closed||s==oboe::StreamState::Disconnected)return; usleep(2000); }
}

std::string GwAudioEngine::loadWav(int fd,int64_t assetOffset,int64_t assetLength,int32_t deviceId,bool exclusive,int64_t startMs,bool autoStart){
    std::lock_guard<std::mutex> lock(mutex_); closeLocked(); error_.clear(); wav_=WavInfo{}; ended_=false;
    sourceFd_=dup(fd); if(sourceFd_<0){ error_="FD_DUP_FAILED"; return statusLocked(); }
    if(!parseWav(sourceFd_,assetOffset,assetLength,wav_,error_)){ closeLocked(); return statusLocked(); }
    const int64_t startFrame=std::clamp<int64_t>(startMs*int64_t(wav_.sampleRate)/1000,0,wav_.totalFrames);
    ringFrames_=std::max<size_t>(4096,size_t(wav_.sampleRate)); ring_.assign(ringFrames_*2,0);
    if(!openStreamLocked(deviceId,exclusive)){ closeLocked(); return statusLocked(); }
    startDecoderLocked(startFrame); for(int i=0;i<100&&writeFrame_.load()==0&&!decoderEof_.load();++i)usleep(1000);
    if(autoStart){ const auto r=stream_->requestStart(); started_=r==oboe::Result::OK; if(!started_.load())error_=oboe::convertToText(r); } else started_=false;
    return statusLocked();
}

std::string GwAudioEngine::seek(int64_t targetMs,bool resume){
    std::lock_guard<std::mutex> lock(mutex_);
    if(!stream_||wav_.sampleRate==0){ error_="STREAM_NOT_OPEN"; return statusLocked(); }
    error_.clear(); const int64_t target=std::clamp<int64_t>(targetMs*int64_t(wav_.sampleRate)/1000,0,wav_.totalFrames);
    auto state=stream_->getState();
    if(state==oboe::StreamState::Started||state==oboe::StreamState::Starting){
        const auto r=stream_->requestPause(); if(r!=oboe::Result::OK){ error_=oboe::convertToText(r); return statusLocked(); }
        waitStreamState(oboe::StreamState::Paused,500); state=stream_->getState();
    }else if(state==oboe::StreamState::Pausing){ waitStreamState(oboe::StreamState::Paused,500); state=stream_->getState(); }
    if(state==oboe::StreamState::Paused){
        const auto r=stream_->requestFlush(); if(r!=oboe::Result::OK){ error_=oboe::convertToText(r); return statusLocked(); }
        waitStreamState(oboe::StreamState::Flushed,500); state=stream_->getState();
    }else if(state==oboe::StreamState::Flushing){ waitStreamState(oboe::StreamState::Flushed,500); state=stream_->getState(); }
    const bool inactive=state==oboe::StreamState::Open||state==oboe::StreamState::Stopped||state==oboe::StreamState::Flushed;
    if(!inactive){ error_="SEEK_STREAM_STATE"; return statusLocked(); }
    stopDecoderLocked(); std::fill(ring_.begin(),ring_.end(),0); startDecoderLocked(target);
    for(int i=0;i<100&&writeFrame_.load()==0&&!decoderEof_.load();++i)usleep(1000);
    if(resume){ const auto r=stream_->requestStart(); started_=r==oboe::Result::OK; if(!started_.load())error_=oboe::convertToText(r); } else started_=false;
    return statusLocked();
}

void GwAudioEngine::decoderLoop(){
    const size_t chunk=512; std::vector<uint8_t> raw(chunk*wav_.blockAlign); int64_t frame=positionFrames_.load();
    while(!stopDecoder_.load()&&frame<wav_.totalFrames){
        const uint64_t r=readFrame_.load(std::memory_order_acquire),w=writeFrame_.load(std::memory_order_relaxed);
        if(size_t(w-r)+chunk>ringFrames_){ usleep(2000); continue; }
        const size_t frames=std::min<size_t>(chunk,size_t(wav_.totalFrames-frame)); const size_t bytes=frames*wav_.blockAlign;
        if(!readAt(sourceFd_,wav_.dataOffset+frame*wav_.blockAlign,raw.data(),bytes))break;
        const int bps=wav_.bits/8;
        for(size_t i=0;i<frames;++i){
            auto sample=[&](int ch)->int32_t{ const uint8_t *p=raw.data()+i*wav_.blockAlign+std::min<int>(ch,wav_.channels-1)*bps;
                if(wav_.format==3){ float f; std::memcpy(&f,p,4); f=std::clamp(f,-1.0f,1.0f); return int32_t(std::lrint(double(f)*2147483647.0)); }
                if(wav_.bits==16)return int32_t(int16_t(le16(p)))*65536;
                if(wav_.bits==24){ int32_t v=int32_t(p[0])|(int32_t(p[1])<<8)|(int32_t(p[2])<<16); if(v&0x800000)v|=~0xffffff; return int32_t(int64_t(v)*256); }
                return int32_t(le32(p)); };
            const size_t at=size_t((w+i)%ringFrames_)*2; ring_[at]=sample(0); ring_[at+1]=sample(wav_.channels>1?1:0);
        }
        writeFrame_.store(w+frames,std::memory_order_release); frame+=frames;
    }
    decoderEof_=true;
}

oboe::DataCallbackResult GwAudioEngine::onAudioReady(oboe::AudioStream*,void *audioData,int32_t numFrames){
    auto *out=static_cast<int32_t*>(audioData); const uint64_t r=readFrame_.load(std::memory_order_relaxed),w=writeFrame_.load(std::memory_order_acquire);
    const size_t take=std::min<size_t>(numFrames,size_t(w-r));
    for(size_t i=0;i<take;++i){ const size_t at=size_t((r+i)%ringFrames_)*2; out[i*2]=ring_[at]; out[i*2+1]=ring_[at+1]; }
    if(take<size_t(numFrames))std::memset(out+take*2,0,(size_t(numFrames)-take)*2*sizeof(int32_t));
    readFrame_.store(r+take,std::memory_order_release); positionFrames_.fetch_add(int64_t(take));
    if(decoderEof_.load()&&r+take>=w){ ended_=true; started_=false; return oboe::DataCallbackResult::Stop; }
    return stopDecoder_.load()?oboe::DataCallbackResult::Stop:oboe::DataCallbackResult::Continue;
}

std::string GwAudioEngine::start(){
    std::lock_guard<std::mutex> lock(mutex_); if(!stream_){ error_="STREAM_NOT_OPEN"; return statusLocked(); }
    if(stream_->getState()==oboe::StreamState::Started){ started_=true; return statusLocked(); }
    error_.clear(); const auto r=stream_->requestStart(); started_=r==oboe::Result::OK; if(!started_.load())error_=oboe::convertToText(r); return statusLocked();
}
std::string GwAudioEngine::stop(){
    std::lock_guard<std::mutex> lock(mutex_); if(!stream_){ started_=false; return statusLocked(); }
    error_.clear(); auto s=stream_->getState();
    if(s==oboe::StreamState::Started||s==oboe::StreamState::Starting){ const auto r=stream_->requestPause(); if(r!=oboe::Result::OK)error_=oboe::convertToText(r); else waitStreamState(oboe::StreamState::Paused,500); }
    started_=false; return statusLocked();
}
std::string GwAudioEngine::close(){ std::lock_guard<std::mutex> lock(mutex_); closeLocked(); return statusLocked(); }
std::string GwAudioEngine::status(){ std::lock_guard<std::mutex> lock(mutex_); return statusLocked(); }

void GwAudioEngine::closeLocked(){
    stopDecoder_=true; if(stream_)stream_->requestStop(); if(decoderThread_.joinable())decoderThread_.join();
    if(stream_){ stream_->close(); stream_.reset(); } if(sourceFd_>=0){ ::close(sourceFd_); sourceFd_=-1; }
    ring_.clear(); ringFrames_=0; started_=false; ended_=false;
}

std::string GwAudioEngine::statusLocked()const{
    const bool open=bool(stream_); const int32_t rate=open?stream_->getSampleRate():0,ch=open?stream_->getChannelCount():0,dev=open?stream_->getDeviceId():-1;
    const bool i32=open&&stream_->getFormat()==oboe::AudioFormat::I32; const bool verified=open&&i32&&rate==int32_t(wav_.sampleRate)&&error_.empty();
    const char *share=open?sharingName(stream_->getSharingMode()):"NONE",*api="NONE";
    if(open){ const auto a=stream_->getAudioApi(); api=a==oboe::AudioApi::AAudio?"AAUDIO":(a==oboe::AudioApi::OpenSLES?"OPENSL_ES":"UNSPECIFIED"); }
    const char *src=wav_.sampleRate==0?"NONE":(wav_.format==3?"WAV_FLOAT32":"WAV_PCM_INTEGER");
    const char *state=ended_.load()?"ENDED":(started_.load()?"STARTED":(open?"PAUSED":"CLOSED"));
    const int64_t pos=positionFrames_.load(),posMs=wav_.sampleRate?pos*1000/int64_t(wav_.sampleRate):0,durMs=wav_.sampleRate?wav_.totalFrames*1000/int64_t(wav_.sampleRate):0;
    std::ostringstream j; j<<"{"<<"\"ok\":"<<(open&&error_.empty()?"true":"false")<<",\"nativeWav\":"<<(wav_.sampleRate?"true":"false")
      <<",\"streamVerified\":"<<(verified?"true":"false")<<",\"actualFormat\":\""<<(open?(i32?"I32":"OTHER"):"NONE")<<"\""
      <<",\"sourceFormat\":\""<<src<<"\",\"sourceRate\":"<<wav_.sampleRate<<",\"actualRate\":"<<rate<<",\"sourceBits\":"<<wav_.bits
      <<",\"sourceChannels\":"<<wav_.channels<<",\"actualChannels\":"<<ch<<",\"audioApi\":\""<<api<<"\",\"actualSharing\":\""<<share<<"\""
      <<",\"actualDeviceId\":"<<dev<<",\"requestedExclusive\":"<<(requestedExclusive_?"true":"false")<<",\"requestedDeviceId\":"<<requestedDeviceId_
      <<",\"state\":\""<<state<<"\",\"positionFrames\":"<<pos<<",\"durationFrames\":"<<wav_.totalFrames<<",\"positionMs\":"<<posMs
      <<",\"durationMs\":"<<durMs<<",\"error\":\""<<esc(error_)<<"\"}"; return j.str();
}
