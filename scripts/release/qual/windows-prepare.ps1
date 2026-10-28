$ErrorActionPreference = 'Stop'
$taskName = 'JustSearch-qual-install'
$job = @'
$ErrorActionPreference = 'Stop'
try {
  $process = Start-Process C:/Qual/package.exe -ArgumentList '/S' -Wait -PassThru
  $code = $process.ExitCode
  if (Get-Process JustSearch -ErrorAction SilentlyContinue) { $code = 99 }
  Set-Content C:/Qual/install-result.tmp $code
  Move-Item C:/Qual/install-result.tmp C:/Qual/install-result.txt -Force
} catch { Set-Content C:/Qual/install-result.tmp $_; Move-Item C:/Qual/install-result.tmp C:/Qual/install-result.txt -Force; exit 1 }
'@
Remove-Item C:/Qual/install-result.txt -ErrorAction SilentlyContinue
$encoded = [Convert]::ToBase64String([Text.Encoding]::Unicode.GetBytes($job))
$action = New-ScheduledTaskAction -Execute 'powershell.exe' -Argument "-NoProfile -NonInteractive -EncodedCommand $encoded"
$principal = New-ScheduledTaskPrincipal -UserId 'qual' -LogonType Interactive -RunLevel Highest
try {
  Register-ScheduledTask -TaskName $taskName -Action $action -Principal $principal -Force | Out-Null
  Start-ScheduledTask -TaskName $taskName
  $deadline = (Get-Date).AddMinutes(10)
  while (!(Test-Path C:/Qual/install-result.txt) -and (Get-Date) -lt $deadline) { Start-Sleep 1 }
  if (!(Test-Path C:/Qual/install-result.txt)) { throw 'Silent NSIS install timed out' }
  $result = (Get-Content C:/Qual/install-result.txt -Raw).Trim()
  if ($result -ne '0') { throw "Silent NSIS install failed: $result" }
} finally {
  Unregister-ScheduledTask -TaskName $taskName -Confirm:$false -ErrorAction SilentlyContinue
}
