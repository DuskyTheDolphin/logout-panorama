# forgeward_pano -- hand build (javac + jar, no Gradle).
# Rebuild:  powershell -ExecutionPolicy Bypass -File build.ps1
# Override paths with env vars JDK / SERVER if they ever move.
#
# This mod is CLIENT-ONLY; it compiles against the NAMED client jars. No mod jars needed.
# Launchers do not always keep those jars, so they live in .\clientlibs (git-ignored: *.jar).
# If they are missing, this script regenerates them by running the SERVER's own NeoForge installer in client mode
# (it downloads Mojang's client mappings), so the client classes always match the server build.
$ErrorActionPreference = "Stop"

if ($env:JDK) { $JDK = $env:JDK } elseif ($env:JAVA_HOME) { $JDK = $env:JAVA_HOME } else { $JDK = "C:\Program Files\Eclipse Adoptium\jdk-21.0.11.10-hotspot" }
if ($env:SERVER) { $SERVER = $env:SERVER } else { $SERVER = Join-Path $PSScriptRoot "..\..\server" }   # a NeoForge 21.1.250 server dir (its libraries/ and installer)
$PROJ = $PSScriptRoot
$NFV  = "21.1.250"
$MCV  = "1.21.1-20240808.144430"

$JAVA  = Join-Path $JDK "bin\java.exe"
$JAVAC = Join-Path $JDK "bin\javac.exe"
$JAR   = Join-Path $JDK "bin\jar.exe"

$LIB  = Join-Path $SERVER "libraries"
$NF   = Join-Path $LIB "net\neoforged\neoforge\$NFV"

$CLIB       = Join-Path $PROJ "clientlibs"
New-Item -ItemType Directory -Force -Path $CLIB | Out-Null
$CLIENT_SRG = Join-Path $CLIB "client-$MCV-srg.jar"
$CLIENT_NF  = Join-Path $CLIB "neoforge-$NFV-client.jar"

if (-not ((Test-Path $CLIENT_SRG) -and (Test-Path $CLIENT_NF))) {
    Write-Host "[0/2] client jars missing -> NeoForge $NFV installer, client mode ..."
    $INST = Join-Path $SERVER "neoforge-$NFV-installer.jar"
    $TMP  = Join-Path $CLIB "mcclient"
    New-Item -ItemType Directory -Force -Path (Join-Path $TMP "versions\1.21.1") | Out-Null
    Set-Content -Path (Join-Path $TMP "launcher_profiles.json") -Value '{"profiles":{}}' -Encoding ascii
    & $JAVA -jar $INST --install-client $TMP
    if ($LASTEXITCODE -ne 0) { throw "installer failed ($LASTEXITCODE)" }
    Copy-Item (Join-Path $TMP "libraries\net\minecraft\client\$MCV\client-$MCV-srg.jar") $CLIENT_SRG
    Copy-Item (Join-Path $TMP "libraries\net\neoforged\neoforge\$NFV\neoforge-$NFV-client.jar") $CLIENT_NF
    Remove-Item -Recurse -Force $TMP
}

$SPONGE = Join-Path $LIB "net\fabricmc\sponge-mixin\0.15.2+mixin.0.8.7\sponge-mixin-0.15.2+mixin.0.8.7.jar"

$BUILD  = Join-Path $PROJ "build"
$LIBS   = Join-Path $PROJ "libs"
$OUT    = Join-Path $BUILD "classes"
$JAROUT = Join-Path $PROJ "forgeward_pano-1.0.9.jar"

if (Test-Path $BUILD) { Remove-Item -Recurse -Force $BUILD }
New-Item -ItemType Directory -Force -Path $OUT  | Out-Null
New-Item -ItemType Directory -Force -Path $LIBS | Out-Null

# MixinExtras is compile-only (provided by NeoForge at runtime); pull the jar-in-jar
# out of the NeoForge universal jar so the build stays self-contained.
$MEX = Join-Path $LIBS "mixinextras-neoforge-0.5.3.jar"
Add-Type -AssemblyName System.IO.Compression.FileSystem
$uni = [System.IO.Compression.ZipFile]::OpenRead((Join-Path $NF "neoforge-$NFV-universal.jar"))
try {
    $entry = $uni.GetEntry("META-INF/jarjar/mixinextras-neoforge-0.5.3.jar")
    [System.IO.Compression.ZipFileExtensions]::ExtractToFile($entry, $MEX, $true)
} finally { $uni.Dispose() }

function Need($subdir, $pattern) {
    $hit = Get-ChildItem (Join-Path $LIB $subdir) -Recurse -Filter $pattern -ErrorAction SilentlyContinue |
           Sort-Object Name -Descending | Select-Object -First 1
    if (-not $hit) { throw "$pattern not found under $LIB\$subdir" }
    return $hit.FullName
}

$MODJARS = @()   # no mod jars: vanilla + NeoForge only (Iris, Sodium and DH are reached through the vanilla render path)

$CP = @(
    $CLIENT_NF, $CLIENT_SRG,
    (Join-Path $NF "neoforge-$NFV-universal.jar"),
    (Need "net\neoforged\fancymodloader\loader" "loader-*.jar"),
    (Need "net\neoforged\bus" "bus-*.jar"),
    (Need "net\neoforged\mergetool" "mergetool-*-api.jar"),
    (Need "com\mojang\logging" "logging-*.jar"),
    (Need "org\slf4j\slf4j-api" "slf4j-api-*.jar"),
    (Need "it\unimi\dsi\fastutil" "fastutil-*.jar"),
    (Need "com\mojang\datafixerupper" "datafixerupper-*.jar"),
    (Need "com\mojang\brigadier" "brigadier-*.jar"),
    (Need "com\mojang\authlib" "authlib-*.jar"),
    (Need "org\joml" "joml-*.jar"),
    (Need "com\google\code\gson\gson\2.10.1" "gson-*.jar"),
    (Need "io\netty\netty-buffer" "netty-buffer-*.jar"),
    (Need "io\netty\netty-common" "netty-common-*.jar"),
    (Need "io\netty\netty-transport" "netty-transport-4*.jar"),
    $SPONGE, $MEX
) + $MODJARS
$CP = $CP -join ";"

Write-Host "[1/2] javac (Java 21) ..."
$SRC = @(Get-ChildItem -Recurse -Filter *.java (Join-Path $PROJ "src\main\java") | ForEach-Object { $_.FullName })
& $JAVAC -proc:none -encoding UTF-8 --release 21 -classpath $CP -d $OUT $SRC
if ($LASTEXITCODE -ne 0) { throw "javac failed ($LASTEXITCODE)" }

Write-Host "[2/2] jar ..."
$RES = Join-Path $PROJ "src\main\resources"
if (Test-Path $JAROUT) { Remove-Item -Force $JAROUT }
& $JAR --create --file $JAROUT -C $OUT . -C $RES .
if ($LASTEXITCODE -ne 0) { throw "jar failed ($LASTEXITCODE)" }

Write-Host "Built: $JAROUT"
& $JAR --list --file $JAROUT
