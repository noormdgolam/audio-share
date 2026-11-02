@echo off
rem Audio Share Plus - start the PC audio server in high quality (24-bit / 48 kHz stereo)
rem Usage: Start-AudioShare-HQ.bat [LAN-IP]   e.g. Start-AudioShare-HQ.bat 192.168.1.20
rem Without an IP, as-cmd picks the first LAN address (can be a WSL/VPN adapter - pass the IP then).
rem Put this file next to as-cmd.exe (from audio-share-server-cmd-windows.zip).
taskkill /IM as-cmd.exe /F >nul 2>&1
cd /d "%~dp0"
if "%~1"=="" (
  start "Audio Share" /min as-cmd.exe -b --encoding=s24 --channels=2 --sample-rate=48000
) else (
  start "Audio Share" /min as-cmd.exe --bind=%~1 --encoding=s24 --channels=2 --sample-rate=48000
)
