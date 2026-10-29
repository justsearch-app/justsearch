# Cache and verify the exact WebView2 driver before sealing a disposable guest.
[CmdletBinding()]
param([switch]$VerifyOnly)
$ErrorActionPreference='Stop'
$hosted=$env:GITHUB_ACTIONS -eq 'true' -and $env:RUNNER_ENVIRONMENT -eq 'github-hosted'
$guest=(Test-Path C:/Qual/justsearch-qualification-guest) -and ((Get-Content C:/Qual/justsearch-qualification-guest -Raw) -ceq "justsearch-vm`n")
if(!$hosted -and !$guest){throw 'Edge driver setup requires a disposable qualification machine'}
$webviews=@(Get-ChildItem "${env:ProgramFiles(x86)}/Microsoft/EdgeWebView/Application" -Directory | Where-Object {$_.Name -match '^\d+\.\d+\.\d+\.\d+$'})
if(!$webviews.Count){throw 'Installed WebView2 version is missing'}
$webview=($webviews | Sort-Object {[version]$_.Name} -Descending | Select-Object -First 1).Name
$cache="C:/Qual/downloads/edgedriver/$webview"
$native='C:/Qual/edge/msedgedriver.exe'
if(!$VerifyOnly){
 New-Item -ItemType Directory -Force $cache,C:/Qual/edge | Out-Null
 $zip="$cache/edgedriver_win64.zip"
 if(!(Test-Path $zip)){
  & curl.exe --fail --location --retry 3 --silent --show-error --output "$zip.tmp" "https://msedgedriver.microsoft.com/$webview/edgedriver_win64.zip"
  if($LASTEXITCODE){throw "Exact Edge driver download failed for WebView2 $webview"}
  Move-Item "$zip.tmp" $zip
 }
 & tar.exe -xf $zip -C C:/Qual/edge
 if($LASTEXITCODE){throw 'Edge driver extraction failed'}
}
if((Get-AuthenticodeSignature $native).Status -ne 'Valid'){throw 'Edge driver vendor signature invalid'}
$driverVersion=& $native --version
if($LASTEXITCODE){throw 'Edge driver version query failed'}
$matched=[regex]::Match(($driverVersion -join ' '),'\b\d+\.\d+\.\d+\.\d+\b').Value
if($matched -cne $webview){throw "WebView2/Edge driver mismatch (infrastructure): WebView2=$webview, EdgeDriver=$driverVersion"}
$receipt=@{webview2=$webview;msedgedriver=$matched;sha256=(Get-FileHash $native -Algorithm SHA256).Hash.ToLowerInvariant();vendorSignature='Valid'}
[IO.File]::WriteAllText('C:/Qual/edge/versions.json',($receipt | ConvertTo-Json))
if(Test-Path C:/Qual/tools.json){
 $tools=Get-Content C:/Qual/tools.json -Raw | ConvertFrom-Json
 $tools.webview2=$webview
 [IO.File]::WriteAllText('C:/Qual/tools.json',($tools | ConvertTo-Json))
}
