@echo off
REM Pasang aplikasi Nala HUD ke Rokid Glasses lewat kabel development.
REM Letakkan file ini, 2-NalaHUD-KACAMATA.apk, dan folder platform-tools (berisi adb.exe) di folder yang sama.
cd /d "%~dp0"
set ADB=adb
if exist "platform-tools\adb.exe" set ADB=platform-tools\adb.exe

echo Mengecek kacamata...
%ADB% devices
echo.
echo Memasang aplikasi (izin Bluetooth langsung diberikan)...
%ADB% install -r -g "2-NalaHUD-KACAMATA.apk"
if errorlevel 1 (
  echo.
  echo GAGAL. Pastikan: kabel development terpasang, ADB aktif lewat Hi Rokid, dan kacamata muncul di daftar di atas.
  pause
  exit /b 1
)
echo.
echo Membuka aplikasi Nala HUD di kacamata...
%ADB% shell am start -n id.nala.rokidpdf.glasses/.MainActivity
echo.
echo SELESAI. Sekarang buka aplikasi "Nala HUD" di HP.
pause
