<#
.SYNOPSIS
    Validates and configures the Windows environment for AlbertaPOS automation.

.DESCRIPTION
    Performs a comprehensive pre-flight check and optional setup:
      1. Verifies Administrator privileges
      2. Checks WinAppDriver installation and version
      3. Starts WinAppDriver as Administrator (if not already running)
      4. Validates AlbertaPOS.exe installation path
      5. Verifies port 4723 is open and WinAppDriver HTTP layer is healthy
      6. Reports UIPI / integrity-level context
      7. Optionally creates a Scheduled Task so WinAppDriver auto-starts at system boot

.PARAMETER StartWinAppDriver
    Start WinAppDriver immediately after validation (default: true).

.PARAMETER CreateStartupTask
    Create a Windows Scheduled Task that starts WinAppDriver at boot as SYSTEM (default: false).

.PARAMETER WinAppDriverPath
    Override the default WinAppDriver.exe path.

.PARAMETER AlbertaPOSPath
    Override the default AlbertaPOS.exe path.

.EXAMPLE
    .\setup-automation-environment.ps1
    .\setup-automation-environment.ps1 -CreateStartupTask
    .\setup-automation-environment.ps1 -StartWinAppDriver:$false
#>

[CmdletBinding()]
param(
    [bool]  $StartWinAppDriver  = $true,
    [switch]$CreateStartupTask,
    [string]$WinAppDriverPath   = 'C:\Program Files (x86)\Windows Application Driver\WinAppDriver.exe',
    [string]$AlbertaPOSPath     = 'C:\Program Files (x86)\Alberta Payments LLC\AlbertaPOS\AlbertaPOS.exe'
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$pass = 0; $warn = 0; $fail = 0
function Pass($msg)  { Write-Host "  [PASS] $msg" -ForegroundColor Green;  $script:pass++ }
function Warn($msg)  { Write-Host "  [WARN] $msg" -ForegroundColor Yellow; $script:warn++ }
function Fail($msg)  { Write-Host "  [FAIL] $msg" -ForegroundColor Red;    $script:fail++ }
function Info($msg)  { Write-Host "  [INFO] $msg" -ForegroundColor Cyan }

Write-Host ""
Write-Host "================================================================" -ForegroundColor Cyan
Write-Host "   AlbertaPOS Automation Environment Setup" -ForegroundColor Cyan
Write-Host "================================================================" -ForegroundColor Cyan
Write-Host ""

# --- 1. Admin privilege check -------------------------------------------------
Write-Host "[ 1 ] Administrator Privilege Validation" -ForegroundColor White
$principal = [System.Security.Principal.WindowsPrincipal][System.Security.Principal.WindowsIdentity]::GetCurrent()
$isAdmin   = $principal.IsInRole([System.Security.Principal.WindowsBuiltInRole]::Administrator)
if ($isAdmin) {
    Pass "Running as Administrator -- WinAppDriver can be auto-started"
} else {
    Fail "NOT running as Administrator -- right-click and choose 'Run as administrator'"
    Write-Host ""
    Write-Host "  To re-run elevated:" -ForegroundColor Yellow
    Write-Host "    Right-click setup-automation-environment.ps1 -> Run with PowerShell (as Admin)" -ForegroundColor Yellow
    Write-Host "  OR open an elevated PowerShell and run:" -ForegroundColor Yellow
    Write-Host "    cd d:\AlbertaPOSAutomationWithClaude" -ForegroundColor Yellow
    Write-Host "    .\setup\setup-automation-environment.ps1" -ForegroundColor Yellow
    Write-Host ""
    exit 1
}
Info "User: $env:USERNAME | Session: $env:SESSIONNAME"

# --- 2. UAC state -------------------------------------------------------------
Write-Host ""
Write-Host "[ 2 ] UAC / Integrity Level" -ForegroundColor White
$policyKey  = 'HKLM:\SOFTWARE\Microsoft\Windows NT\CurrentVersion\Policies\System'
$uacEnabled = (Get-ItemProperty $policyKey -Name EnableLUA -ErrorAction SilentlyContinue).EnableLUA
if ($uacEnabled -eq 0) {
    Warn "UAC is DISABLED (EnableLUA=0) -- automation VM mode active"
    Info "AlbertaPOS.exe will launch without UAC prompts"
} elseif ($uacEnabled -eq 1) {
    Info "UAC is ENABLED (EnableLUA=1) -- standard desktop mode"
    Info "AlbertaPOS.exe launch will require UAC consent (Shell.Application runas fallback)"
    Info "To disable UAC on this VM run: .\setup\disable-uac-automation-vm.ps1"
} else {
    Warn "UAC state unknown (key not found) -- assuming enabled"
}

# --- 3. WinAppDriver installation --------------------------------------------
Write-Host ""
Write-Host "[ 3 ] WinAppDriver Installation" -ForegroundColor White
if (Test-Path $WinAppDriverPath) {
    $wadVersion = (Get-Item $WinAppDriverPath).VersionInfo.FileVersion
    Pass "WinAppDriver found: $WinAppDriverPath (v$wadVersion)"
} else {
    Fail "WinAppDriver.exe NOT found at: $WinAppDriverPath"
    Info "Download: https://github.com/microsoft/WinAppDriver/releases"
}

# --- 4. WinAppDriver process & port 4723 ------------------------------------
Write-Host ""
Write-Host "[ 4 ] WinAppDriver Process and Port 4723" -ForegroundColor White
$wadProcess = Get-Process WinAppDriver -ErrorAction SilentlyContinue
if ($wadProcess) {
    Pass "WinAppDriver process already running (PID $($wadProcess.Id))"
} else {
    Info "WinAppDriver process not found"
    if ($StartWinAppDriver -and (Test-Path $WinAppDriverPath)) {
        Info "Starting WinAppDriver..."
        Start-Process -FilePath $WinAppDriverPath -Verb RunAs
        Start-Sleep -Seconds 4
        $wadProcess = Get-Process WinAppDriver -ErrorAction SilentlyContinue
        if ($wadProcess) {
            Pass "WinAppDriver started (PID $($wadProcess.Id))"
        } else {
            Fail "WinAppDriver did not start -- verify the executable path"
        }
    } else {
        Warn "WinAppDriver not started (StartWinAppDriver=$StartWinAppDriver)"
    }
}

# HTTP health check
try {
    $response = Invoke-WebRequest -Uri 'http://127.0.0.1:4723/status' -TimeoutSec 5 -UseBasicParsing
    if ($response.StatusCode -eq 200) {
        Pass "WinAppDriver HTTP /status -> 200 OK -- ready at http://127.0.0.1:4723"
    } else {
        Warn "WinAppDriver HTTP /status returned $($response.StatusCode)"
    }
} catch {
    Fail "WinAppDriver HTTP health check failed: $_"
    Info "Ensure WinAppDriver is running and listening on port 4723"
}

# Port listener check
$listener = Get-NetTCPConnection -LocalPort 4723 -State Listen -ErrorAction SilentlyContinue
if ($listener) {
    Pass "Port 4723 is listening (PID $($listener[0].OwningProcess))"
} else {
    Warn "Port 4723 not listening -- WinAppDriver may still be initialising"
}

# --- 5. AlbertaPOS installation ----------------------------------------------
Write-Host ""
Write-Host "[ 5 ] AlbertaPOS Installation" -ForegroundColor White
if (Test-Path $AlbertaPOSPath) {
    $posVersion = (Get-Item $AlbertaPOSPath).VersionInfo.FileVersion
    Pass "AlbertaPOS.exe found: $AlbertaPOSPath (v$posVersion)"
} else {
    Fail "AlbertaPOS.exe NOT found at: $AlbertaPOSPath"
    Info "Install AlbertaPOS and update appPath in config.properties"
}

# requireAdministrator manifest check
try {
    $bytes = [System.IO.File]::ReadAllBytes($AlbertaPOSPath)
    $text  = [System.Text.Encoding]::ASCII.GetString($bytes)
    if ($text -match 'requireAdministrator') {
        Info "AlbertaPOS.exe manifest: requireAdministrator=TRUE (always runs elevated)"
    } else {
        Warn "AlbertaPOS.exe manifest: requireAdministrator NOT found -- unexpected"
    }
} catch {
    Warn "Could not read AlbertaPOS.exe manifest: $_"
}

# --- 6. Java and Maven -------------------------------------------------------
Write-Host ""
Write-Host "[ 6 ] Java and Maven" -ForegroundColor White
try {
    $javaOut = java -version 2>&1 | Select-Object -First 1
    Pass "Java: $javaOut"
} catch {
    Warn "Java not found in PATH -- mvn clean test will fail"
}
try {
    $mvnOut = mvn --version 2>&1 | Select-Object -First 1
    Pass "Maven: $mvnOut"
} catch {
    Warn "Maven not found in PATH -- add Maven/bin to system PATH"
}

# --- 7. Optional: create boot startup task -----------------------------------
if ($CreateStartupTask) {
    Write-Host ""
    Write-Host "[ 7 ] WinAppDriver Boot Startup Task" -ForegroundColor White
    $taskName = "WinAppDriver-AutoStart"
    $existing = Get-ScheduledTask -TaskName $taskName -ErrorAction SilentlyContinue
    if ($existing) {
        Warn "Scheduled task '$taskName' already exists -- skipping"
    } else {
        $action     = New-ScheduledTaskAction -Execute $WinAppDriverPath
        $trigger    = New-ScheduledTaskTrigger -AtStartup
        $princObj   = New-ScheduledTaskPrincipal -UserId "SYSTEM" -LogonType ServiceAccount -RunLevel Highest
        $settings   = New-ScheduledTaskSettingsSet -ExecutionTimeLimit (New-TimeSpan -Minutes 0)
        Register-ScheduledTask -TaskName $taskName -Action $action -Trigger $trigger `
            -Principal $princObj -Settings $settings `
            -Description "Auto-start WinAppDriver as SYSTEM at boot for POS automation" | Out-Null
        Pass "Scheduled task '$taskName' created -- WinAppDriver auto-starts at boot"
        Info "To remove: Unregister-ScheduledTask -TaskName '$taskName' -Confirm:`$false"
    }
}

# --- Summary -----------------------------------------------------------------
Write-Host ""
Write-Host "================================================================" -ForegroundColor Cyan
Write-Host "  Environment Setup Summary" -ForegroundColor Cyan
Write-Host "================================================================" -ForegroundColor Cyan

$summaryColor = if ($fail -gt 0) { 'Red' } elseif ($warn -gt 0) { 'Yellow' } else { 'Green' }
Write-Host "  PASS: $pass   WARN: $warn   FAIL: $fail" -ForegroundColor $summaryColor
Write-Host ""

if ($fail -gt 0) {
    Write-Host "  Fix the FAIL items above before running the test suite." -ForegroundColor Red
    exit 1
} elseif ($warn -gt 0) {
    Write-Host "  WARN items are non-blocking -- review for stability." -ForegroundColor Yellow
    Write-Host "  Ready to run: mvn clean test" -ForegroundColor Green
} else {
    Write-Host "  All checks passed. Run: mvn clean test" -ForegroundColor Green
}
Write-Host ""
