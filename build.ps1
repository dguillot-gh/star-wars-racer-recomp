<#
.SYNOPSIS
    Builds the Star Wars Episode I: Racer Android app from your own ROM, in one go.

.DESCRIPTION
    Checks the tools and the ROM, builds N64Recomp and RSPRecomp (once), recompiles the game from baserom.z64
    (only when it or the recompiler inputs changed), builds the APK with the Gradle wrapper and, if a phone is
    connected over USB, installs it. Each step stops with a plain message when something is missing.

.PARAMETER Rom
    Your dump (.z64, .v64 or .n64). It is converted to .z64 and copied to baserom.z64; the original is only read.
    Not needed once baserom.z64 is in place.

.PARAMETER NoInstall
    Build only; do not install on a connected phone.

.EXAMPLE
    .\build.ps1 -Rom "C:\Games\Star Wars Episode I - Racer (USA).z64"

.EXAMPLE
    .\build.ps1
#>
param(
    [string]$Rom,
    [switch]$NoInstall
)

$ErrorActionPreference = 'Stop'
Set-Location -LiteralPath $PSScriptRoot

$KnownSha1 = '3542d5597c8a56ea8f5c63bceae97a24c4c08d58'
$NdkVersion = '28.2.13676358'
$Tools = Join-Path $PSScriptRoot 'build\recomp-tools'

function Step([string]$text) { Write-Host ''; Write-Host "== $text" -ForegroundColor Cyan }
function Ok([string]$text) { Write-Host "   ok  $text" -ForegroundColor Green }
function Fail([string]$text) {
    Write-Host ''
    Write-Host "STOPPED: $text" -ForegroundColor Red
    exit 1
}
function Run([string]$what, [string]$exe, [string[]]$arguments, [string]$log) {
    # Tools print warnings on stderr; in Windows PowerShell 5.1 that would stop the script, so judge by exit code.
    $ErrorActionPreference = 'Continue'
    & $exe @arguments *> $log
    if ($LASTEXITCODE -ne 0) {
        Get-Content -LiteralPath $log -Tail 25 | ForEach-Object { Write-Host "   $_" }
        Fail "$what failed (exit code $LASTEXITCODE). Full log: $log"
    }
}
function Expect([string]$log, [string]$text, [string]$what) {
    if (-not (Select-String -LiteralPath $log -SimpleMatch $text -Quiet)) {
        Get-Content -LiteralPath $log -Tail 15 | ForEach-Object { Write-Host "   $_" }
        Fail "$what did not report '$text'. Full log: $log"
    }
}

# ---------------------------------------------------------------------------------------------------------------
Step '1/6  Checking the tools'

if (-not (Test-Path -LiteralPath 'lib\N64ModernRuntime\N64Recomp\CMakeLists.txt') -or
    -not (Test-Path -LiteralPath 'lib\rt64\CMakeLists.txt') -or -not (Test-Path -LiteralPath 'lib\SDL2\CMakeLists.txt')) {
    Fail "the libraries in lib\ are missing. Run: git submodule update --init --recursive"
}
Ok 'libraries in lib\'

$python = Get-Command python -ErrorAction SilentlyContinue
if (-not $python -or $python.Source -like '*WindowsApps*') {
    Fail 'Python 3 was not found. Install it from python.org and tick "Add python.exe to PATH".'
}
Ok "python ($($python.Source))"

if (-not (Get-Command clang++ -ErrorAction SilentlyContinue)) {
    if (Test-Path -LiteralPath 'C:\llvm-mingw\bin\clang++.exe') {
        $env:PATH = "C:\llvm-mingw\bin;$env:PATH"
    } else {
        Fail 'clang was not found. Unzip LLVM-MinGW (README step 1) to C:\llvm-mingw, or add its bin folder to PATH.'
    }
}
Ok "clang ($((Get-Command clang++).Source))"

if (-not $env:ANDROID_HOME) { $env:ANDROID_HOME = Join-Path $env:LOCALAPPDATA 'Android\Sdk' }
if (-not (Test-Path -LiteralPath $env:ANDROID_HOME)) {
    Fail "the Android SDK was not found at $env:ANDROID_HOME. Install Android Studio, or set `$env:ANDROID_HOME."
}
Ok "Android SDK ($env:ANDROID_HOME)"

$sdkCmake = Join-Path $env:ANDROID_HOME 'cmake\3.22.1\bin'
if (-not (Test-Path -LiteralPath (Join-Path $sdkCmake 'cmake.exe'))) {
    Fail 'CMake 3.22.1 is missing from the SDK. Android Studio > SDK Manager > SDK Tools > Show Package Details > CMake 3.22.1.'
}
$env:PATH = "$sdkCmake;$(Join-Path $env:ANDROID_HOME 'platform-tools');$env:PATH"
Ok 'CMake 3.22.1 and Ninja'

if (-not (Test-Path -LiteralPath (Join-Path $env:ANDROID_HOME "ndk\$NdkVersion"))) {
    Fail "NDK $NdkVersion is missing. Android Studio > SDK Manager > SDK Tools > Show Package Details > NDK (Side by side) $NdkVersion."
}
Ok "NDK $NdkVersion"

if (-not (Test-Path -LiteralPath (Join-Path $env:ANDROID_HOME 'platforms\android-35'))) {
    Fail 'Android 15 (API 35) is missing. Android Studio > SDK Manager > SDK Platforms > Android 15.'
}
Ok 'Android 15 (API 35) platform'

if (-not $env:JAVA_HOME) {
    $jbr = 'C:\Program Files\Android\Android Studio\jbr'
    if (Test-Path -LiteralPath $jbr) { $env:JAVA_HOME = $jbr }
}
$java = if ($env:JAVA_HOME) { Join-Path $env:JAVA_HOME 'bin\java.exe' } else { '' }
if (-not $java -or -not (Test-Path -LiteralPath $java)) {
    Fail 'Java was not found. Install Android Studio (it brings Java), or set $env:JAVA_HOME to a JDK 17 or newer.'
}
Ok "Java ($env:JAVA_HOME)"

# ---------------------------------------------------------------------------------------------------------------
Step '2/6  Checking your ROM'

if ($Rom) {
    if (-not (Test-Path -LiteralPath $Rom)) { Fail "the ROM '$Rom' does not exist." }
    $ErrorActionPreference = 'Continue'
    & $python.Source -I tools\rom_to_z64.py $Rom baserom.z64
    if ($LASTEXITCODE -ne 0) { Fail "'$Rom' is not an N64 ROM." }
    $ErrorActionPreference = 'Stop'
}
if (-not (Test-Path -LiteralPath 'baserom.z64')) {
    Fail 'no ROM yet. Run: .\build.ps1 -Rom "C:\path\to\your\Star Wars Episode I - Racer (USA).z64"  (.v64 / .n64 work too)'
}
$sha1 = (Get-FileHash -LiteralPath 'baserom.z64' -Algorithm SHA1).Hash.ToLower()
if ($sha1 -ne $KnownSha1) {
    Fail "baserom.z64 is not the supported dump (SHA-1 $sha1). You need Star Wars Episode I: Racer (USA); expected $KnownSha1."
}
Ok 'baserom.z64 is Star Wars Episode I: Racer (USA)'

# ---------------------------------------------------------------------------------------------------------------
Step '3/6  Building N64Recomp and RSPRecomp'

$n64recomp = Join-Path $Tools 'N64Recomp.exe'
$rsprecomp = Join-Path $Tools 'RSPRecomp.exe'
if ((Test-Path -LiteralPath $n64recomp) -and (Test-Path -LiteralPath $rsprecomp)) {
    Ok 'already built'
} else {
    New-Item -ItemType Directory -Force 'build' | Out-Null
    # -include cstdlib: N64Recomp's bundled rabbitizer misses an #include that current clang versions reject.
    Run 'Configuring the recompilers' 'cmake' @('-S', 'lib/N64ModernRuntime/N64Recomp', '-B', 'build/recomp-tools', '-G', 'Ninja',
        '-DCMAKE_BUILD_TYPE=Release', '-DCMAKE_C_COMPILER=clang', '-DCMAKE_CXX_COMPILER=clang++',
        '-DCMAKE_CXX_FLAGS=-include cstdlib') 'build\recomp-tools-configure.log'
    Run 'Building the recompilers' 'cmake' @('--build', 'build/recomp-tools', '--target', 'N64RecompCLI', 'RSPRecomp') 'build\recomp-tools-build.log'
    Ok 'built'
}

# ---------------------------------------------------------------------------------------------------------------
Step '4/6  Recompiling the game from your ROM'

# Only when something it depends on changed: a fresh recompile makes the app build compile all game code again.
$stamp = 'build\recompile.stamp'
$inputs = @('baserom.z64', 'symbols.toml', 'recomp.toml', 'aspMain.toml', $n64recomp, $rsprecomp) +
          @(Get-ChildItem -LiteralPath 'tools' -Filter '*.py' | ForEach-Object { $_.FullName })
$needed = -not (Test-Path -LiteralPath $stamp) -or -not (Test-Path -LiteralPath 'RecompiledFuncs\lookup.cpp') -or
          -not (Test-Path -LiteralPath 'rsp\aspMain.cpp')
if (-not $needed) {
    $stampTime = (Get-Item -LiteralPath $stamp).LastWriteTime
    $needed = [bool]($inputs | Where-Object { (Get-Item -LiteralPath $_).LastWriteTime -gt $stampTime })
}
if (-not $needed) {
    Ok 'up to date (nothing it depends on changed)'
} else {
    if (Test-Path -LiteralPath 'RecompiledFuncs') { Get-ChildItem -LiteralPath 'RecompiledFuncs' -File | Remove-Item -Force }
    Run 'N64Recomp' $n64recomp @('recomp.toml') 'build\n64recomp.log'
    Run 'fix_fallthroughs.py' $python.Source @('-I', 'tools\fix_fallthroughs.py') 'build\fix_fallthroughs.log'
    Expect 'build\fix_fallthroughs.log' 'chained to continuation:            143' 'fix_fallthroughs.py'
    Run 'post_recomp_patches.py' $python.Source @('-I', 'tools\post_recomp_patches.py') 'build\post_recomp_patches.log'
    Expect 'build\post_recomp_patches.log' 'post-recomp redirects applied: 25' 'post_recomp_patches.py'
    Run 'gen_lookup_table.py' $python.Source @('-I', 'tools\gen_lookup_table.py') 'build\gen_lookup_table.log'
    Expect 'build\gen_lookup_table.log' '1409 function entries' 'gen_lookup_table.py'
    New-Item -ItemType Directory -Force 'rsp' | Out-Null
    Run 'RSPRecomp' $rsprecomp @('aspMain.toml') 'build\rsprecomp.log'
    if (-not (Test-Path -LiteralPath 'rsp\aspMain.cpp')) { Fail 'RSPRecomp did not write rsp\aspMain.cpp. Log: build\rsprecomp.log' }
    Set-Content -LiteralPath $stamp -Value (Get-Date -Format o)
    Ok 'RecompiledFuncs\ and rsp\aspMain.cpp written (made from your ROM: keep them to yourself)'
}

# ---------------------------------------------------------------------------------------------------------------
Step '5/6  Building the app (the first build compiles the whole game and takes a while)'

# The Gradle wrapper started through Java, as gradlew.bat does (so it also works where cmd.exe is blocked).
$wrapper = Join-Path $PSScriptRoot 'android\gradle\wrapper\gradle-wrapper.jar'
$gradleLog = Join-Path $PSScriptRoot 'build\gradle.log'
$started = Get-Date
$ErrorActionPreference = 'Continue'
& $java '-Xmx64m' '-classpath' $wrapper 'org.gradle.wrapper.GradleWrapperMain' '-p' 'android' ':app:assembleDebug' '--console=plain' *> $gradleLog
if ($LASTEXITCODE -ne 0) {
    Select-String -LiteralPath $gradleLog -Pattern 'error|What went wrong|FAILED' | Select-Object -First 20 |
        ForEach-Object { Write-Host "   $($_.Line)" }
    Fail "the app build failed. Full log: $gradleLog"
}
$apk = Join-Path $PSScriptRoot 'android\app\build\outputs\apk\debug\app-debug.apk'
$minutes = [int]((Get-Date) - $started).TotalMinutes
Ok "built in $minutes min: $apk"
Write-Host '   The APK contains the game: keep it to yourself.' -ForegroundColor Yellow

# ---------------------------------------------------------------------------------------------------------------
Step '6/6  Installing on your phone'

if ($NoInstall) {
    Ok 'skipped (-NoInstall). Install later with: adb install -r android\app\build\outputs\apk\debug\app-debug.apk'
    exit 0
}
$devices = @(& adb devices | Select-String -Pattern "`tdevice$")
if ($devices.Count -eq 0) {
    Ok 'no phone connected (USB debugging on?). Install later with: adb install -r android\app\build\outputs\apk\debug\app-debug.apk'
    exit 0
}
$out = & adb install -r $apk 2>&1 | Out-String
if ($out -match 'Success') {
    Ok 'installed. Open "Star Wars Episode I: Racer", tap "Choose your game ROM", pick your ROM, then Play.'
} elseif ($out -match 'INSTALL_FAILED_UPDATE_INCOMPATIBLE') {
    Fail 'the phone has a copy of the app signed by another PC. Uninstall it first (this deletes its saves), then run again.'
} else {
    Write-Host $out
    Fail 'adb install failed (see above).'
}
