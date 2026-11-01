@echo off
rem Checks the graphics card both ways (OpenCL, then CUDA) and keeps the report in graphics-card-report.txt.
cd /d "%~dp0"
echo Checking the graphics card. The first run also downloads Minecraft's files (about 70 MB), which takes a minute.
echo.
(
  echo === OpenCL ===
  call "Alloy Seed Finder.bat" gpu --gpu-api opencl
  echo.
  echo === CUDA ===
  call "Alloy Seed Finder.bat" gpu --gpu-api cuda
) > graphics-card-report.txt 2>&1
type graphics-card-report.txt
echo.
echo Saved as graphics-card-report.txt in this folder. Send that file back.
pause
