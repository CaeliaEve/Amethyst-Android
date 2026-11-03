@echo off
set "JAVA_HOME=C:\Users\Caelia\AppData\Local\Programs\Microsoft\jdk-25.0.3.9-hotspot"
set "PATH=%JAVA_HOME%\bin;%PATH%"
cd /d "E:\gemini\Amethyst-Project\Amethyst-Android"
echo Using Java:
"%JAVA_HOME%\bin\java.exe" -version
call gradlew.bat :app_pojavlauncher:assembleDebug --stacktrace
