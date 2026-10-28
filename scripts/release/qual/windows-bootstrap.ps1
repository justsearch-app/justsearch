$ErrorActionPreference = 'Stop'
Start-Transcript -Path C:\Qual\bootstrap.log -Append
try {
    # Install the inbox Feature on Demand from Microsoft before quieting updates.
    $cap = Get-WindowsCapability -Online -Name OpenSSH.Server~~~~0.0.1.0
    if ($cap.State -ne 'Installed') {
        Add-WindowsCapability -Online -Name OpenSSH.Server~~~~0.0.1.0
    }
    New-Item C:\ProgramData\ssh -ItemType Directory -Force | Out-Null
    Set-Content C:\ProgramData\ssh\administrators_authorized_keys '@SSH_KEY@' -Encoding ascii
    icacls C:\ProgramData\ssh\administrators_authorized_keys /inheritance:r /grant '*S-1-5-32-544:F' /grant '*S-1-5-18:F'
    if ($LASTEXITCODE -ne 0) { throw 'Setting authorized_keys ACL failed' }
    Set-Service sshd -StartupType Automatic
    Start-Service sshd
    if (-not (Get-NetFirewallRule -Name OpenSSH-Server-In-TCP -ErrorAction SilentlyContinue)) {
        New-NetFirewallRule -Name OpenSSH-Server-In-TCP -DisplayName 'OpenSSH server' -Enabled True -Direction Inbound -Protocol TCP -Action Allow -LocalPort 22
    }
    Set-NetFirewallRule -Name OpenSSH-Server-In-TCP -Enabled True -Profile Any
    $sshd = 'C:\ProgramData\ssh\sshd_config'
    Add-Content $sshd "`nPasswordAuthentication no`n"
    Restart-Service sshd
    $wu = 'HKLM:\SOFTWARE\Policies\Microsoft\Windows\WindowsUpdate\AU'
    New-Item $wu -Force | Out-Null
    New-ItemProperty $wu -Name NoAutoUpdate -Value 1 -PropertyType DWord -Force | Out-Null
    Stop-Service wuauserv
    Set-Service wuauserv -StartupType Disabled
    # Keep real-time protection; quiet scheduled scans and idle maintenance only.
    Set-MpPreference -ScanScheduleDay 8 -DisableCatchupFullScan $true -DisableCatchupQuickScan $true
    $content = 'HKCU:\Software\Microsoft\Windows\CurrentVersion\ContentDeliveryManager'
    New-Item $content -Force | Out-Null
    New-ItemProperty $content -Name SubscribedContent-310093Enabled -Value 0 -PropertyType DWord -Force | Out-Null
    New-ItemProperty $content -Name SubscribedContent-338389Enabled -Value 0 -PropertyType DWord -Force | Out-Null
    Set-LocalUser -Name qual -PasswordNeverExpires $true
    powercfg /change monitor-timeout-ac 0
    powercfg /change standby-timeout-ac 0
    Set-Content C:\Qual\ready.txt 'Unattended preparation complete' -Encoding ascii
} catch {
    $_ | Out-String | Set-Content C:\Qual\failed.txt
    throw
} finally {
    Stop-Transcript
}
