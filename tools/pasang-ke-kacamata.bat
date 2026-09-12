@echo off
REM Pasang aplikasi PDF HUD ke Rokid Glasses lewat kabel development.
REM Letakkan file ini, 2-PASANG-DI-KACAMATA.apk, dan folder platform-tools (berisi adb.exe) di folder yang sama.
cd /d "%~dp0"
set ADB=adb
if exist "platform-tools\adb.exe" set ADB=platform-tools\adb.exe

echo Mengecek kacamata...
%ADB% devices
echo.
echo Memasang aplikasi (izin Bluetooth langsung diberikan)...
%ADB% install -r -g "2-PASANG-DI-KACAMATA.apk"
if errorlevel 1 (
  echo.
  echo GAGAL. Pastikan: kabel development terpasang, ADB aktif lewat Hi Rokid, dan kacamata muncul di daftar di atas.
  pause
  exit /b 1
)
echo.
echo Membuka aplikasi PDF HUD di kacamata...
%ADB% shell am start -n id.nala.rokidpdf.glasses/.MainActivity
echo.
echo SELESAI. Sekarang buka aplikasi "PDF ke Rokid" di HP.
pause
