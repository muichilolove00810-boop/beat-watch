# 비트워치 배포: 버전 올리기 -> 워치/폰 릴리스 빌드 -> GitHub Releases 업로드 -> 바탕화면 복사
# 사용법:  .\release.ps1 1.1 "변경 내용"
param(
    [Parameter(Mandatory = $true)][string]$VersionName,
    [string]$Notes = "업데이트"
)
$ErrorActionPreference = "Stop"
Set-Location $PSScriptRoot
$env:JAVA_HOME = "C:\Program Files\Microsoft\jdk-21.0.12.8-hotspot"

$propFile = "gradle.properties"
$g = Get-Content $propFile -Raw -Encoding UTF8
$code = [int]([regex]::Match($g, 'beatVersionCode=(\d+)').Groups[1].Value)
$prev = (gh release list --limit 1 --json tagName --jq '.[].tagName') 2>$null
if ($prev) {
    $code++
    $g = $g -replace 'beatVersionCode=\d+', "beatVersionCode=$code"
}
$g = $g -replace 'beatVersionName=.*', "beatVersionName=$VersionName"
[IO.File]::WriteAllText((Resolve-Path $propFile), $g, (New-Object Text.UTF8Encoding $false))

.\gradlew.bat :wear:assembleRelease :phone:assembleRelease --console=plain
if ($LASTEXITCODE -ne 0) { throw "빌드 실패" }

$out = "build\release-upload"
New-Item -ItemType Directory -Force $out | Out-Null
Copy-Item "wear\build\outputs\apk\release\wear-release.apk" "$out\beat-watch.apk" -Force
Copy-Item "phone\build\outputs\apk\release\phone-release.apk" "$out\beat-maker.apk" -Force
[IO.File]::WriteAllText("$PWD\$out\version.json", "{`"versionCode`":$code,`"versionName`":`"$VersionName`"}", (New-Object Text.UTF8Encoding $false))

gh release create "v$VersionName" "$out\beat-watch.apk" "$out\beat-maker.apk" "$out\version.json" --title "$VersionName" --notes "$Notes" --latest
if ($LASTEXITCODE -ne 0) { throw "업로드 실패" }
Copy-Item "$out\beat-watch.apk" "$env:USERPROFILE\Desktop\비트워치(워치용).apk" -Force
Copy-Item "$out\beat-maker.apk" "$env:USERPROFILE\Desktop\비트메이커(폰용).apk" -Force
Write-Host "배포 완료: v$VersionName (versionCode $code)"