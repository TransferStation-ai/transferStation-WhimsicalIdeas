@echo off
echo === Building native-renderer.dll ===
echo.
echo The preferred way: just run 'gradlew build' in IDEA's Gradle panel.
echo Gradle will auto-detect Visual Studio and compile everything.
echo.
echo If you want to build manually, open a "Developer Command Prompt for VS 2022"
echo and run:
echo   cl /nologo /O2 /MT /EHsc /std:c++17 ^
echo       /Isrc\main\native\include ^
echo       /I%%JAVA_HOME%%\include ^
echo       /I%%JAVA_HOME%%\include\win32 ^
echo       src\main\native\src\*.cpp ^
echo       /link /DLL /OUT:native-renderer.dll ^
echo       opengl32.lib user32.lib gdi32.lib ntdll.lib
echo.
pause
