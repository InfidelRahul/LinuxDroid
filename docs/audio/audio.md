# LinuxDroid — Audio Subsystem

## 1. Pipeline
```
Linux Application (ALSA / PulseAudio)
               ↓
PulseAudio UNIX Socket Server / PCM stream
               ↓
`AudioBridge` (native C++ sink)
               ↓
Android Native Audio (AAudio / AudioTrack)
               ↓
Hardware Speaker / Headphones
```

## 2. Metrics & Latency
- Native PCM playback at 44.1 kHz / 48 kHz stereo 16-bit PCM.
- Low-latency buffer tuning (~20ms latency).

