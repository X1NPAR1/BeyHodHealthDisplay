Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
Set-Location $PSScriptRoot

Write-Host '=========================================='
Write-Host 'BeyHodHealthDisplay Plugin - Build Helper'
Write-Host '=========================================='
Write-Host ''

function Find-FirstDirectory([string[]]$Patterns) {
    foreach ($pattern in $Patterns) {
        if ([string]::IsNullOrWhiteSpace($pattern)) { continue }
        $items = @(Get-ChildItem -Path $pattern -Directory -ErrorAction SilentlyContinue | Sort-Object FullName -Descending)
        if ($items.Length -gt 0) { return $items[0].FullName }
    }
    return $null
}

function Add-PathFront([string]$PathToAdd) {
    if ([string]::IsNullOrWhiteSpace($PathToAdd)) { return }
    $env:Path = "$PathToAdd;$env:Path"
}

$javaHome = Find-FirstDirectory @(
    "$env:ProgramFiles\Eclipse Adoptium\jdk-21*",
    "$env:ProgramFiles\Java\jdk-21*",
    "$env:ProgramFiles\Microsoft\jdk-21*",
    "$env:ProgramFiles\Amazon Corretto\jdk21*",
    "$env:ProgramFiles\BellSoft\LibericaJDK-21*"
)

if (-not $javaHome) {
    Write-Host 'Java 21 ortak klasorlerde bulunamadi. Temurin Java 21 winget ile kurulmaya calisilacak...'
    winget install -e --id EclipseAdoptium.Temurin.21.JDK --accept-source-agreements --accept-package-agreements
    $javaHome = Find-FirstDirectory @("$env:ProgramFiles\Eclipse Adoptium\jdk-21*")
}

if (-not $javaHome) {
    throw 'Java 21 bulunamadi. Java 21 JDK kurulu olmali.'
}

$env:JAVA_HOME = $javaHome
Add-PathFront "$env:JAVA_HOME\bin"
Write-Host "Java 21 bulundu: $env:JAVA_HOME"
Write-Host 'Aktif Java:'
java -version
Write-Host ''

$mvn = Get-Command mvn -ErrorAction SilentlyContinue
if (-not $mvn) {
    $mavenHome = Find-FirstDirectory @(
        "$env:ProgramFiles\Apache\Maven\apache-maven-*",
        "$env:ProgramFiles\Maven\apache-maven-*",
        "${env:ProgramFiles(x86)}\Apache\Maven\apache-maven-*"
    )

    if (-not $mavenHome) {
        $mavenVersion = '3.9.9'
        $buildDir = Join-Path $PSScriptRoot '.build'
        $mavenHome = Join-Path $buildDir "apache-maven-$mavenVersion"
        $zipPath = Join-Path $buildDir "apache-maven-$mavenVersion-bin.zip"

        if (-not (Test-Path $mavenHome)) {
            New-Item -ItemType Directory -Force -Path $buildDir | Out-Null
            Write-Host "Maven PATH icinde bulunamadi. Proje icine portable Maven $mavenVersion indirilecek..."
            $urls = @(
                "https://archive.apache.org/dist/maven/maven-3/$mavenVersion/binaries/apache-maven-$mavenVersion-bin.zip",
                "https://dlcdn.apache.org/maven/maven-3/$mavenVersion/binaries/apache-maven-$mavenVersion-bin.zip"
            )

            $downloaded = $false
            foreach ($url in $urls) {
                try {
                    Write-Host "Indiriliyor: $url"
                    Invoke-WebRequest -Uri $url -OutFile $zipPath -UseBasicParsing
                    $downloaded = $true
                    break
                } catch {
                    Write-Host 'Bu adresten indirilemedi, digeri deneniyor...'
                }
            }

            if (-not $downloaded) {
                throw 'Maven otomatik indirilemedi. Internet baglantisini kontrol et veya Maven zipini manuel kur.'
            }

            Write-Host 'Maven zip aciliyor...'
            Expand-Archive -Path $zipPath -DestinationPath $buildDir -Force
        }
    }

    if (-not (Test-Path $mavenHome)) {
        throw 'Maven bulunamadi veya indirilen Maven klasoru olusmadi.'
    }

    $env:MAVEN_HOME = $mavenHome
    Add-PathFront "$env:MAVEN_HOME\bin"
}

Write-Host 'Aktif Maven:'
mvn -version
Write-Host ''

Write-Host 'Plugin derleniyor...'
mvn clean package

$jarPath = Join-Path $PSScriptRoot 'target\BeyHodHealthDisplay.jar'
if (-not (Test-Path $jarPath)) {
    throw "Build tamamlandi gibi gorunuyor ama jar bulunamadi: $jarPath"
}

Write-Host ''
Write-Host '=========================================='
Write-Host 'BUILD SUCCESS'
Write-Host "Plugin jar: $jarPath"
Write-Host 'Bu .jar dosyasini sunucunun plugins klasorune at.'
Write-Host '=========================================='
