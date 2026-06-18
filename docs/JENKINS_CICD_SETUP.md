# Jenkins CI/CD Setup — AlbertaPOS UI Automation

---

## The Core Challenge — Windows Desktop UI ≠ Standard Headless CI

WinAppDriver requires:
1. A **real Windows desktop session** (not Session 0 / Windows Service isolation)
2. The test JVM running as **Administrator**
3. AlbertaPOS and WinAppDriver **installed** on the same machine

A standard Jenkins Windows agent that runs as a **Windows Service** runs in Session 0 —
isolated from the desktop with no visible UI. WinAppDriver will fail to interact with
any application there. This is the #1 requirement to get right.

---

## Architecture

```
┌─────────────────────────────────────────────────────────────────────┐
│  Developer Machine / GitHub                                          │
│  git push → GitHub repo                                             │
└────────────────────────┬────────────────────────────────────────────┘
                         │ webhook (push) OR cron schedule
                         ▼
┌─────────────────────────────────────────────────────────────────────┐
│  Jenkins Controller  (any OS — Linux, Docker, cloud)                │
│  • Schedules jobs                                                    │
│  • Dispatches to Windows agent by label 'windows-pos-agent'         │
│  • Collects results (Surefire XML, Extent HTML, screenshots)        │
│  • Sends notifications (email / Slack)                              │
└────────────────────────┬────────────────────────────────────────────┘
                         │ JNLP remoting (agent.jar, port 50000)
                         ▼
┌─────────────────────────────────────────────────────────────────────┐
│  Windows Test Server / VM  (the remote server with POS)             │
│                                                                      │
│  ┌─────────────────────────────────────────────────────────────┐   │
│  │  Logged-in Session (Administrator, auto-login, no sleep)    │   │
│  │                                                              │   │
│  │  WinAppDriver.exe ───► port 4723                           │   │
│  │  AlbertaPOS.exe   ───► launched by framework               │   │
│  │  jenkins-agent.jar ──► Task Scheduler auto-start on login  │   │
│  │  Java 21 + Maven   ──► mvn clean test                      │   │
│  └─────────────────────────────────────────────────────────────┘   │
└─────────────────────────────────────────────────────────────────────┘
```

---

## End-to-End Flow

```
1. Developer pushes to GitHub (main branch)
        ↓
2. GitHub webhook fires → Jenkins job triggered
        ↓
3. Jenkins dispatches job to 'windows-pos-agent'
        ↓
4. Stage: Checkout
   git clone / pull to C:\jenkins\workspace\<job>
        ↓
5. Stage: Verify Prerequisites
   Admin check PASSED (agent runs as Administrator)
   WinAppDriver.exe present ✓
   AlbertaPOS.exe present ✓
        ↓
6. Stage: Ensure WinAppDriver Running
   WinAppDriver already running on port 4723 (Task Scheduler started it at login)
        ↓
7. Stage: Run Tests — mvn clean test
   BaseTest.setUp():
     ├─ AdminPrivilegeValidator → PASS (elevated JVM)
     ├─ WinAppDriverManager → ALREADY RUNNING (HTTP 200 on /status)
     ├─ AlbertaPOS check → NOT RUNNING (fresh machine state)
     ├─ Strategy A: WinAppDriver createForApp → launches AlbertaPOS (admin JVM = no UAC)
     ├─ Window detected in ~10-30 s
     ├─ LoginPage.login() → Strategy A (AutomationId click) → succeeds
     ├─ waitForMainWindowToLoad() → 'Home' window confirmed
     └─ Test method executes → PASS
        ↓
8. post: always
   TestNG XML → Jenkins JUnit plugin → trend charts
   Extent HTML → HTML Publisher plugin → archived
   Screenshots → artifacts (empty if all passed)
   Logs → artifacts
        ↓
9. post: failure (if any test failed)
   Email to hari@albertapayments.com
        ↓
10. BaseTest.tearDown():
    driver.quit() → closes WinAppDriver session
    killProcess("AlbertaPOS.exe") → terminates app (launchedByFramework=true)
    WinAppDriver stays alive (stopWinAppDriverAfterSuite=false)
```

---

## Remote Windows Server Setup

### Step 1 — Install required software

| Software | Notes |
|----------|-------|
| Java 21 (JDK) | Add `JAVA_HOME` and `%JAVA_HOME%\bin` to `PATH` |
| Apache Maven 3.9+ | Add `MAVEN_HOME` and `%MAVEN_HOME%\bin` to `PATH` |
| Git | For Jenkins `git checkout` |
| WinAppDriver 1.x | Install to default path: `C:\Program Files (x86)\Windows Application Driver\` |
| AlbertaPOS | Install to default path: `C:\Program Files (x86)\Alberta Payments LLC\AlbertaPOS\` |

### Step 2 — Create a dedicated CI user

```
Computer Management → Local Users and Groups → Users → New User
  Username:        posci
  Password:        <strong password>
  Password never expires: ✓
  Add to group:    Administrators
```

Enable auto-login (no password prompt on boot):
```
Win + R → netplwiz → uncheck "Users must enter a user name and password"
→ enter posci credentials when prompted
```

### Step 3 — Disable screensaver and sleep

Run in elevated PowerShell as `posci`:
```powershell
powercfg /change standby-timeout-ac 0
powercfg /change monitor-timeout-ac 0
powercfg /change disk-timeout-ac 0
```

WinAppDriver requires a live, unlocked desktop. Any sleep or lock state kills the session.

### Step 4 — Disable UAC prompts

```
Control Panel → User Accounts → Change User Account Control settings
→ drag slider to "Never notify" → OK
```

This allows the elevated test JVM to launch AlbertaPOS without interactive UAC dialogs.

### Step 5 — Auto-start WinAppDriver via Task Scheduler

```
Task Scheduler → Create Task
  General:
    Name:              StartWinAppDriver
    Run as:            posci
    Run with highest privileges: ✓ checked
    Configure for:     Windows 10/11

  Triggers:
    Begin the task: At log on
    Specific user:  posci

  Actions:
    Program: C:\Program Files (x86)\Windows Application Driver\WinAppDriver.exe

  Settings:
    Allow task to be run on demand: ✓
    Run whether user is logged on or not: ✗ (MUST be unchecked — needs active session)
```

### Step 6 — Install Jenkins agent via Task Scheduler (NOT as Windows Service)

Download `agent.jar` from your Jenkins controller: `http://<jenkins-url>/jnlpJars/agent.jar`
Save to `C:\jenkins\agent.jar`.

```
Task Scheduler → Create Task
  General:
    Name:              JenkinsAgent
    Run as:            posci
    Run with highest privileges: ✓ checked

  Triggers:
    Begin the task: At log on
    Specific user:  posci

  Actions:
    Program: java
    Arguments: -jar C:\jenkins\agent.jar
               -url http://<jenkins-url>:8080
               -secret <agent-secret-from-jenkins>
               -name windows-pos-agent
               -workDir C:\jenkins\workspace

  Settings:
    Run whether user is logged on or not: ✗ (needs interactive session)
```

> **Why NOT Windows Service?** Windows services run in Session 0 (isolated from the
> desktop). WinAppDriver requires Session 1+ (the interactive user session). Running the
> agent via Task Scheduler on login ensures it runs in the same desktop session.

---

## Jenkins Controller Configuration

### Create the Windows Node

```
Jenkins → Manage Jenkins → Nodes → New Node
  Name:               windows-pos-agent
  Type:               Permanent Agent
  Remote root:        C:\jenkins\workspace
  Labels:             windows-pos-agent
  Launch method:      Launch agent by connecting it to the controller (JNLP)
  Availability:       Keep this agent online as much as possible

  Environment variables:
    JAVA_HOME  → C:\Program Files\Java\jdk-21
    MAVEN_HOME → C:\tools\apache-maven-3.9.x
```

Copy the agent secret from the node page — you'll need it for the Task Scheduler action.

### GitHub → Jenkins Trigger

**Webhook (push-triggered):**
1. Install Jenkins plugin: **GitHub plugin**
2. GitHub repo → Settings → Webhooks → Add webhook
   - Payload URL: `http://<jenkins-url>/github-webhook/`
   - Content type: `application/json`
   - Events: `Push events`

**Cron (periodic):** Configured directly in the `Jenkinsfile` (see below).

---

## Jenkins Plugins Required

| Plugin | Purpose |
|--------|---------|
| **Pipeline** | `Jenkinsfile` declarative pipeline support |
| **GitHub plugin** | Webhook push trigger |
| **HTML Publisher** | Publish Extent HTML reports in Jenkins UI |
| **JUnit** | Parse Surefire XML, show test trend graphs |
| **Email Extension (email-ext)** | Failure notifications |

---

## Jenkinsfile

Create this file at the **project root** (`d:\AlbertaPOSAutomationWithClaude\Jenkinsfile`):

```groovy
pipeline {
    agent { label 'windows-pos-agent' }

    triggers {
        cron('0 8 * * 1-5')          // Weekdays at 8:00 AM — adjust to your schedule
        // githubPush()              // Uncomment to also trigger on every push
    }

    options {
        timeout(time: 20, unit: 'MINUTES')
        buildDiscarder(logRotator(numToKeepStr: '30'))
        disableConcurrentBuilds()    // Only one POS test run at a time (one screen)
    }

    parameters {
        choice(
            name: 'SUITE',
            choices: [
                'src/test/resources/testng.xml',
                'src/test/resources/testng-homepage.xml',
                'src/test/resources/testng-hometest.xml',
                'src/test/resources/testng-transaction.xml',
                'src/test/resources/testng-full-suite.xml'
            ],
            description: 'TestNG suite to execute'
        )
    }

    stages {
        stage('Checkout') {
            steps {
                checkout scm
                bat 'git log -1 --format="%H %s"'
            }
        }

        stage('Verify Prerequisites') {
            steps {
                // Fail immediately if JVM is not elevated — avoids 90 s timeout
                bat '''powershell -Command "
                    $p = New-Object Security.Principal.WindowsPrincipal(
                            [Security.Principal.WindowsIdentity]::GetCurrent())
                    if (-not $p.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)) {
                        Write-Error 'Jenkins agent must run as Administrator. Check Task Scheduler setup.'
                        exit 1
                    }
                    Write-Host 'Admin check: PASSED'
                "'''
                bat 'if not exist "C:\\Program Files (x86)\\Windows Application Driver\\WinAppDriver.exe" (echo WinAppDriver not found && exit /b 1)'
                bat 'if not exist "C:\\Program Files (x86)\\Alberta Payments LLC\\AlbertaPOS\\AlbertaPOS.exe" (echo AlbertaPOS not found && exit /b 1)'
            }
        }

        stage('Ensure WinAppDriver Running') {
            steps {
                bat '''powershell -Command "
                    $wad = Get-Process WinAppDriver -ErrorAction SilentlyContinue
                    if (-not $wad) {
                        Write-Host 'WinAppDriver not running — starting...'
                        Start-Process 'C:\\Program Files (x86)\\Windows Application Driver\\WinAppDriver.exe'
                        Start-Sleep -Seconds 5
                        Write-Host 'WinAppDriver started'
                    } else {
                        Write-Host 'WinAppDriver already running (PID ' + $wad.Id + ')'
                    }
                "'''
            }
        }

        stage('Run Tests') {
            steps {
                bat "mvn clean test -DsuiteXmlFile=\"${params.SUITE}\""
            }
        }
    }

    post {
        always {
            // Publish Surefire XML → test trend charts in Jenkins
            junit allowEmptyResults: true, testResults: 'target/surefire-reports/*.xml'

            // Publish Extent HTML report
            publishHTML(target: [
                allowMissing         : true,
                alwaysLinkToLastBuild: true,
                keepAll              : true,
                reportDir            : 'target/reports',
                reportFiles          : '*/index.html',
                reportName           : 'Extent Test Report',
                reportTitles         : 'POS Automation'
            ])

            // Archive failure screenshots and logs
            archiveArtifacts artifacts: 'target/screenshots/**', allowEmptyArchive: true
            archiveArtifacts artifacts: 'logs/automation.log',   allowEmptyArchive: true
        }

        failure {
            emailext(
                subject: "FAILED: AlbertaPOS Tests — Build #${env.BUILD_NUMBER}",
                body: """
                    <b>Build failed.</b><br/>
                    Build URL: ${env.BUILD_URL}<br/>
                    Suite:     ${params.SUITE}<br/>
                    Branch:    ${env.GIT_BRANCH}<br/>
                    Commit:    ${env.GIT_COMMIT}
                """,
                mimeType: 'text/html',
                to: 'hari@albertapayments.com'
            )
        }

        success {
            echo "All tests passed — Build #${env.BUILD_NUMBER}"
        }
    }
}
```

---

## config.properties Changes for CI

Update these four keys on the remote server's `config.properties`
(`src/test/resources/config.properties`):

| Key | Local value | CI value | Reason |
|-----|------------|----------|--------|
| `executionMode` | `local` | `ci` | Enables CI log formatting |
| `requireAdminPrivileges` | `false` | `true` | Fail immediately if agent not elevated |
| `stopWinAppDriverAfterSuite` | `false` | `false` | Task Scheduler manages WinAppDriver lifecycle |
| `automationVmMode` | `false` | `true` | UAC disabled on CI machine; stricter launch checks |
| `receiptsFolder` | `D:\\POSAutomationWithClaude\\Receipts` | `C:\\POSAutomationCI\\Receipts` | Path must exist on CI server |

> Create `C:\POSAutomationCI\Receipts` on the server before the first run:
> `New-Item -ItemType Directory -Force -Path "C:\POSAutomationCI\Receipts"`

---

## Verification Checklist

After completing setup, run a manual build:

**Jenkins → job → Build with Parameters → select `testng.xml` → Build**

Watch Console Output for these lines in order:

```
[Verify Prerequisites] Admin check: PASSED
[Verify Prerequisites] WinAppDriver.exe found
[Verify Prerequisites] AlbertaPOS.exe found
[Ensure WinAppDriver] WinAppDriver already running (PID ...)
[Run Tests] Privilege level : ADMINISTRATOR  (high integrity)
[Run Tests] WinAppDriver health check PASSED
[Run Tests] Login succeeded via Strategy A
[Run Tests] Window title validation passed: 'Home'
[Run Tests] Tests run: 1, Failures: 0, Errors: 0, Skipped: 0
[Run Tests] BUILD SUCCESS
```

Then verify:
- **Extent Test Report** link appears on the build page
- **Test Result Trend** graph shows on the job page after 2+ builds
- Email arrives on failure (trigger a deliberate failure to test)
- Cron fires at the scheduled time (check `Build History` the next morning)

---

## Common Issues

| Symptom | Cause | Fix |
|---------|-------|-----|
| `AlbertaPOS window did not appear within 90s` | Agent running as Windows Service (Session 0) | Reinstall agent via Task Scheduler (not service) |
| `Admin check: FAILED` | Task Scheduler task not set to "Run with highest privileges" | Edit task → General → check "Run with highest privileges" |
| `WinAppDriver not found` | WinAppDriver not installed on CI server | Install WinAppDriver 1.x on the Windows server |
| Extent report shows blank page | HTML Publisher CSP policy blocking scripts | Jenkins → Script Console: `System.setProperty("hudson.model.DirectoryBrowserSupport.CSP", "")` |
| Tests run but UI clicks miss | Screen locked or screensaver active | Fix power settings (Step 3) and auto-login (Step 2) |
| Build queues but never starts | Agent offline | RDP to server, check Task Scheduler ran the agent jar on login |
