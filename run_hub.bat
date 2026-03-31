@echo off
setlocal enabledelayedexpansion
title Scrcpy Remote Hub - Setup ^& Launch

:: --- Configuration ---
:: We are fetching a stable 64-bit release of Scrcpy
set SCRCPY_VER=v2.4
set ZIP_NAME=scrcpy-win64-%SCRCPY_VER%.zip
set DOWNLOAD_URL=https://github.com/Genymobile/scrcpy/releases/download/%SCRCPY_VER%/%ZIP_NAME%

echo ========================================================
echo       SCRCPY REMOTE HUB - ENTERPRISE LAUNCHER
echo ========================================================
echo.

:: --- Check for Existing Tools ---
if exist "tools\scrcpy.exe" if exist "tools\adb.exe" (
    echo [OK] Scrcpy and ADB binaries found.
    goto :COMPILE
)

:: --- Prompt for Download ---
echo [WARNING] Required binaries (adb.exe, scrcpy.exe) are missing.
echo The application requires the official Scrcpy %SCRCPY_VER% binaries to function.
echo.
choice /c YN /m "Would you like to automatically download and extract them now?"
if errorlevel 2 goto :MANUAL_SETUP
if errorlevel 1 goto :DOWNLOAD_TOOLS

:: --- Download & Extract Logic ---
:DOWNLOAD_TOOLS
echo.
echo --------------------------------------------------------
echo   Fetching Scrcpy %SCRCPY_VER% from GitHub...
echo --------------------------------------------------------
if not exist "tools" mkdir tools

echo [1/3] Downloading %ZIP_NAME%... (Please wait, this takes a minute)
powershell -Command "(New-Object Net.WebClient).DownloadFile('%DOWNLOAD_URL%', 'scrcpy_temp.zip')"

if not exist "scrcpy_temp.zip" (
    echo [ERROR] Download failed. Please check your internet connection.
    goto :MANUAL_SETUP
)

echo [2/3] Extracting files...
powershell -Command "Expand-Archive -Path 'scrcpy_temp.zip' -DestinationPath 'scrcpy_extracted' -Force"

echo [3/3] Installing tools...
xcopy /E /Y /Q "scrcpy_extracted\scrcpy-win64-%SCRCPY_VER%\*" "tools\" > nul

echo Cleaning up temporary files...
del "scrcpy_temp.zip"
rmdir /S /Q "scrcpy_extracted"

if exist "tools\scrcpy.exe" (
    echo [SUCCESS] Tools successfully installed!
    echo.
    goto :COMPILE
) else (
    echo [ERROR] Something went wrong during extraction.
    goto :MANUAL_SETUP
)

:: --- Manual Fallback ---
:MANUAL_SETUP
echo.
echo --------------------------------------------------------
echo   MANUAL SETUP REQUIRED
echo --------------------------------------------------------
echo Please download Scrcpy manually from: 
echo https://github.com/Genymobile/scrcpy/releases
echo Extract the ZIP and place ALL files into the 'tools' folder.
echo.
pause
exit /b

:: --- Compile & Launch ---
:COMPILE
echo --------------------------------------------------------
echo   Compiling ScrcpyRemoteHub...
echo --------------------------------------------------------

:: Create the bin folder if it doesn't exist
if not exist "bin" mkdir bin

:: Compile the Java file AND send all .class files into the bin folder
javac -d bin ScrcpyRemoteHub.java

if %ERRORLEVEL% neq 0 (
    echo.
    echo [ERROR] Compilation Failed! Please ensure Java JDK is installed.
    pause
    exit /b
)

echo [SUCCESS] Compilation successful!
echo.
echo Launching Scrcpy Remote Hub...

:: Launch the app by telling Java to look inside the bin folder for the compiled classes
java -cp bin ScrcpyRemoteHub