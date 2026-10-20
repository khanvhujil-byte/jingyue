@echo off
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\open-preview.ps1"
if errorlevel 1 pause
