' BitMan JustBuy Auto-Start
' 로그인 시 Flask(5001) + Spring Boot(8080) + Cloudflared 자동 시작
' Spring Boot가 프론트엔드(static/) 직접 서빙 — 별도 dev server 불필요
' 이미 실행 중이면 스킵 (중복 방지) + 5분 Watchdog 루프
'
' 설치: 이 파일의 바로가기(.lnk)를 시작프로그램 폴더에 배치
' C:\Users\dynas\AppData\Roaming\Microsoft\Windows\Start Menu\Programs\Startup

Option Explicit
Dim objShell, objFSO, objWMI, logFile
Dim PROJECT, PYTHON, BACKEND, CLOUDFLARED

Set objShell = CreateObject("WScript.Shell")
Set objFSO = CreateObject("Scripting.FileSystemObject")
Set objWMI = GetObject("winmgmts:\\.\root\cimv2")

PROJECT = "C:\bitman_justbuy_project"
PYTHON = PROJECT & "\.venv\Scripts\python.exe"
BACKEND = PROJECT & "\backend"
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

' ── 1. Flask API (port 5001) ──
If AnyPortOpen(5001) Then
    Log "Flask: already running"
Else
    Log "Flask: starting..."
    objShell.CurrentDirectory = PROJECT
    objShell.Run "cmd /c ""set PYTHONIOENCODING=utf-8 && """ & PYTHON & """ flask_app.py""", 0, False
    WaitForPort 5001, "Flask", 10
End If

' ── 2. Spring Boot (port 8080) — 프론트엔드 static 서빙 포함 ──
If AnyPortOpen(8080) Then
    Log "SpringBoot: already running"
Else
    Log "SpringBoot: starting..."
    objShell.CurrentDirectory = BACKEND
    objShell.Run "cmd /c ""cd /d " & BACKEND & " && " & BACKEND & "\gradlew.bat bootRun""", 0, False
    WaitForPort 8080, "SpringBoot", 20
End If

' ── 3. Cloudflared Tunnel ──
If IsProcessRunning("cloudflared.exe"" tunnel") Then
    Log "Cloudflared: already running"
Else
    Log "Cloudflared: starting justbuy-tunnel..."
    objShell.Run """" & CLOUDFLARED & """ tunnel --config ""C:\Users\dynas\.cloudflared\config.yml"" run justbuy-tunnel", 0, False
    WScript.Sleep 8000
    If IsProcessRunning("cloudflared.exe"" tunnel") Then
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

    ' Flask
    If Not AnyPortOpen(5001) Then
        Log "WATCHDOG: Flask DOWN — restarting..."
        objShell.CurrentDirectory = PROJECT
        objShell.Run "cmd /c ""set PYTHONIOENCODING=utf-8 && """ & PYTHON & """ flask_app.py""", 0, False
        WScript.Sleep 10000
        If AnyPortOpen(5001) Then Log "WATCHDOG: Flask OK" Else Log "WATCHDOG: Flask FAILED"
    End If

    ' Spring Boot
    If Not AnyPortOpen(8080) Then
        Log "WATCHDOG: SpringBoot DOWN — restarting..."
        objShell.CurrentDirectory = BACKEND
        objShell.Run "cmd /c ""cd /d " & BACKEND & " && " & BACKEND & "\gradlew.bat bootRun""", 0, False
        WScript.Sleep 30000
        If AnyPortOpen(8080) Then Log "WATCHDOG: SpringBoot OK" Else Log "WATCHDOG: SpringBoot FAILED"
    End If

    ' Cloudflared
    If Not IsProcessRunning("cloudflared.exe"" tunnel") Then
        Log "WATCHDOG: Cloudflared DOWN — restarting..."
        objShell.Run """" & CLOUDFLARED & """ tunnel --config ""C:\Users\dynas\.cloudflared\config.yml"" run justbuy-tunnel", 0, False
        WScript.Sleep 8000
        Log "WATCHDOG: Cloudflared restarted"
    End If

    logFile.Close
Loop
