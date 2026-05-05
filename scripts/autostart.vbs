' BitMan JustBuy Auto-Start
' 로그인 시 Spring Boot(8080) + Scheduler + Cloudflared 자동 시작
' Spring Boot가 프론트엔드(static/) 직접 서빙 — 별도 dev server 불필요
' 이미 실행 중이면 스킵 (중복 방지) + 5분 Watchdog 루프
'
' 포트 소유권 (단일 진실 소스):
'   8080  → justbuy Spring Boot (이 스크립트가 소유)
'   5001  → marketflow Flask (별개 프로젝트 Task Scheduler 'MarketFlow-V1-Flask' 가 소유, justbuy 는 소비만)
'   4000  → marketflow vite
'   5002  → 미사용
'
' 8080 충돌 방어: 부팅 시 marketflow 의 stray gradle bootRun 이 8080 을 먼저 잡는 사고가 있었으므로,
' 8080 점유 프로세스가 'bitman_marketfloww' 경로의 java 면 강제 종료 후 justbuy JAR 을 띄운다.
'
' 설치: 이 파일의 바로가기(.lnk)를 시작프로그램 폴더에 배치
' C:\Users\dynas\AppData\Roaming\Microsoft\Windows\Start Menu\Programs\Startup

Option Explicit
Dim objShell, objFSO, objWMI, logFile
Dim PROJECT, PYTHON, BACKEND, BOOT_JAR, JAVA_EXE, CLOUDFLARED

Set objShell = CreateObject("WScript.Shell")
Set objFSO = CreateObject("Scripting.FileSystemObject")
Set objWMI = GetObject("winmgmts:\\.\root\cimv2")

PROJECT = "C:\bitman_justbuy_project"
PYTHON = PROJECT & "\.venv\Scripts\python.exe"
BACKEND = PROJECT & "\backend"
BOOT_JAR = BACKEND & "\build\libs\justbuy-api-1.0.0.jar"
JAVA_EXE = "C:\Program Files\Microsoft\jdk-21.0.10.7-hotspot\bin\java.exe"
CLOUDFLARED = "C:\Users\dynas\AppData\Local\Microsoft\WinGet\Packages\Cloudflare.cloudflared_Microsoft.Winget.Source_8wekyb3d8bbwe\cloudflared.exe"

' 환경변수
objShell.Environment("Process")("PYTHONIOENCODING") = "utf-8"

' 로그
Dim logDir : logDir = PROJECT & "\logs"
If Not objFSO.FolderExists(logDir) Then objFSO.CreateFolder(logDir)
Set logFile = objFSO.OpenTextFile(logDir & "\autostart.log", 8, True)

Sub Log(msg)
    logFile.WriteLine Now & " | " & msg
End Sub

Function IsProcessRunning(cmdPattern)
    Dim colProcesses
    IsProcessRunning = False
    Set colProcesses = objWMI.ExecQuery("SELECT CommandLine FROM Win32_Process WHERE CommandLine LIKE '%" & cmdPattern & "%'")
    If colProcesses.Count > 0 Then IsProcessRunning = True
End Function

Function IsPortOpen(port)
    On Error Resume Next
    Dim objHTTP
    Set objHTTP = CreateObject("MSXML2.XMLHTTP")
    objHTTP.Open "GET", "http://127.0.0.1:" & port & "/", False
    objHTTP.setRequestHeader "Connection", "close"
    objHTTP.Send
    IsPortOpen = (Err.Number = 0)
    On Error GoTo 0
End Function

Function IsPortOpenLocal(port)
    On Error Resume Next
    Dim objHTTP
    Set objHTTP = CreateObject("MSXML2.XMLHTTP")
    objHTTP.Open "GET", "http://localhost:" & port & "/", False
    objHTTP.setRequestHeader "Connection", "close"
    objHTTP.Send
    IsPortOpenLocal = (Err.Number = 0)
    On Error GoTo 0
End Function

Function AnyPortOpen(port)
    AnyPortOpen = IsPortOpen(port) Or IsPortOpenLocal(port)
End Function

' 8080 을 잡고 있는 프로세스가 marketflow 의 stray java 면 죽인다.
' (justbuy 의 java 면 그대로 둔다 — 정상 상태)
Sub KillStrayMarketflowOn8080()
    On Error Resume Next
    Dim exec, line, parts, pid, proc, cmdLine
    Set exec = objShell.Exec("cmd /c netstat -ano ^| findstr "":8080 "" ^| findstr LISTENING")
    Do While Not exec.StdOut.AtEndOfStream
        line = exec.StdOut.ReadLine
        parts = Split(Trim(line))
        pid = parts(UBound(parts))
        If IsNumeric(pid) Then
            Set proc = objWMI.ExecQuery("SELECT CommandLine FROM Win32_Process WHERE ProcessId=" & pid)
            For Each p In proc
                cmdLine = "" & p.CommandLine
                If InStr(LCase(cmdLine), "bitman_marketfloww") > 0 Then
                    Log "8080 점유: marketflow stray java PID=" & pid & " — 강제 종료"
                    objShell.Run "cmd /c taskkill /F /PID " & pid, 0, True
                ElseIf InStr(LCase(cmdLine), "justbuy-api") > 0 Then
                    Log "8080 점유: justbuy JAR PID=" & pid & " — 정상"
                End If
            Next
        End If
    Loop
    On Error GoTo 0
End Sub

Sub WaitForPort(port, label, maxRetries)
    Dim k
    For k = 1 To maxRetries
        WScript.Sleep 3000
        If AnyPortOpen(port) Then
            Log label & ": OK (port " & port & ")"
            Exit Sub
        End If
    Next
    Log label & ": FAILED (port " & port & " not open after " & (maxRetries * 3) & "s)"
End Sub

' ========== STARTUP ==========
Log "========== JUSTBUY AUTO START BEGIN =========="
WScript.Sleep 15000  ' 네트워크/디스크 안정화 대기

' ── 1. Flask 5001 은 marketflow 가 소유 (Task Scheduler 'MarketFlow-V1-Flask') ──
'    justbuy 는 /api/kr/market-gate 호출만 함 (consumer). 자체 기동 없음.
If AnyPortOpen(5001) Then
    Log "Flask 5001: marketflow owns it (consumer mode)"
Else
    Log "Flask 5001: NOT UP — marketflow Task Scheduler 확인 필요 (justbuy 가 기동하지 않음)"
End If

' ── 2. Spring Boot (port 8080) — JAR 직접 실행 ──
'    8080 이 marketflow stray java 에 잡혀 있으면 죽이고 justbuy JAR 을 띄운다.
KillStrayMarketflowOn8080()
If AnyPortOpen(8080) Then
    Log "SpringBoot: already running (justbuy)"
Else
    If Not objFSO.FileExists(BOOT_JAR) Then
        Log "SpringBoot: JAR not found, building..."
        objShell.CurrentDirectory = BACKEND
        objShell.Run "cmd /c ""cd /d " & BACKEND & " && " & BACKEND & "\gradlew.bat bootJar -x test""", 0, True
        Log "SpringBoot: build done"
    End If
    Log "SpringBoot: starting JAR (with .env)..."
    objShell.CurrentDirectory = PROJECT
    objShell.Run "cmd /c """ & PROJECT & "\scripts\start-springboot.bat""", 0, False
    WaitForPort 8080, "SpringBoot", 15
End If

' ── 3. Scheduler (daemon mode) ──
If IsProcessRunning("scheduler.py --daemon") Then
    Log "Scheduler: already running"
Else
    Log "Scheduler: starting..."
    objShell.CurrentDirectory = PROJECT
    objShell.Run "cmd /c ""set PYTHONIOENCODING=utf-8 && """ & PYTHON & """ scheduler.py --daemon""", 0, False
    WScript.Sleep 5000
    If IsProcessRunning("scheduler.py --daemon") Then
        Log "Scheduler: OK"
    Else
        Log "Scheduler: FAILED"
    End If
End If

' ── 4. Cloudflared Tunnel ──
If IsProcessRunning("justbuy-tunnel") Then
    Log "Cloudflared: already running"
Else
    Log "Cloudflared: starting justbuy-tunnel..."
    objShell.Run """" & CLOUDFLARED & """ tunnel --config ""C:\Users\dynas\.cloudflared\config.yml"" run justbuy-tunnel", 0, False
    WScript.Sleep 8000
    If IsProcessRunning("justbuy-tunnel") Then
        Log "Cloudflared: OK"
    Else
        Log "Cloudflared: FAILED"
    End If
End If

Log "========== JUSTBUY AUTO START END =========="
logFile.Close

' ========== WATCHDOG (5분 간격) ==========
Do While True
    WScript.Sleep 300000

    Set logFile = objFSO.OpenTextFile(logDir & "\autostart.log", 8, True)

    ' Flask 5001 은 marketflow 소유 — justbuy watchdog 에서 만지지 않음.
    ' DOWN 이면 marketflow Task Scheduler 가 복구. 여기서는 로그만 남긴다.
    If Not AnyPortOpen(5001) Then
        Log "WATCHDOG: Flask 5001 DOWN — marketflow Task Scheduler 가 복구해야 함 (justbuy 는 consumer)"
    End If

    ' Spring Boot (JAR 직접 실행) — stray marketflow java 도 정리
    KillStrayMarketflowOn8080()
    If Not AnyPortOpen(8080) Then
        Log "WATCHDOG: SpringBoot DOWN — restarting JAR..."
        objShell.CurrentDirectory = PROJECT
        objShell.Run "cmd /c """ & PROJECT & "\scripts\start-springboot.bat""", 0, False
        WScript.Sleep 20000
        If AnyPortOpen(8080) Then Log "WATCHDOG: SpringBoot OK" Else Log "WATCHDOG: SpringBoot FAILED"
    End If

    ' Scheduler
    If Not IsProcessRunning("scheduler.py --daemon") Then
        Log "WATCHDOG: Scheduler DOWN — restarting..."
        objShell.CurrentDirectory = PROJECT
        objShell.Run "cmd /c ""set PYTHONIOENCODING=utf-8 && """ & PYTHON & """ scheduler.py --daemon""", 0, False
        WScript.Sleep 5000
        If IsProcessRunning("scheduler.py --daemon") Then Log "WATCHDOG: Scheduler OK" Else Log "WATCHDOG: Scheduler FAILED"
    End If

    ' Cloudflared
    If Not IsProcessRunning("justbuy-tunnel") Then
        Log "WATCHDOG: Cloudflared DOWN — restarting..."
        objShell.Run """" & CLOUDFLARED & """ tunnel --config ""C:\Users\dynas\.cloudflared\config.yml"" run justbuy-tunnel", 0, False
        WScript.Sleep 8000
        Log "WATCHDOG: Cloudflared restarted"
    End If

    logFile.Close
Loop
