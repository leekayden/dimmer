@echo off
rem Rebuilds ScreenDimmer.jar from source (requires a JDK on PATH)
cd /d "%~dp0"
javac -cp "jna-5.17.0.jar;flatlaf-3.5.4.jar" -d out ScreenDimmer.java || exit /b 1
if exist jna_x rmdir /s /q jna_x
if exist flatlaf_x rmdir /s /q flatlaf_x
mkdir jna_x
mkdir flatlaf_x
cd jna_x
jar xf ..\jna-5.17.0.jar
cd ..\flatlaf_x
jar xf ..\flatlaf-3.5.4.jar
cd ..
rmdir /s /q jna_x\META-INF 2>nul
del jna_x\module-info.class 2>nul
rmdir /s /q flatlaf_x\META-INF 2>nul
del flatlaf_x\module-info.class 2>nul
jar cfe ScreenDimmer.jar ScreenDimmer -C out . -C jna_x . -C flatlaf_x .
echo Built ScreenDimmer.jar
