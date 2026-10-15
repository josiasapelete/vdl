; Installeur Windows de VDL (Inno Setup 6).
; Compilé par packaging/build_windows.ps1 :  ISCC /DAppVersion=x.y.z vdl.iss
;
; Installation sans droits administrateur, dans %LOCALAPPDATA%\Programs\VDL.

#ifndef AppVersion
  #define AppVersion "0.0.0"
#endif

[Setup]
AppId={{6C1E2F7A-3B9D-4E51-9A0C-5D2B8E4F1A73}
AppName=VDL
AppVersion={#AppVersion}
AppVerName=VDL {#AppVersion}
AppPublisher=VDL
DefaultDirName={localappdata}\Programs\VDL
DefaultGroupName=VDL
DisableProgramGroupPage=yes
PrivilegesRequired=lowest
OutputDir=..\dist
OutputBaseFilename=VDL-Setup-{#AppVersion}
SetupIconFile=vdl.ico
UninstallDisplayIcon={app}\VDL.exe
UninstallDisplayName=VDL
Compression=lzma2/max
SolidCompression=yes
WizardStyle=modern
ArchitecturesAllowed=x64compatible
ArchitecturesInstallIn64BitMode=x64compatible
CloseApplications=force
RestartApplications=no

[Languages]
Name: "french"; MessagesFile: "compiler:Languages\French.isl"
Name: "english"; MessagesFile: "compiler:Default.isl"

[Tasks]
Name: "desktopicon"; Description: "{cm:CreateDesktopIcon}"; GroupDescription: "{cm:AdditionalIcons}"
Name: "startup"; Description: "Lancer VDL au démarrage de Windows (recommandé pour l'extension navigateur)"; GroupDescription: "Options :"

[Files]
Source: "..\dist\VDL\*"; DestDir: "{app}"; Flags: ignoreversion recursesubdirs createallsubdirs

[Icons]
Name: "{autoprograms}\VDL"; Filename: "{app}\VDL.exe"
Name: "{autoprograms}\VDL - Extension navigateur"; Filename: "{app}\extension"
Name: "{autodesktop}\VDL"; Filename: "{app}\VDL.exe"; Tasks: desktopicon
Name: "{userstartup}\VDL"; Filename: "{app}\VDL.exe"; Parameters: "--minimized"; Tasks: startup

[Run]
Filename: "{app}\VDL.exe"; Description: "{cm:LaunchProgram,VDL}"; Flags: nowait postinstall skipifsilent
; Mise à jour lancée depuis VDL (mode silencieux) : on relance l'application
Filename: "{app}\VDL.exe"; Flags: nowait; Check: WizardSilent

[InstallDelete]
; Une mise à jour remplace entièrement l'ancienne version (fichiers obsolètes inclus)
Type: filesandordirs; Name: "{app}\_internal"

[UninstallRun]
Filename: "{sys}\taskkill.exe"; Parameters: "/IM VDL.exe /F"; Flags: runhidden; RunOnceId: "StopVDL"

[Code]
// Ferme VDL s'il tourne (il reste dans la zone de notification) avant de remplacer ses fichiers
function PrepareToInstall(var NeedsRestart: Boolean): String;
var
  Code: Integer;
begin
  Exec(ExpandConstant('{sys}\taskkill.exe'), '/IM VDL.exe /F', '', SW_HIDE, ewWaitUntilTerminated, Code);
  Result := '';
end;
