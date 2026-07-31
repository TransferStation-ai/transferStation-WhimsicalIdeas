@echo off
chcp 65001 >nul
echo === Building native-renderer.dll via CMake ===
echo.

set SCRIPT_DIR=%~dp0
set BUILD_DIR=%SCRIPT_DIR%build

if "%JAVA_HOME%"=="" (
    echo JAVA_HOME is not set.
    echo Set it to your JDK 17+ installation path, e.g.:
    echo   set JAVA_HOME=C:\Program Files\Java\jdk-17
    pause
    exit /b 1
)

where cmake >nul 2>&1
if %ERRORLEVEL% neq 0 (
    echo CMake not found. Install CMake from https://cmake.org/download/
    echo and ensure it is on your PATH.
    pause
    exit /b 1
)

echo Configuring...
cmake -B "%BUILD_DIR%" -S "%SCRIPT_DIR%" "-DJAVA_HOME=%JAVA_HOME%" -DCMAKE_BUILD_TYPE=Release
if %ERRORLEVEL% neq 0 (
    echo CMake configuration failed.
    pause
    exit /b 1
)

echo Building...
cmake --build "%BUILD_DIR%" --config Release -j
if %ERRORLEVEL% neq 0 (
    echo CMake build failed.
    pause
    exit /b 1
)

echo.
echo === Build successful! ===
echo Output: %BUILD_DIR%\native-renderer.dll
echo.
pause
