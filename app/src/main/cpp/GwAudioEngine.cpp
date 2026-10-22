#include "GwAudioEngine.h"
#include <algorithm>
#include <cmath>
#include <cstring>
#include <fcntl.h>
#include <sstream>
#include <sys/stat.h>
#include <unistd.h>

namespace {
uint16_t le16(const uint8_t* p) { return uint16_t(p[0]) | (uint16_t(p[1]) << 8); }
uint32_t le32(const uint8_t* p) { return uint32_t(p[0]) | (uint32_t(p[1]) << 8) | (uint32_t(p[2]) << 16) | (uint32_t(p[3]) << 24); }
bool readAt(int fd, int64_t off, void* dst, size_t n) {
    auto* p = static_cast<uint8_t*>(dst); size_t done = 0;
    while (done < n) { ssize_t r = pread(fd, p + done, n - done, off + int64_t(done)); if (r <= 0) return false; done += size_t(r); }
    return true;
}
const char* sharingName(oboe::SharingMode m) { return m == oboe::SharingMode::Exclusive ? "EXCLUSIVE" : "SHARED"; }
std::string esc(const std::string& s) { std::string o; for(char c:s){ if(c=='"'||c=='\\')o+='\\'; if(c=='\n')o+="\\n"; else o+=c; } return o; }
}

GwAudioEngine& GwAudioEngine::instance() { static GwAudioEngine e; return e; }
GwAudioEngine::~GwAudioEngine() { std::lock_guard<std::mutex> l(mutex_); closeLocked(); }

bool GwAudioEngine::parseWav(int fd, int64_t base, int64_t length, WavInfo& out, std::string& error) {
    uint8_t head[12];
    if (!readAt(fd, base, head, sizeof(head)) || std::memcmp(head,"RIFF",4)!=0 || std::memcmp(head+8,"WAVE",4)!=0) {
        error="WAV_RIFF_REQUIRED"; return false;
    }
    struct stat st{}; if (length <= 0 && fstat(fd,&st)==0) length = st.st_size - base;
    int64_t limit = length > 0 ? base + length : base + int64_t(le32(head+4)) + 8;
    int64_t pos = base + 12; bool fmt=false, data=false;
    while (pos + 8 <= limit) {
        uint8_t ch[8]; if(!readAt(fd,pos,ch,8)) break;
        uint32_t size=le32(ch+4); int64_t payload=pos+8;
        if(std::memcmp(ch,"fmt ",4)==0 && size>=16){
            uint8_t f[40]{}; size_t take=std::min<size_t>(size,sizeof(f)); if(!readAt(fd,payload,f,take)){error="WAV_FMT_READ";return false;}
            out.format=le16(f); out.channels=le16(f+2); out.sampleRate=le32(f+4); out.blockAlign=le16(f+12); out.bits=le16(f+14); fmt=true;
        } else if(std::memcmp(ch,"data",4)==0){ out.dataOffset=payload; out.dataSize=size; data=true; }
        if(fmt&&data) break;
        pos = payload + size + (size & 1u);
    }
    if(!fmt||!data){error="WAV_CHUNKS_MISSING";return false;}
    if(out.channels<1||out.channels>2){error="WAV_CHANNELS_UNSUPPORTED";return false;}
    if(!((out.format==1&&(out.bits==16||out.bits==24||out.bits==32))||(out.format==3&&out.bits==32))){error="WAV_FORMAT_UNSUPPORTED";return false;}
    if(out.sampleRate<8000||out.sampleRate>384000||out.blockAlign==0){error="WAV_LAYOUT_INVALID";return false;}
    out.totalFrames=out.dataSize/out.blockAlign; return true;
}

bool GwAudioEngine::openStreamLocked(int32_t deviceId, bool exclusive) {
    requestedExclusive_=exclusive;
    oboe::AudioStreamBuilder b;
    b.setDirection(oboe::Direction::Output); b.setAudioApi(oboe::AudioApi::AAudio);
    b.setFormat(oboe::AudioFormat::I32); b.setSampleRate(int32_t(wav_.sampleRate)); b.setChannelCount(2);
    b.setPerformanceMode(oboe::PerformanceMode::LowLatency); b.setUsage(oboe::Usage::Media);
    b.setContentType(oboe::ContentType::Music); b.setSharingMode(exclusive?oboe::SharingMode::Exclusive:oboe::SharingMode::Shared);
    b.setDataCallback(this); if(deviceId>=0)b.setDeviceId(deviceId);
    oboe::Result r=b.openStream(stream_);
    if(r!=oboe::Result::OK&&exclusive){stream_.reset();b.setSharingMode(oboe::SharingMode::Shared);r=b.openStream(stream_);}
    if(r!=oboe::Result::OK||!stream_){error_=oboe::convertToText(r);stream_.reset();return false;}
    if(stream_->getFormat()!=oboe::AudioFormat::I32){error_="STRICT_I32_REJECTED";stream_->close();stream_.reset();return false;}
    if(stream_->getSampleRate()!=int32_t(wav_.sampleRate)){error_="SOURCE_RATE_REJECTED";stream_->close();stream_.reset();return false;}
    return true;
}

std::string GwAudioEngine::loadWav(int fd,int64_t assetOffset,int64_t assetLength,int32_t deviceId,bool exclusive,int64_t startMs){
    std::lock_guard<std::mutex> l(mutex_); closeLocked(); error_.clear();
    sourceFd_=dup(fd); if(sourceFd_<0){error_="FD_DUP_FAILED";return statusLocked();}
    if(!parseWav(sourceFd_,assetOffset,assetLength,wav_,error_)){closeLocked();return statusLocked();}
    int64_t startFrame=std::clamp<int64_t>(startMs*int64_t(wav_.sampleRate)/1000,0,wav_.totalFrames);
    positionFrames_=startFrame; readFrame_=0; writeFrame_=0; decoderEof_=false; ended_=false; stopDecoder_=false;
    ringFrames_=std::max<size_t>(4096,size_t(wav_.sampleRate)); ring_.assign(ringFrames_*2,0);
    if(!openStreamLocked(deviceId,exclusive)){closeLocked();return statusLocked();}
    decoderThread_=std::thread(&GwAudioEngine::decoderLoop,this);
    for(int i=0;i<100&&writeFrame_.load()==0&&!decoderEof_.load();++i) usleep(1000);
    oboe::Result r=stream_->requestStart(); started_=r==oboe::Result::OK;
    if(!started_) error_=oboe::convertToText(r);
    return statusLocked();
}

void GwAudioEngine::decoderLoop(){
    const size_t chunkFrames=512; std::vector<uint8_t> raw(chunkFrames*wav_.blockAlign);
    int64_t frame=positionFrames_.load();
    while(!stopDecoder_.load()&&frame<wav_.totalFrames){
        uint64_t r=readFrame_.load(std::memory_order_acquire), w=writeFrame_.load(std::memory_order_relaxed);
        size_t free=ringFrames_-size_t(w-r); if(free<chunkFrames){usleep(2000);continue;}
        size_t frames=std::min<size_t>(chunkFrames,size_t(wav_.totalFrames-frame)); size_t bytes=frames*wav_.blockAlign;
        if(!readAt(sourceFd_,wav_.dataOffset+frame*wav_.blockAlign,raw.data(),bytes)){break;}
        int bps=wav_.bits/8;
        for(size_t i=0;i<frames;i++){
            auto sample=[&](int c)->int32_t{
                const uint8_t* p=raw.data()+i*wav_.blockAlign+std::min<int>(c,wav_.channels-1)*bps;
                if(wav_.format==3){float f;std::memcpy(&f,p,4);f=std::clamp(f,-1.0f,1.0f);return int32_t(std::lrint(double(f)*2147483647.0));}
                if(wav_.bits==16)return int32_t(int16_t(le16(p)))*65536;
                if(wav_.bits==24){int32_t v=int32_t(p[0])|(int32_t(p[1])<<8)|(int32_t(p[2])<<16);if(v&0x800000)v|=~0xffffff;return int32_t(int64_t(v)*256);}
                return int32_t(le32(p));
            };
            size_t at=size_t((w+i)%ringFrames_)*2; ring_[at]=sample(0); ring_[at+1]=sample(wav_.channels>1?1:0);
        }
        writeFrame_.store(w+frames,std::memory_order_release); frame+=frames;
    }
    decoderEof_=true;
}

oboe::DataCallbackResult GwAudioEngine::onAudioReady(oboe::AudioStream*,void* audioData,int32_t numFrames){
    auto* out=static_cast<int32_t*>(audioData); uint64_t r=readFrame_.load(std::memory_order_relaxed),w=writeFrame_.load(std::memory_order_acquire);
    size_t take=std::min<size_t>(numFrames,size_t(w-r));
    for(size_t i=0;i<take;i++){size_t at=size_t((r+i)%ringFrames_)*2;out[i*2]=ring_[at];out[i*2+1]=ring_[at+1];}
    if(take<size_t(numFrames))std::memset(out+take*2,0,(size_t(numFrames)-take)*2*sizeof(int32_t));
    readFrame_.store(r+take,std::memory_order_release); positionFrames_.fetch_add(int64_t(take));
    if(decoderEof_.load()&&r+take>=w){ended_=true;return oboe::DataCallbackResult::Stop;}
    return stopDecoder_.load()?oboe::DataCallbackResult::Stop:oboe::DataCallbackResult::Continue;
}

std::string GwAudioEngine::start(){std::lock_guard<std::mutex>l(mutex_);if(!stream_){error_="STREAM_NOT_OPEN";return statusLocked();}auto r=stream_->requestStart();started_=r==oboe::Result::OK;if(!started_)error_=oboe::convertToText(r);return statusLocked();}
std::string GwAudioEngine::stop(){std::lock_guard<std::mutex>l(mutex_);if(stream_)stream_->requestStop();started_=false;return statusLocked();}
std::string GwAudioEngine::close(){std::lock_guard<std::mutex>l(mutex_);closeLocked();return statusLocked();}
std::string GwAudioEngine::status(){std::lock_guard<std::mutex>l(mutex_);return statusLocked();}

void GwAudioEngine::closeLocked(){
    stopDecoder_=true; if(stream_){stream_->requestStop();stream_->close();stream_.reset();}
    if(decoderThread_.joinable())decoderThread_.join(); if(sourceFd_>=0){::close(sourceFd_);sourceFd_=-1;}
    ring_.clear();ringFrames_=0;started_=false;
}

std::string GwAudioEngine::statusLocked()const{
    bool open=bool(stream_);int32_t actualRate=open?stream_->getSampleRate():0;const char* share=open?sharingName(stream_->getSharingMode()):"NONE";
    std::ostringstream j;j<<"{"<<"\"ok\":"<<(open&&error_.empty()?"true":"false")
      <<",\"nativeWav\":"<<(wav_.sampleRate>0?"true":"false")<<",\"actualFormat\":\""<<(open?"I32":"NONE")<<"\""
      <<",\"sourceRate\":"<<wav_.sampleRate<<",\"actualRate\":"<<actualRate<<",\"sourceBits\":"<<wav_.bits
      <<",\"sourceChannels\":"<<wav_.channels<<",\"actualSharing\":\""<<share<<"\""
      <<",\"state\":\""<<(ended_.load()?"ENDED":(started_?"STARTED":(open?"OPEN":"CLOSED")))<<"\""
      <<",\"positionFrames\":"<<positionFrames_.load()<<",\"durationFrames\":"<<wav_.totalFrames
      <<",\"error\":\""<<esc(error_)<<"\"}";return j.str();
}
