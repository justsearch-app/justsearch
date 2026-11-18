; JustSearch NSIS hooks for install/uninstall.
;
; This is wired via `bundle.windows.nsis.installerHooks` in `tauri.conf.json`.
; The goal is to keep install/uninstall deterministic by terminating any running
; JustSearch processes that would otherwise hold file locks (e.g., bundled Java backend).
;
; This file is also the ONLY point at which this repo can influence Tauri's generated
; `installer.nsi` without forking it: the template `!include`s it near the top, before it
; declares the wizard's pages.

; ---------------------------------------------------------------------------------------
; Wizard branding on the two MUI full pages (Welcome, Finish) -- tempdoc 807 R13-F1.
;
; Tempdoc 806 put the version on the wizard via `bundle.copyright`, which the generated
; template renders as `BrandingText "${COPYRIGHT}"`. That draws the bottom strip on the
; interior pages and the uninstaller, but MUI's Welcome and Finish pages are full-window
; and draw NO strip -- so round 13 found those two pages carrying no version at all.
;
; MUI takes both page texts through `MUI_DEFAULT` (an `!ifndef`, see
; `Contrib/Modern UI 2/Pages/Welcome.nsh:72` and `Pages/Finish.nsh:156`), so a define made
; here wins over MUI's default. `${COPYRIGHT}` is declared by the template AFTER this file
; is included, which does NOT prevent this: NSIS expands a nested `${...}` inside a define
; when the define is USED, not when it is written, and both pages are declared after the
; template's `!define COPYRIGHT` (verified with makensis, tempdoc 807 Part C). The 806-era
; note that "hooks cannot name the version" holds only for IMMEDIATE uses at include time.
;
; The localized MUI text is kept and the branding line appended, so non-English installers
; do not lose their wording. `MUI_FINISHPAGE_TEXT_LARGE` buys the finish page's text box the
; extra rows the appended line needs (40u -> 60u, `Pages/Finish.nsh:287-291`); without it the
; appended line is clipped by the Run / Create-shortcut checkboxes.
!define MUI_WELCOMEPAGE_TEXT "$(MUI_TEXT_WELCOME_INFO_TEXT)$\r$\n$\r$\n${COPYRIGHT}"
!define MUI_FINISHPAGE_TEXT_LARGE
!define MUI_FINISHPAGE_TEXT "$(MUI_TEXT_FINISH_INFO_TEXT)$\r$\n$\r$\n${COPYRIGHT}"
; ---------------------------------------------------------------------------------------

!macro JustSearch_KillProcesses_BestEffort
  ; Kill processes that are part of this installation, scoped by:
  ; - ExecutablePath under $INSTDIR
  ;
  ; This is best-effort and should never abort install/uninstall.
  DetailPrint "JustSearch: stopping running processes (best-effort)..."
  ; IMPORTANT: do NOT match on CommandLine containing $INSTDIR because the NSIS installer itself can include
  ; `/D=$INSTDIR` in its command line, which would cause the installer to kill itself.
  ; Also exclude the current installer/uninstaller executable ($EXEPATH) so uninstall doesn't self-terminate.
  ; Use Start-Job + Wait-Job to enforce a 15-second timeout so a hung process cannot stall the installer.
  ExecWait "$SYSDIR\WindowsPowerShell\v1.0\powershell.exe -NoProfile -NonInteractive -WindowStyle Hidden -ExecutionPolicy Bypass -Command $\"try { $$j = Start-Job -ScriptBlock { param($$instDir, $$selfExe) $$procs = Get-CimInstance Win32_Process -ErrorAction SilentlyContinue; foreach ($$p in $$procs) { $$exe = [string]$$p.ExecutablePath; if ([string]::IsNullOrEmpty($$exe)) { continue }; if ($$exe.Equals($$selfExe, [System.StringComparison]::OrdinalIgnoreCase)) { continue }; if ($$exe.StartsWith($$instDir, [System.StringComparison]::OrdinalIgnoreCase)) { try { Stop-Process -Id $$p.ProcessId -Force -ErrorAction SilentlyContinue } catch {} } }; Start-Sleep -Milliseconds 300 } -ArgumentList '$INSTDIR','$EXEPATH'; $$null = Wait-Job $$j -Timeout 15; Remove-Job $$j -Force -ErrorAction SilentlyContinue } catch {}$\""
!macroend

!macro JustSearch_EnsureProgramDataPolicyDir_BestEffort
  ; Create the machine policy directory so enterprise admins have a stable, discoverable location
  ; for %PROGRAMDATA%\JustSearch\policy.v1.json.
  ;
  ; This is best-effort: per-user installs may not have permission and should not fail.
  DetailPrint "JustSearch: ensuring ProgramData policy directory exists (best-effort)..."
  ClearErrors
  CreateDirectory "$COMMONAPPDATA\JustSearch"
!macroend

!macro JustSearch_EnsureVcRedist
  ; Visual C++ 2015-2022 Redistributable (x64) provides MSVCP140.dll / VCRUNTIME140.dll
  ; that the bundled llama-server.exe links against. A clean Windows Sandbox
  ; (and many enterprise/VDI envs) does not ship the redist, so the bundled
  ; binaries fail with STATUS_DLL_NOT_FOUND on first launch.
  ; Tempdoc 374 sandbox round 2 finding #13.
  ClearErrors
  ReadRegDWORD $0 HKLM "SOFTWARE\Microsoft\VisualStudio\14.0\VC\Runtimes\x64" "Installed"
  ${If} ${Errors}
  ${OrIf} $0 != "1"
    DetailPrint "JustSearch: installing Visual C++ 2015-2022 redistributable..."
    IfFileExists "$INSTDIR\resources\vc_redist.x64.exe" 0 +3
      ExecWait '"$INSTDIR\resources\vc_redist.x64.exe" /install /quiet /norestart' $1
      DetailPrint "JustSearch: VC++ redist installer exit=$1"
  ${EndIf}
!macroend

!macro NSIS_HOOK_PREINSTALL
  !insertmacro JustSearch_KillProcesses_BestEffort
  ; Tauri runs this hook before copying resources, including in /UPDATE mode.
  ClearErrors
  Delete "$INSTDIR\resources\headless\lib\*.jar"
  ${If} ${Errors}
    MessageBox MB_OK|MB_ICONSTOP "JustSearch could not remove existing Engine JARs. Close any programs using $INSTDIR\resources\headless\lib and run the installer again." /SD IDOK
    SetErrorLevel 2
    Abort "Could not remove existing JustSearch Engine JARs."
  ${EndIf}
  !insertmacro JustSearch_EnsureProgramDataPolicyDir_BestEffort
!macroend

!macro NSIS_HOOK_POSTINSTALL
  ; Runs after $INSTDIR\resources is populated, so the bundled redist exists on disk.
  !insertmacro JustSearch_EnsureVcRedist
!macroend

!macro NSIS_HOOK_PREUNINSTALL
  !insertmacro JustSearch_KillProcesses_BestEffort
  ; Clean up machine policy directory if empty (best-effort, non-recursive).
  RMDir "$COMMONAPPDATA\JustSearch"
!macroend


