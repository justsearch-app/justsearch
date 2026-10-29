# Cache the pinned qualification client once, before sealing a tools snapshot.
$ErrorActionPreference='Stop'
if (!(Test-Path C:/Qual/justsearch-qualification-guest) -or
    (Get-Content C:/Qual/justsearch-qualification-guest -Raw) -cne "justsearch-vm`n") {throw 'An owned disposable VM is required'}
$python='C:/Qual/Python/Scripts/python.exe'
if(!(Test-Path C:/Qual/uia-client.json)){
 & $python -m pip install --disable-pip-version-check comtypes==1.4.13
 if($LASTEXITCODE){throw 'Pinned native UIA client installation failed'}
}
$probe=@'
import json,os
from pathlib import Path
import comtypes,comtypes.client
assert comtypes.__version__ == '1.4.13'
module=comtypes.client.GetModule(str(Path(os.environ['WINDIR'])/'System32/UIAutomationCore.dll'))
client=comtypes.client.CreateObject(module.CUIAutomation,interface=module.IUIAutomation)
Path('C:/Qual/uia-client.json').write_text(json.dumps({'comtypes':comtypes.__version__,'interface':'IUIAutomation','nativeClientCreated':bool(client)}))
'@
& $python -c $probe
if($LASTEXITCODE){throw 'Native UIA interface cache/probe failed'}
$tools=Get-Content C:/Qual/tools.json -Raw | ConvertFrom-Json
$node=$tools.node
$nodeDir=Split-Path $node
$npm=Join-Path $nodeDir node_modules/npm/bin/npm-cli.js
$env:PATH=$nodeDir+';'+$env:PATH
$package='--package=@modelcontextprotocol/inspector@0.21.1'
if (!(Test-Path C:/Qual/inspector-cache.json)) {
 & $node $npm exec --yes $package -- node --version
 if($LASTEXITCODE) {throw 'Pinned Inspector cache population failed'}
}
# Same package resolution as npx in J8, with downloads prohibited for this check.
$version=& $node $npm exec --offline --yes $package -- node --version
if($LASTEXITCODE) {throw 'Pinned Inspector is unavailable from the setup cache'}
[IO.File]::WriteAllText('C:/Qual/inspector-cache.json',(@{inspector='0.21.1';node=($version -join "`n");offlineResolution=$true} | ConvertTo-Json))
