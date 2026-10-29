# Disposable qualification guest only. Apply once, reboot, then Verify before sealing.
[CmdletBinding()]
param([ValidateSet('Apply','Verify')][string]$Mode='Verify')
$ErrorActionPreference='Stop'
if (!(Test-Path C:/Qual/justsearch-qualification-guest) -or
    (Get-Content C:/Qual/justsearch-qualification-guest -Raw) -cne "justsearch-vm`n") {throw 'An owned disposable VM is required'}
# DoSvc is protected even from SYSTEM. Its verified no-download policy below,
# together with disabled Windows Update, is the accepted qualification control.
$services=@('wuauserv','SysMain','WSearch','DiagTrack','dmwappushservice','edgeupdate','edgeupdatem')
$taskPaths=@('\Microsoft\Windows\WindowsUpdate\','\Microsoft\Windows\UpdateOrchestrator\',
 '\Microsoft\Windows\Defrag\','\Microsoft\Windows\Customer Experience Improvement Program\',
 '\Microsoft\Windows\Feedback\Siuf\','\Microsoft\Windows\Diagnosis\',
 '\Microsoft\Windows\DeliveryOptimization\')
$edgeTasks=@(Get-ScheduledTask | Where-Object {$_.TaskName -like 'MicrosoftEdgeUpdateTask*'})
$exclusions=@('C:\Qual','C:\Users\qual\AppData\Local\JustSearch',
 'C:\Users\qual\AppData\Local\io.justsearch.shell','C:\Users\qual\AppData\Roaming\io.justsearch.shell')
$errors=New-Object 'Collections.Generic.List[string]'
if ($Mode -eq 'Apply') {
 # WER archives can carry High (NR,NX), which overrides an explicit read ACL
 # for the medium-integrity app task. Retain the reports and make this disposable
 # guest's existing archive readable; new unreadable reports still fail collection.
 $wer='C:/ProgramData/Microsoft/Windows/WER'
 & icacls.exe $wer /grant 'qual:(OI)(CI)(RX)' /T /C
 if($LASTEXITCODE) {throw 'WER read access provisioning failed'}
 & icacls.exe $wer /setintegritylevel '(OI)(CI)M' /T /C
 if($LASTEXITCODE) {throw 'WER integrity provisioning failed'}
 foreach($name in $services) {
  if(Get-Service $name -ErrorAction SilentlyContinue) {
   try {Stop-Service $name -Force;Set-Service $name -StartupType Disabled}
   catch {$errors.Add("service ${name}: $($_.Exception.Message)")}
  }
 }
 foreach($task in @($edgeTasks)+@(Get-ScheduledTask | Where-Object {$_.TaskPath -in $taskPaths -or $_.TaskPath -eq '\Microsoft\Windows\Windows Defender\'})) {
  try {$task | Disable-ScheduledTask | Out-Null} catch {$errors.Add("task $($task.TaskPath)$($task.TaskName): $($_.Exception.Message)")}
 }
 try {Set-MpPreference -DisableRealtimeMonitoring $true -ScanScheduleDay 8 -DisableCatchupFullScan $true -DisableCatchupQuickScan $true}
 catch {$errors.Add("Defender disable: $($_.Exception.Message)")}
 # Tamper protection can refuse the disable without returning a PowerShell error.
 $pendingScans=@(Get-ScheduledTask | Where-Object {$_.TaskPath -eq '\Microsoft\Windows\Windows Defender\' -and $_.State -ne 'Disabled'})
 if ((Get-MpComputerStatus).RealTimeProtectionEnabled -or $pendingScans.Count) {
  try {Add-MpPreference -ExclusionPath $exclusions} catch {$errors.Add("Defender exclusions: $($_.Exception.Message)")}
 }
 foreach($policy in @(
  @('HKLM:\SOFTWARE\Policies\Microsoft\Windows\WindowsUpdate\AU','NoAutoUpdate',1),
  @('HKLM:\SOFTWARE\Policies\Microsoft\Windows\DataCollection','AllowTelemetry',0),
  @('HKLM:\SOFTWARE\Policies\Microsoft\EdgeUpdate','UpdateDefault',0),
 @('HKLM:\SOFTWARE\Policies\Microsoft\EdgeUpdate','Update{F3017226-FE2A-4295-8BDF-00C3A9A7E4C5}',0),
 @('HKLM:\SOFTWARE\Policies\Microsoft\Windows\DeliveryOptimization','DODownloadMode',99))) {
  if(!(Test-Path $policy[0])) {New-Item $policy[0] -Force | Out-Null}
  New-ItemProperty $policy[0] -Name $policy[1] -Value $policy[2] -PropertyType DWord -Force | Out-Null
 }
 & powercfg.exe /hibernate off
 if($LASTEXITCODE) {throw 'Disabling hibernation failed'}
 $computer=Get-CimInstance Win32_ComputerSystem
 $computer | Set-CimInstance -Property @{AutomaticManagedPagefile=$false} | Out-Null
 # Set the boot configuration directly. The WMI create provider rejects a
 # replacement of the currently active pagefile before the required reboot.
 New-ItemProperty 'HKLM:\SYSTEM\CurrentControlSet\Control\Session Manager\Memory Management' -Name PagingFiles -PropertyType MultiString -Value @('C:\pagefile.sys 2048 2048') -Force | Out-Null
 [IO.File]::WriteAllText('C:/Qual/quiet-applied.json',(@{boot=(Get-CimInstance Win32_OperatingSystem).LastBootUpTime.ToUniversalTime().ToString('o');errors=$errors.ToArray()} | ConvertTo-Json -Depth 5))
}
$rows=New-Object 'Collections.Generic.List[object]'
foreach($name in $services) {
 $s=Get-CimInstance Win32_Service -Filter "Name='$name'"
 $rows.Add(@{setting="service/$name";observed=$s | Select-Object Name,StartMode,State;verified=(!$s -or ($s.StartMode -eq 'Disabled' -and $s.State -eq 'Stopped'))})
}
foreach($path in $taskPaths) {
 $tasks=@(Get-ScheduledTask | Where-Object {$_.TaskPath -eq $path})
 $rows.Add(@{setting="tasks/$path";observed=@($tasks | Select-Object TaskName,State);verified=(@($tasks | Where-Object {$_.State -ne 'Disabled'}).Count -eq 0)})
}
$rows.Add(@{setting='tasks/EdgeUpdate';observed=@($edgeTasks | Select-Object TaskName,State);verified=(@($edgeTasks | Where-Object {$_.State -ne 'Disabled'}).Count -eq 0)})
$mp=Get-MpPreference;$status=Get-MpComputerStatus
$defenderTasks=@(Get-ScheduledTask | Where-Object {$_.TaskPath -eq '\Microsoft\Windows\Windows Defender\'})
$disabled=($status.RealTimeProtectionEnabled -eq $false -and @($defenderTasks | Where-Object {$_.State -ne 'Disabled'}).Count -eq 0)
$excluded=(@($exclusions | Where-Object {$_ -notin $mp.ExclusionPath}).Count -eq 0)
$rows.Add(@{setting='Defender';verified=($disabled -or $excluded);mode=$(if($disabled){'disabled'}elseif($excluded){'test-and-app-folders-excluded'}else{'unverified'});
 observed=@{realtime=$status.RealTimeProtectionEnabled;tasks=@($defenderTasks | Select-Object TaskName,State);exclusions=$mp.ExclusionPath}})
$hiber=(Get-ItemProperty 'HKLM:\SYSTEM\CurrentControlSet\Control\Power').HibernateEnabled
$rows.Add(@{setting='hibernation';observed=$hiber;verified=($hiber -eq 0 -and !(Test-Path C:/hiberfil.sys))})
$page=@(Get-CimInstance Win32_PageFileSetting)
$usage=@(Get-CimInstance Win32_PageFileUsage)
$auto=(Get-CimInstance Win32_ComputerSystem).AutomaticManagedPagefile
$rows.Add(@{setting='pagefile';observed=@{automatic=$auto;settings=$page | Select-Object Name,InitialSize,MaximumSize;usage=$usage | Select-Object Name,AllocatedBaseSize};
 verified=(!$auto -and $page.Count -eq 1 -and $page[0].InitialSize -eq 2048 -and $page[0].MaximumSize -eq 2048 -and $usage.Count -eq 1 -and $usage[0].AllocatedBaseSize -eq 2048)})
foreach($policy in @(
 @('HKLM:\SOFTWARE\Policies\Microsoft\Windows\WindowsUpdate\AU','NoAutoUpdate',1),
 @('HKLM:\SOFTWARE\Policies\Microsoft\Windows\DataCollection','AllowTelemetry',0),
 @('HKLM:\SOFTWARE\Policies\Microsoft\EdgeUpdate','UpdateDefault',0),
 @('HKLM:\SOFTWARE\Policies\Microsoft\EdgeUpdate','Update{F3017226-FE2A-4295-8BDF-00C3A9A7E4C5}',0),
 @('HKLM:\SOFTWARE\Policies\Microsoft\Windows\DeliveryOptimization','DODownloadMode',99))) {
 $value=(Get-ItemProperty $policy[0] -ErrorAction SilentlyContinue).($policy[1])
 $rows.Add(@{setting=$policy[1];observed=$value;verified=($null -ne $value -and $value -eq $policy[2])})
}
$boot=(Get-CimInstance Win32_OperatingSystem).LastBootUpTime.ToUniversalTime().ToString('o')
$applied=Get-Content C:/Qual/quiet-applied.json -Raw | ConvertFrom-Json
$rebooted=$boot -ne $applied.boot
$report=@{mode=$Mode;boot=$boot;appliedBoot=$applied.boot;rebooted=$rebooted;applyErrors=$applied.errors;settings=$rows.ToArray();
 exceptions=@(@{setting='service/DoSvc';reason='Protected service: verify DODownloadMode=99 after reboot instead of startup mode; Windows Update disabled; measured writes remain the guard';observed=Get-CimInstance Win32_Service -Filter "Name='DoSvc'" | Select-Object Name,StartMode,State});
 verified=($Mode -eq 'Verify' -and $rebooted -and @($rows | Where-Object {!$_.verified}).Count -eq 0)}
[IO.File]::WriteAllText('C:/Qual/quiet-'+$Mode.ToLowerInvariant()+'.json',($report | ConvertTo-Json -Depth 12))
if($Mode -eq 'Verify' -and !$report.verified) {throw 'Guest write settings did not survive reboot; see quiet-verify.json'}
