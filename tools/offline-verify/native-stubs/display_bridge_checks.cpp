#include <jni.h>
#include <android/native_window.h>
#include <cstdio>
#include <cstring>
#include <vector>
#include <cstdint>
// Stand-ins for the ANativeWindow the real bridge posts to.
static std::vector<uint8_t> g_dst; static ANativeWindow_Buffer g_buf;
static ANativeWindow* FAKE = (ANativeWindow*)1;
extern "C" {
int __android_log_print(int,const char*,const char*,...){return 0;}
int32_t ANativeWindow_setBuffersGeometry(ANativeWindow*,int32_t,int32_t,int32_t){return 0;}
int32_t ANativeWindow_lock(ANativeWindow*,ANativeWindow_Buffer* b,ARect*){*b=g_buf;return 0;}
int32_t ANativeWindow_unlockAndPost(ANativeWindow*){return 0;}
void ANativeWindow_release(ANativeWindow*){}
ANativeWindow* ANativeWindow_fromSurface(JNIEnv*,jobject){return FAKE;}
}
#include "display_bridge.cpp"
using namespace linuxdroid;
static int fails=0;
void check(bool c,const char* m){printf(c?"  PASS %s\n":"  FAIL %s\n",m); if(!c)fails++;}

int main(){
  // Destination window: 4x2 with padded stride of 8 px, exercising dst padding.
  const int W=4,H=2,DSTRIDE=8;
  g_dst.assign(DSTRIDE*H*4, 0xCD);
  g_buf.width=W; g_buf.height=H; g_buf.stride=DSTRIDE; g_buf.format=1; g_buf.bits=g_dst.data();
  auto& db = DisplayBridge::getInstance();
  // Attach the fake window through the public entry point.
  db.onSurfaceCreated(nullptr,(jobject)1,W,H);

  // Source: stride padded to 6 px, deliberately != W*4, in BGRA order.
  const int SSTRIDE=6*4;
  std::vector<uint8_t> src(SSTRIDE*H, 0);
  for(int y=0;y<H;y++)for(int x=0;x<W;x++){
    uint8_t* p=&src[y*SSTRIDE+x*4];
    p[0]=10+x; p[1]=20+x; p[2]=30+x; p[3]=40+x;   // B,G,R,A
  }
  auto st=db.presentFrame(src.data(),src.size(),W,H,SSTRIDE,DisplayBridge::kSourceBgra8888);
  check(st==DisplayBridge::kPresentOk,"BGRA present returns OK");
  // Expect R,G,B,A == 30+x,20+x,10+x,40+x
  bool ok=true;
  for(int y=0;y<H;y++)for(int x=0;x<W;x++){
    uint8_t* d=&g_dst[y*DSTRIDE*4+x*4];
    if(d[0]!=30+x||d[1]!=20+x||d[2]!=10+x||d[3]!=40+x) ok=false;
  }
  check(ok,"BGRA->RGBA swaps red/blue and preserves alpha");
  // Padding beyond the copied width must be untouched.
  check(g_dst[(0*DSTRIDE+W)*4]==0xCD,"destination row padding is not overwritten");

  // BGRX must force alpha opaque.
  for(int y=0;y<H;y++)for(int x=0;x<W;x++) src[y*SSTRIDE+x*4+3]=0x00;
  db.presentFrame(src.data(),src.size(),W,H,SSTRIDE,DisplayBridge::kSourceBgrx8888);
  check(g_dst[3]==0xFF,"BGRX forces alpha opaque");

  // RGBA is a straight copy.
  for(int y=0;y<H;y++)for(int x=0;x<W;x++){uint8_t*p=&src[y*SSTRIDE+x*4];p[0]=1;p[1]=2;p[2]=3;p[3]=4;}
  db.presentFrame(src.data(),src.size(),W,H,SSTRIDE,DisplayBridge::kSourceRgba8888);
  check(g_dst[0]==1&&g_dst[1]==2&&g_dst[2]==3&&g_dst[3]==4,"RGBA copied verbatim");

  // Validation.
  check(db.presentFrame(src.data(),src.size(),0,H,SSTRIDE,0)==DisplayBridge::kPresentBadGeometry,"zero width rejected");
  check(db.presentFrame(src.data(),src.size(),W,H,4,0)==DisplayBridge::kPresentBadGeometry,"stride < row rejected");
  check(db.presentFrame(src.data(),8,W,H,SSTRIDE,0)==DisplayBridge::kPresentBadGeometry,"short buffer rejected");
  check(db.presentFrame(src.data(),src.size(),W,H,SSTRIDE,99)==DisplayBridge::kPresentUnsupportedFormat,"bad format rejected");
  check(db.presentFrame(nullptr,src.size(),W,H,SSTRIDE,0)==DisplayBridge::kPresentBadGeometry,"null pixels rejected");

  // Frame larger than the window (mid-resize) must clip, not overrun.
  std::vector<uint8_t> big(16*4*8,7);
  check(db.presentFrame(big.data(),big.size(),16,8,16*4,DisplayBridge::kSourceRgba8888)==DisplayBridge::kPresentOk,"oversized frame clips instead of overrunning");

  // Surface loss.
  db.onSurfaceDestroyed();
  check(db.presentFrame(src.data(),src.size(),W,H,SSTRIDE,0)==DisplayBridge::kPresentNoWindow,"present after destroy returns NoWindow");
  printf(fails? "\nNATIVE FAILURES: %d\n":"\nALL NATIVE CHECKS PASSED\n",fails);
  return fails?1:0;
}
