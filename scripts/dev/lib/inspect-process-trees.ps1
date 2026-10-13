# Read-only census: one process-table enumeration for every recorded root and descendant.
[CmdletBinding()]
param([string]$RootsBase64, [switch]$Session)
$ErrorActionPreference='Stop'
Add-Type -Namespace JustSearchDev -Name CensusNative -MemberDefinition @'
[System.Runtime.InteropServices.DllImport("kernel32.dll", SetLastError=true)]
public static extern System.IntPtr OpenProcess(uint access, bool inherit, int pid);
[System.Runtime.InteropServices.DllImport("kernel32.dll", SetLastError=true)]
public static extern bool GetProcessTimes(System.IntPtr process, out long creation, out long exit, out long kernel, out long user);
[System.Runtime.InteropServices.DllImport("kernel32.dll")]
public static extern bool CloseHandle(System.IntPtr handle);
private static readonly System.Threading.Timer deadline = new System.Threading.Timer(
  ignored => System.Environment.Exit(4), null, System.Threading.Timeout.Infinite, System.Threading.Timeout.Infinite);
public static void Arm(int milliseconds) { deadline.Change(milliseconds, System.Threading.Timeout.Infinite); }
'@
function Read-Census([string]$RootsBase64) {
$handles=New-Object 'System.Collections.Generic.List[IntPtr]'
$censusEnded=@{}
function Pin-Birth([int]$id) {
  $h=[JustSearchDev.CensusNative]::OpenProcess(0x1000,$false,$id)
  if($h -eq [IntPtr]::Zero){return $null}
  $handles.Add($h)
  [long]$created=0;[long]$endedAt=0;[long]$kernel=0;[long]$user=0
  if([JustSearchDev.CensusNative]::GetProcessTimes($h,[ref]$created,[ref]$endedAt,[ref]$kernel,[ref]$user)) {$censusEnded[$id]=$endedAt -ne 0;return $created}
  return $null
}
try {
  $roots=[Text.Encoding]::UTF8.GetString([Convert]::FromBase64String($RootsBase64))|ConvertFrom-Json
  $pins=@{};foreach($r in $roots){$pins[[int]$r.pid]=Pin-Birth ([int]$r.pid)}
  $table=@(Get-CimInstance Win32_Process -ErrorAction Stop)
  $rows=@{};$children=@{};$identities=@{}
  foreach($r in $table){
    $id=[int]$r.ProcessId;$parent=[int]$r.ParentProcessId
    $b=if($r.CreationDate){$r.CreationDate.ToFileTimeUtc().ToString()}else{$null}
    $rows[$id]=@{pid=$id;parent=$parent;birth=$b;name=$r.Name;executable=$r.ExecutablePath}
    $identities["$id"]=@{present=$true;creationFileTime=$b;name=$r.Name;executable=$r.ExecutablePath}
    if(-not $children.ContainsKey($parent)){$children[$parent]=New-Object 'System.Collections.Generic.List[int]'}
    $children[$parent].Add($id)
  }
  $found=@{};$rootIds=@{};foreach($r in $roots){$rootIds[[int]$r.pid]=$true}
  foreach($r in $roots){
    $id=[int]$r.pid;$expected=[long]0;[void][long]::TryParse([string]$r.creationFileTimeUtc,[ref]$expected)
    $pin=$pins[$id]
    if($null -ne $pin){$identities["$id"]=@{present=(-not $censusEnded[$id]);creationFileTime="$pin";name=$rows[$id].name;executable=$rows[$id].executable}}
    elseif(-not $rows.ContainsKey($id)){$identities["$id"]=@{present=$false}}
    $tick=if($r.birthTick -gt 0){[long]$r.birthTick}else{10}
    $owned=$expected -gt 0 -and $null -ne $pin -and ($pin-($pin%$tick)) -eq ($expected-($expected%$tick))
    $replacement=if($null -ne $pin -and $expected -gt 0 -and -not $owned){[long]$pin}else{$null}
    $q=New-Object 'System.Collections.Generic.Queue[object]';$visited=@{}
    $q.Enqueue(@{pid=$id;birth=if($owned){$pin}else{$expected};owned=$owned;replacement=$replacement})
    while($q.Count -gt 0){
      $parent=$q.Dequeue();if($visited.ContainsKey($parent.pid)){continue};$visited[$parent.pid]=$true
      foreach($childId in $children[$parent.pid]){
        $row=$rows[$childId];$b=if($row.birth){[long]$row.birth}else{$null}
        if($null -ne $b -and $parent.birth -gt 0 -and $b -lt $parent.birth){continue}
        # Children of a replacement root cannot belong to the recorded incarnation.
        if($null -ne $parent.replacement -and $null -ne $b -and $b -ge $parent.replacement){continue}
        $childPin=Pin-Birth $childId
        $same=$null -ne $childPin -and $null -ne $b -and ($childPin-($childPin%10)) -eq ($b-($b%10))
        $childOwned=$parent.owned -and $same
        $savedBirth=if($same){"$childPin"}else{$row.birth}
        if($same){$identities["$childId"]=@{present=(-not $censusEnded[$childId]);creationFileTime="$childPin";name=$row.name;executable=$row.executable}}
        if(-not $rootIds.ContainsKey($childId) -and -not $censusEnded[$childId]){
          $found[$childId]=@{pid=$childId;creationFileTimeUtc=$savedBirth;name=$row.name;owned=$childOwned}
        }
        $q.Enqueue(@{pid=$childId;birth=if($b){$b}else{0};owned=$childOwned;replacement=$null})
      }
    }
  }
  [Console]::Out.WriteLine((@{ok=$true;outcome='inspected';complete=$true;processes=@($found.Values);identities=$identities}|ConvertTo-Json -Depth 6 -Compress))
}catch{
  [Console]::Out.WriteLine((@{ok=$false;outcome='enumeration-failed';complete=$false;processes=@();error=$_.Exception.Message}|ConvertTo-Json -Compress))
}finally{foreach($h in $handles){[void][JustSearchDev.CensusNative]::CloseHandle($h)}}
}
if ($Session) {
  # Query and idle limits belong to this helper itself: never terminate it by a numeric PID.
  [JustSearchDev.CensusNative]::Arm(60000)
  while ($null -ne ($request = [Console]::In.ReadLine())) {
    [JustSearchDev.CensusNative]::Arm(10000)
    Read-Census $request
    [JustSearchDev.CensusNative]::Arm(60000)
  }
} else {
  Read-Census $RootsBase64
}
