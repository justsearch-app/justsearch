# Qualification tools only, inside an owned disposable Windows guest/hosted runner.
[CmdletBinding()]
param([Parameter(Mandatory=$true)][string]$Driver)
$ErrorActionPreference='Stop'
$global:ProgressPreference='SilentlyContinue'
$hosted=$env:GITHUB_ACTIONS -eq 'true' -and $env:RUNNER_ENVIRONMENT -eq 'github-hosted'
$guest=(Test-Path C:/Qual/justsearch-qualification-guest) -and ((Get-Content C:/Qual/justsearch-qualification-guest -Raw) -ceq "justsearch-vm`n")
if (!$hosted -and !$guest) {throw 'Qualification tools require a disposable hosted runner or the VM guest marker'}
if (!(Test-Path -LiteralPath $Driver -PathType Leaf)) {throw 'Supply the separately built tauri-driver 2.0.4 tool'}
New-Item -ItemType Directory -Force C:/Qual/downloads | Out-Null
function Download([string]$Url,[string]$Path) {
 & curl.exe --fail --location --retry 3 --silent --show-error --output $Path $Url
 if($LASTEXITCODE) {throw "Qualification download failed: $Url"}
}
$python='C:/Qual/Python/Scripts/python.exe'
if (!(Test-Path $python)) {
 $base=$null
 if($hosted) {$base=(Get-Command python.exe -ErrorAction Stop).Source}
 elseif(Get-Command py.exe -ErrorAction SilentlyContinue) {
  $base=& py.exe -3.13 -c 'import sys;print(sys.executable)' 2>$null
  if($LASTEXITCODE) {$base=$null}
 }
 if(!$base) {
  Download 'https://www.python.org/ftp/python/3.13.7/python-3.13.7-amd64.exe' C:/Qual/downloads/python.exe
  if((Get-AuthenticodeSignature C:/Qual/downloads/python.exe).Status -ne 'Valid') {throw 'Python vendor signature invalid'}
  $p=Start-Process C:/Qual/downloads/python.exe -ArgumentList '/quiet InstallAllUsers=1 TargetDir=C:\Qual\PythonBase Include_test=0 PrependPath=0' -PassThru -Wait
  if($p.ExitCode -ne 0) {throw "Python installer failed $($p.ExitCode)"}
  $base='C:/Qual/PythonBase/python.exe'
 }
 & $base -m venv C:/Qual/Python
 if($LASTEXITCODE) {throw 'Qualification Python venv failed'}
}
& $python -m pip install --disable-pip-version-check Pillow==11.3.0 comtypes==1.4.13
if($LASTEXITCODE) {throw 'Qualification Pillow install failed'}
if ($hosted) {
 # The hosted machine belongs to this job. Remove its known developer JDKs,
 # rather than let a clean-install check accidentally inherit a toolchain.
 $jdkRoots=@(Get-ChildItem Env:JAVA_HOME* | ForEach-Object {$_.Value})
 if($env:RUNNER_TOOL_CACHE) {
  $jdkRoots+=@(Get-ChildItem -LiteralPath $env:RUNNER_TOOL_CACHE -Directory | Where-Object {$_.Name -like 'Java_*'} | ForEach-Object {$_.FullName})
 }
 foreach($jdkRoot in ($jdkRoots | Select-Object -Unique)) {
  $full=[IO.Path]::GetFullPath($jdkRoot).TrimEnd('\')
  if($full -match '^[A-Za-z]:\\?$') {throw 'Refusing an invalid hosted JDK root'}
  if(Test-Path (Join-Path $full 'bin/javac.exe')) {Remove-Item -LiteralPath $full -Recurse -Force}
  elseif($env:RUNNER_TOOL_CACHE -and $full.StartsWith([IO.Path]::GetFullPath($env:RUNNER_TOOL_CACHE)+'\',[StringComparison]::OrdinalIgnoreCase) -and (Split-Path $full -Leaf) -like 'Java_*') {
   Remove-Item -LiteralPath $full -Recurse -Force
  }
 }
}
# LocalDumps is an HKLM facility and needs an administrator during provisioning.
# https://learn.microsoft.com/windows/win32/wer/collecting-user-mode-dumps
New-Item -ItemType Directory -Force C:/Qual/crash-dumps | Out-Null
$key='HKLM:\Software\Microsoft\Windows\Windows Error Reporting\LocalDumps'
New-Item $key -Force | Out-Null
New-ItemProperty $key DumpType -Value 1 -PropertyType DWord -Force | Out-Null
New-ItemProperty $key DumpCount -Value 1024 -PropertyType DWord -Force | Out-Null
New-ItemProperty $key DumpFolder -Value 'C:\Qual\crash-dumps' -PropertyType ExpandString -Force | Out-Null
$nodeVersion='22.20.0'
$nodeDir="C:/Qual/node-v$nodeVersion-win-x64"
if (!(Test-Path "$nodeDir/node.exe")) {
 Download "https://nodejs.org/dist/v$nodeVersion/node-v$nodeVersion-win-x64.zip" C:/Qual/downloads/node.zip
 Download "https://nodejs.org/dist/v$nodeVersion/SHASUMS256.txt" C:/Qual/downloads/node-sums.txt
 $expected=((Get-Content C:/Qual/downloads/node-sums.txt | Where-Object {$_ -match " node-v$([regex]::Escape($nodeVersion))-win-x64.zip$"}) -split ' ')[0]
 if((Get-FileHash C:/Qual/downloads/node.zip).Hash -ine $expected) {throw 'Node checksum mismatch'}
 & tar.exe -xf C:/Qual/downloads/node.zip -C C:/Qual
 if($LASTEXITCODE) {throw 'Node archive extraction failed'}
}
# Use the installed OS editor; no separate viewer binary/download is needed.
$viewer=Join-Path $env:WINDIR 'System32/WindowsPowerShell/v1.0/powershell_ise.exe'
if(!(Test-Path $viewer)){throw 'PowerShell ISE text editor is unavailable on this qualification image'}
$viewImage=(Resolve-Path $viewer).Path
# Refresh the vendor driver for the exact installed runtime; cache by version.
& (Join-Path $PSScriptRoot 'windows-edge.ps1')
$edge=Get-Content C:/Qual/edge/versions.json -Raw | ConvertFrom-Json
$webview=$edge.webview2
$driverHash=(Get-FileHash -LiteralPath $Driver -Algorithm SHA256).Hash.ToLowerInvariant()
$receiptPath=$Driver+'.json'
if(Test-Path -LiteralPath $receiptPath) {
 $receipt=Get-Content -LiteralPath $receiptPath -Raw | ConvertFrom-Json
 if($receipt.version -cne '2.0.4' -or $receipt.sha256 -ine $driverHash) {throw 'tauri-driver receipt/version/checksum mismatch'}
} else {
 # tauri-driver 2.0.4 has no --version switch. The pinned Cargo installation
 # record is its provenance; supplied tool binaries carry the matching receipt.
 $cargoRoot=if($env:CARGO_HOME){$env:CARGO_HOME}else{Join-Path $env:USERPROFILE '.cargo'}
 $metadata=Get-Content (Join-Path $cargoRoot '.crates2.json') -Raw | ConvertFrom-Json
 $entry=@($metadata.installs.PSObject.Properties | Where-Object {$_.Name.StartsWith('tauri-driver 2.0.4 (')})
 if($entry.Count -ne 1 -or (Resolve-Path $Driver).Path -ine (Resolve-Path (Join-Path $cargoRoot 'bin/tauri-driver.exe')).Path) {throw 'Supply a pinned tauri-driver 2.0.4 build receipt beside the tool'}
}
if((Resolve-Path $Driver).Path -ine 'C:\Qual\tauri-driver.exe') {Copy-Item -LiteralPath $Driver C:/Qual/tauri-driver.exe -Force}
[IO.File]::WriteAllText('C:/Qual/tools.json',(@{node="$nodeDir/node.exe";driver='C:/Qual/tauri-driver.exe';nativeDriver='C:/Qual/edge/msedgedriver.exe';driverVersion='2.0.4';driverSha256=$driverHash;webview2=$webview;viewer=$viewImage} | ConvertTo-Json))

if($hosted){
 & $python (Join-Path $PSScriptRoot 'windows-association.py') --evidence C:/Qual/viewer-setup
 if($LASTEXITCODE){
  Get-Content -LiteralPath C:/Qual/viewer-setup/configure.json -Raw
  throw 'Native default viewer setup failed; see C:/Qual/viewer-setup/configure.json'
 }
}
