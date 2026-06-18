@echo off
:: Runs mvn clean test from an elevated (Administrator) PowerShell window.
:: This allows the test JVM to launch AlbertaPOS (requireAdministrator manifest)
:: without needing a separate UAC prompt for the app.
:: UAC will prompt ONCE to elevate this batch file — click Yes.
net session >nul 2>nul
if %errorlevel% == 0 (
    cd /d "%~dp0"
    mvn clean test
) else (
    powershell -Command "Start-Process -FilePath '%~f0' -Verb RunAs -WorkingDirectory '%~dp0'"
)
