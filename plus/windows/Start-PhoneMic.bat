@echo off
rem Audio Share Plus - phone mic -> PC receiver (Windows)
rem Receives the "Phone mic -> PC" stream (48 kHz, mono, 16-bit PCM over TCP) on port 65531
rem and plays it into an output device. Use "CABLE Input" (VB-Audio Virtual Cable) as the
rem device so other apps can pick "CABLE Output" as their microphone.
rem Needs mpv on PATH (https://mpv.io).
rem Usage: Start-PhoneMic.bat [mpv-audio-device]     list devices: mpv --audio-device=help
set DEV=%~1
if "%DEV%"=="" set DEV=auto
:loop
mpv --no-video --really-quiet --profile=low-latency --untimed --cache=no ^
  --demuxer=rawaudio --demuxer-rawaudio-format=s16le --demuxer-rawaudio-rate=48000 ^
  --demuxer-rawaudio-channels=1 --audio-buffer=0.05 --audio-device=%DEV% ^
  "tcp://0.0.0.0:65531?listen"
rem the phone stopped or reconnected -> listen again
goto loop
