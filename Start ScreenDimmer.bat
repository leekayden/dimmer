@echo off
rem ScreenDimmer launcher - runs headless (no console window)
cd /d "%~dp0"
start "ScreenDimmer" /b javaw -Xmx48m -XX:+UseSerialGC -jar ScreenDimmer.jar
