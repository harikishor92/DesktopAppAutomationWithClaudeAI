#Requires -RunAsAdministrator
<#
.SYNOPSIS
    Disables UAC (User Account Control) on a DEDICATED AUTOMATION VM.

.DESCRIPTION
    Sets EnableLUA=0 in the registry and optionally disables the UAC slider entirely.
    This allows processes to launch executables that carry a requireAdministrator manifest
    (such as AlbertaPOS.exe) without triggering a UAC consent prompt.

    SECURITY WARNING — READ BEFORE RUNNING
    =======================================
    UAC is a defence-in-depth boundary. Disabling it means:
      - ANY process (including malware) can gain SYSTEM-level access without a prompt.
      - There is no integrity-level separation between user applications.
      - This machine must NOT be used for general-purpose work, browsing, or email.
      - It must NOT be accessible from the internet without a firewall/VPN layer.

    VALID USAGE
    ===========
    This script is ONLY appropriate for:
      1. Dedicated CI/CD automation agents (no interactive user sessions).
      2. Isolated VMs that run ONLY the POS automation suite.
      3. Air-gapped or VPN-only network segments.

    DO NOT run this on:
      - Developer workstations
      - Shared build servers
      - Production systems
      - Any machine accessed by non-automation users

.PARAMETER Force
    Skip the interactive confirmation prompt (useful in CI bootstrap scripts).

.EXAMPLE
    .\disable-uac-automation-vm.ps1
    .\disable-uac-automation-vm.ps1 -Force
#>

[CmdletBinding()]
param(
    [switch]$Force
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

# ── Environment guard ─────────────────────────────────────────────────────────
Write-Host ""
Write-Host "╔══════════════════════════════════════════════════════════════╗" -ForegroundColor Yellow
Write-Host "║  UAC DISABLE — AUTOMATION VM ONLY                           ║" -ForegroundColor Yellow
Write-Host "╚══════════════════════════════════════════════════════════════╝" -ForegroundColor Yellow
Write-Host ""
Write-Host "  SECURITY IMPACT:" -ForegroundColor Red
Write-Host "    • ALL processes will run at HIGH integrity with no prompt."  -ForegroundColor Red
Write-Host "    • Malware / rogue code gains unrestricted system access."    -ForegroundColor Red
Write-Host "    • This machine must be a DEDICATED, ISOLATED automation VM." -ForegroundColor Red
Write-Host ""
Write-Host "  VALID for: CI agents, isolated test VMs, air-gapped networks." -ForegroundColor Green
Write-Host "  NEVER for: dev workstations, shared servers, internet machines." -ForegroundColor Red
Write-Host ""

if (-not $Force) {
    $answer = Read-Host "Type YES to confirm this is a dedicated automation VM and proceed"
    if ($answer -ne 'YES') {
        Write-Host "Aborted — no changes made." -ForegroundColor Cyan
        exit 0
    }
}

# ── Registry changes ──────────────────────────────────────────────────────────
$policyKey = 'HKLM:\SOFTWARE\Microsoft\Windows NT\CurrentVersion\Policies\System'

Write-Host ""
Write-Host "Applying registry changes..." -ForegroundColor Cyan

# EnableLUA=0: disables UAC entirely; all processes run with full admin token.
Set-ItemProperty -Path $policyKey -Name 'EnableLUA' -Value 0 -Type DWord
Write-Host "  [SET] EnableLUA = 0" -ForegroundColor Green

# ConsentPromptBehaviorAdmin=0: suppress the consent prompt even if UAC is partially on.
Set-ItemProperty -Path $policyKey -Name 'ConsentPromptBehaviorAdmin' -Value 0 -Type DWord
Write-Host "  [SET] ConsentPromptBehaviorAdmin = 0" -ForegroundColor Green

# PromptOnSecureDesktop=0: prevent secure-desktop lockout (belt-and-suspenders).
Set-ItemProperty -Path $policyKey -Name 'PromptOnSecureDesktop' -Value 0 -Type DWord
Write-Host "  [SET] PromptOnSecureDesktop = 0" -ForegroundColor Green

# ── Update config flag ────────────────────────────────────────────────────────
$configPath = Join-Path $PSScriptRoot '..\src\test\resources\config.properties'
if (Test-Path $configPath) {
    (Get-Content $configPath) -replace '^automationVmMode=.*', 'automationVmMode=true' |
        Set-Content $configPath -Encoding utf8
    Write-Host "  [SET] config.properties: automationVmMode=true" -ForegroundColor Green
} else {
    Write-Host "  [SKIP] config.properties not found at expected path — set automationVmMode=true manually" -ForegroundColor Yellow
}

# ── Summary ───────────────────────────────────────────────────────────────────
Write-Host ""
Write-Host "╔══════════════════════════════════════════════════════════════╗" -ForegroundColor Green
Write-Host "║  UAC disabled successfully.                                  ║" -ForegroundColor Green
Write-Host "║  A REBOOT is required for the changes to take effect.        ║" -ForegroundColor Green
Write-Host "╚══════════════════════════════════════════════════════════════╝" -ForegroundColor Green
Write-Host ""

$reboot = if ($Force) { 'n' } else { Read-Host "Reboot now? (y/n)" }
if ($reboot -eq 'y') {
    Write-Host "Rebooting..." -ForegroundColor Cyan
    Restart-Computer -Force
}
