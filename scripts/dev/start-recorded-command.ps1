# Launch a private command and record its original process handle before returning to the caller.
[CmdletBinding()]
param([Parameter(Mandatory=$true)][string]$CommandFile,
      [Parameter(Mandatory=$true)][string]$RecordPath)
$ErrorActionPreference = 'Stop'
$child = Start-Process -FilePath $env:ComSpec -ArgumentList @('/d','/s','/c', ('"' + $CommandFile + '"')) -PassThru -WindowStyle Hidden
try {
  $record = @{ pid = $child.Id; creationFileTimeUtc = $child.StartTime.ToFileTimeUtc().ToString() }
  [IO.File]::WriteAllText($RecordPath, ($record | ConvertTo-Json -Compress), [Text.UTF8Encoding]::new($false))
} finally { $child.Dispose() }
