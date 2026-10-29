# One approved NSIS operation, launched only by the owning VM host.
[CmdletBinding()]
param([Parameter(Mandatory=$true)][string]$Installer,
      [Parameter(Mandatory=$true)][string]$Sha256,
      [Parameter(Mandatory=$true)][string]$Nonce)
$ErrorActionPreference='Stop'
if (!(Test-Path C:/Qual/justsearch-qualification-guest) -or
    (Get-Content C:/Qual/justsearch-qualification-guest -Raw) -cne "justsearch-vm`n" -or
    $Nonce -cnotmatch '^[a-f0-9]{32}$' -or $Sha256 -cnotmatch '^[a-f0-9]{64}$') {throw 'Disposable installer request invalid'}
$principal=New-Object Security.Principal.WindowsPrincipal([Security.Principal.WindowsIdentity]::GetCurrent())
if (!$principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)) {throw 'NSIS helper requires the VM highest task'}
$result=@{nonce=$Nonce;sha256=$Sha256;authenticode=[string](Get-AuthenticodeSignature -LiteralPath $Installer).Status;executionContext='highest interactive NSIS task; app/driver remain limited'}
$p=$null
try {
 if((Get-FileHash -LiteralPath $Installer -Algorithm SHA256).Hash -ine $Sha256) {throw 'Approved installer bytes changed'}
 $info=New-Object Diagnostics.ProcessStartInfo
 $info.FileName=$Installer;$info.Arguments='/S';$info.UseShellExecute=$false
 $info.WorkingDirectory=[IO.Path]::GetDirectoryName($Installer)
 $p=New-Object Diagnostics.Process;$p.StartInfo=$info
 if(!$p.Start()) {throw 'NSIS did not start'}
 $null=$p.Handle
 $parent=[Diagnostics.Process]::GetCurrentProcess()
 $result.process=@{pid=$p.Id;created=$p.StartTime.ToUniversalTime().ToString('o');exe=$p.MainModule.FileName;
                   ppid=$parent.Id;parentCreated=$parent.StartTime.ToUniversalTime().ToString('o')}
 if(!$p.WaitForExit(600000)) {$p.Kill();$null=$p.WaitForExit(20000);throw 'NSIS operation timed out'}
 $result.exitCode=[int]$p.ExitCode
 $result.process.exited=$p.ExitTime.ToUniversalTime().ToString('o')
} catch {$result.error=$_.Exception.Message}
finally {
 if($p){$p.Dispose()}
 $path="C:/Qual/installer-worker-$Nonce.json"
 [IO.File]::WriteAllText($path+'.tmp',($result | ConvertTo-Json -Depth 8 -Compress))
 Move-Item -LiteralPath ($path+'.tmp') -Destination $path -Force
}
