@echo off
:: Launches AlbertaPOS elevated. Double-click this file before running mvn clean test.
:: If not already admin, re-launches this script requesting elevation via UAC.
net session >nul 2>nul
if %errorlevel% == 0 (
    start "" "C:\Program Files (x86)\Alberta Payments LLC\AlbertaPOS\AlbertaPOS.exe"
    echo AlbertaPOS launched. You can now run: mvn clean test
    timeout /t 3
) else (
    powershell -Command "Start-Process -FilePath '%~f0' -Verb RunAs"
)
