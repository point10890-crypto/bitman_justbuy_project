' Test version — no 15s wait, no watchdog loop
Option Explicit
Dim objShell, objFSO, objWMI
Dim PROJECT, PYTHON, BACKEND, CLOUDFLARED

Set objShell = CreateObject("WScript.Shell")
Set objFSO = CreateObject("Scripting.FileSystemObject")
Set objWMI = GetObject("winmgmts:\\.\root\cimv2")

PROJECT = "C:\bitman_justbuy_project"
PYTHON = PROJECT & "\.venv\Scripts\python.exe"
BACKEND = PROJECT & "\backend"
CLOUDFLARED = "C:\Users\dynas\AppData\Local\Microsoft\WinGet\Packages\Cloudflare.cloudflared_Microsoft.Winget.Source_8wekyb3d8bbwe\cloudflared.exe"

objShell.Environment("Process")("PYTHONIOENCODING") = "utf-8"

Dim logDir : logDir = PROJECT & "\logs"
If Not objFSO.FolderExists(logDir) Then objFSO.CreateFolder(logDir)
Dim logFile
Set logFile = objFSO.OpenTextFile(logDir & "\test_autostart.log", 2, True)

Sub Log(msg)
    logFile.WriteLine Now & " | " & msg
    WScript.Echo msg
End Sub

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

Function IsProcessRunning(cmdPattern)
    Dim colProcesses
    IsProcessRunning = False
    Set colProcesses = objWMI.ExecQuery("SELECT CommandLine FROM Win32_Process WHERE CommandLine LIKE '%" & cmdPattern & "%'")
    If colProcesses.Count > 0 Then IsProcessRunning = True
End Function

Log "===== FINAL TEST START ====="

' 1. Flask
Log "Flask: starting..."
objShell.CurrentDirectory = PROJECT
objShell.Run "cmd /c ""set PYTHONIOENCODING=utf-8 && """ & PYTHON & """ flask_app.py""", 0, False
Dim i
For i = 1 To 10
    WScript.Sleep 3000
    If AnyPortOpen(5001) Then
        Log "Flask: OK"
        Exit For
    End If
Next
If Not AnyPortOpen(5001) Then Log "Flask: FAILED"

' 2. Spring Boot
Log "SpringBoot: starting..."
objShell.CurrentDirectory = BACKEND
objShell.Run "cmd /c ""cd /d " & BACKEND & " && " & BACKEND & "\gradlew.bat bootRun""", 0, False
Dim j
For j = 1 To 20
    WScript.Sleep 3000
    If AnyPortOpen(8080) Then
        Log "SpringBoot: OK"
        Exit For
    End If
Next
If Not AnyPortOpen(8080) Then Log "SpringBoot: FAILED"

' 3. Cloudflared
If IsProcessRunning("cloudflared") Then
    Log "Cloudflared: SKIP (already running)"
Else
    Log "Cloudflared: starting..."
    objShell.Run """" & CLOUDFLARED & """ tunnel --config ""C:\Users\dynas\.cloudflared\config.yml"" run justbuy-tunnel", 0, False
    WScript.Sleep 8000
    If IsProcessRunning("cloudflared") Then
        Log "Cloudflared: OK"
    Else
        Log "Cloudflared: FAILED"
    End If
End If

Log "===== FINAL TEST COMPLETE ====="
logFile.Close
