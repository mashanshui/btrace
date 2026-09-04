<#
Copyright (C) 2021 ByteDance Inc

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

除非适用法律要求或书面同意，依据本许可证发布的软件均按“原样”提供，
不附带任何明示或暗示的保证或条件。

.SYNOPSIS
从已连接 Android 设备拉取 ZIP 产物，并使用 rhea-trace-processor 解析。

.DESCRIPTION
脚本默认把原始 ZIP 和解析结果写入项目根目录的 build/device-zip-<文件名>/。
输入路径既支持 adb 可直接读取的公共目录，也支持 /data/user/0/<包名>/... 等
应用私有目录。应用私有目录会在 adb pull 失败时自动尝试 run-as。

.EXAMPLE
.\scripts\parse-device-zip.ps1

.EXAMPLE
.\scripts\parse-device-zip.ps1 -Device 99041FFBA0004Q `
    -RemotePath /data/user/0/rhea.sample.android/no_backup/rhea/stack/demo-jank.rheajank.zip

.EXAMPLE
.\scripts\parse-device-zip.ps1 -BuildProcessor
#>

[CmdletBinding()]
param(
    # 多台设备时可直接指定 adb serial；不指定则运行时选择。
    [string]$Device,

    # 不指定时运行时提示输入手机上的 ZIP 路径。
    [string]$RemotePath,

    # 默认是项目根目录的 build；相对路径相对于当前 PowerShell 目录。
    [string]$OutputRoot,

    # 可选的 ProGuard/R8 mapping 文件。
    [string]$Mapping,

    # 强制先执行 :rhea-trace-processor:jar。
    [switch]$BuildProcessor
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version 2.0

$repoRoot = Split-Path -Parent $PSScriptRoot
$gradleProperties = Join-Path $repoRoot 'gradle.properties'
$processorLibs = Join-Path $repoRoot 'rhea-tool\rhea-trace-processor\build\libs'
$gradleWrapper = Join-Path $repoRoot 'gradlew.bat'

function Assert-Command {
    param(
        [Parameter(Mandatory = $true)]
        [string]$Name,

        [Parameter(Mandatory = $true)]
        [string]$Hint
    )

    if ($null -eq (Get-Command -Name $Name -ErrorAction SilentlyContinue)) {
        throw "未找到 $Name，请先安装并加入 PATH。$Hint"
    }
}

function Get-ConnectedDevices {
    $previousPreference = $ErrorActionPreference
    try {
        # adb 首次启动 daemon 时可能将正常提示写到 stderr，不能让 Stop 策略提前中断。
        $ErrorActionPreference = 'Continue'
        $output = @(& adb devices 2>&1)
        $exitCode = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $previousPreference
    }
    if ($exitCode -ne 0) {
        throw "执行 adb devices 失败：$($output -join ' ')"
    }

    $devices = @()
    foreach ($line in $output) {
        $text = ([string]$line).Trim()
        $match = [regex]::Match($text, '^(?<serial>\S+)\s+device(?:\s|$)')
        if ($match.Success) {
            $devices += $match.Groups['serial'].Value
        }
    }
    return @($devices)
}

function Select-Device {
    param(
        [Parameter(Mandatory = $true)]
        [string[]]$ConnectedDevices,

        [string]$RequestedDevice
    )

    if (-not [string]::IsNullOrWhiteSpace($RequestedDevice)) {
        if ($ConnectedDevices -notcontains $RequestedDevice) {
            throw "指定设备 '$RequestedDevice' 不在已连接的 device 状态设备中。"
        }
        return $RequestedDevice
    }

    if ($ConnectedDevices.Count -eq 1) {
        return $ConnectedDevices[0]
    }

    Write-Host '检测到多台设备，请选择目标设备：'
    for ($i = 0; $i -lt $ConnectedDevices.Count; $i++) {
        Write-Host ("  [{0}] {1}" -f ($i + 1), $ConnectedDevices[$i])
    }

    $choice = Read-Host '请输入设备序号'
    $index = 0
    $validIndex = [int]::TryParse($choice, [ref]$index)
    if (-not $validIndex -or $index -lt 1 -or $index -gt $ConnectedDevices.Count) {
        throw '设备序号无效。'
    }
    return $ConnectedDevices[$index - 1]
}

function Normalize-RemotePath {
    param(
        [Parameter(Mandatory = $true)]
        [string]$Value
    )

    $normalized = $Value.Trim().Trim('"').Trim("'").Replace('\', '/')
    if ([string]::IsNullOrWhiteSpace($normalized)) {
        throw '手机 ZIP 地址不能为空。'
    }
    if (-not $normalized.StartsWith('/')) {
        throw "手机路径必须是 Android 绝对路径，例如 /data/user/0/<包名>/...：$normalized"
    }

    $fileName = ($normalized -split '/')[-1]
    if ([string]::IsNullOrWhiteSpace($fileName) -or $fileName -notmatch '(?i)\.zip$') {
        throw "输入路径不是 ZIP 文件：$normalized"
    }
    return $normalized
}

function Get-RunAsPackage {
    param(
        [Parameter(Mandatory = $true)]
        [string]$Path
    )

    # 应用私有目录需要 debuggable 包提供 run-as；公共目录仍走普通 adb pull。
    $match = [regex]::Match($Path, '^/data/(?:user(?:_de)?/\d+|data)/([^/]+)/')
    if ($match.Success) {
        return $match.Groups[1].Value
    }
    return $null
}

function Get-SafeArtifactStem {
    param(
        [Parameter(Mandatory = $true)]
        [string]$FileName
    )

    $stem = [regex]::Replace($FileName, '(?i)\.zip$', '')
    $stem = [regex]::Replace($stem, '(?i)\.(rheajank|rheatrace)$', '')
    $safeStem = [regex]::Replace($stem, '[^A-Za-z0-9._-]', '_')
    if ([string]::IsNullOrWhiteSpace($safeStem)) {
        $safeStem = 'artifact-' + (Get-Date -Format 'yyyyMMdd-HHmmss')
    }
    return $safeStem
}

function Resolve-OutputRoot {
    param(
        [string]$RequestedRoot
    )

    if ([string]::IsNullOrWhiteSpace($RequestedRoot)) {
        return (Join-Path $repoRoot 'build')
    }
    if ([System.IO.Path]::IsPathRooted($RequestedRoot)) {
        return [System.IO.Path]::GetFullPath($RequestedRoot)
    }
    return [System.IO.Path]::GetFullPath((Join-Path (Get-Location).Path $RequestedRoot))
}

function Read-ProcessorVersion {
    if (-not (Test-Path -LiteralPath $gradleProperties -PathType Leaf)) {
        return $null
    }

    foreach ($line in Get-Content -LiteralPath $gradleProperties) {
        if ($line -match '^\s*POM_VERSION_NAME\s*=\s*(\S+)') {
            return $Matches[1]
        }
    }
    return $null
}

function Find-ProcessorJar {
    param(
        [string]$Version
    )

    if (-not (Test-Path -LiteralPath $processorLibs -PathType Container)) {
        return $null
    }

    if (-not [string]::IsNullOrWhiteSpace($Version)) {
        $expectedPath = Join-Path $processorLibs ("rhea-trace-processor-{0}.jar" -f $Version)
        if (Test-Path -LiteralPath $expectedPath -PathType Leaf) {
            return Get-Item -LiteralPath $expectedPath
        }
    }

    $candidates = @(
        Get-ChildItem -LiteralPath $processorLibs -File -Filter 'rhea-trace-processor-*.jar' |
            Where-Object { $_.Name -notmatch '(?i)-(sources|javadoc)\.jar$' } |
            Sort-Object LastWriteTime -Descending
    )
    if ($candidates.Count -gt 0) {
        return $candidates[0]
    }
    return $null
}

function Get-ProcessorJar {
    param(
        [switch]$ForceBuild
    )

    $version = Read-ProcessorVersion
    $jar = $null
    if (-not $ForceBuild) {
        $jar = Find-ProcessorJar -Version $version
    }

    if ($null -eq $jar) {
        if (-not (Test-Path -LiteralPath $gradleWrapper -PathType Leaf)) {
            throw "未找到 Processor JAR，且项目根目录没有 Gradle Wrapper：$gradleWrapper"
        }

        Write-Host '未找到可用的 Processor JAR，正在构建 :rhea-trace-processor:jar ...' -ForegroundColor Yellow
        $buildOutput = @()
        Push-Location $repoRoot
        try {
            $previousPreference = $ErrorActionPreference
            try {
                $ErrorActionPreference = 'Continue'
                $buildOutput = @(& $gradleWrapper ':rhea-trace-processor:jar' '--no-daemon' 2>&1)
                $buildExitCode = $LASTEXITCODE
            } finally {
                $ErrorActionPreference = $previousPreference
            }
        } finally {
            Pop-Location
        }
        foreach ($line in $buildOutput) {
            Write-Host ([string]$line)
        }
        if ($buildExitCode -ne 0) {
            throw "构建 Processor JAR 失败，退出码：$buildExitCode"
        }
        $jar = Find-ProcessorJar -Version $version
    }

    if ($null -eq $jar) {
        throw "未找到 rhea-trace-processor JAR，请检查 $processorLibs"
    }
    return $jar
}

function Get-RemoteSha256 {
    param(
        [Parameter(Mandatory = $true)]
        [string]$SelectedDevice,

        [Parameter(Mandatory = $true)]
        [string]$Path,

        [string]$Package
    )

    $hashArgs = @('-s', $SelectedDevice, 'shell')
    if (-not [string]::IsNullOrWhiteSpace($Package)) {
        $hashArgs += @('run-as', $Package)
    }
    $hashArgs += @('sha256sum', $Path)
    $output = @(& adb @hashArgs 2>$null)
    if ($LASTEXITCODE -ne 0) {
        return $null
    }

    $match = [regex]::Match(($output -join "`n"), '(?i)\b[0-9a-f]{64}\b')
    if ($match.Success) {
        return $match.Value.ToUpperInvariant()
    }
    return $null
}

try {
    Assert-Command -Name 'adb' -Hint 'Android SDK platform-tools 提供 adb。'
    Assert-Command -Name 'java' -Hint '请安装 JDK 并配置 JAVA_HOME/PATH。'

    $connectedDevices = @(Get-ConnectedDevices)
    if ($connectedDevices.Count -eq 0) {
        throw '没有检测到处于 device 状态的 Android 设备，请连接手机并确认 adb 已授权。'
    }
    $selectedDevice = Select-Device -ConnectedDevices $connectedDevices -RequestedDevice $Device

    if ([string]::IsNullOrWhiteSpace($RemotePath)) {
        $RemotePath = Read-Host '请输入手机上的 ZIP 文件地址（例如 /data/user/0/rhea.sample.android/no_backup/rhea/stack/demo-jank.rheajank.zip）'
    }
    $remotePath = Normalize-RemotePath -Value $RemotePath
    $runAsPackage = Get-RunAsPackage -Path $remotePath

    $remoteFileName = ($remotePath -split '/')[-1]
    $safeFileName = [regex]::Replace($remoteFileName, '[<>:"/\\|?*]', '_')
    $safeStem = Get-SafeArtifactStem -FileName $remoteFileName
    $root = Resolve-OutputRoot -RequestedRoot $OutputRoot
    $outputDir = Join-Path $root ("device-zip-{0}" -f $safeStem)
    New-Item -ItemType Directory -Force -Path $outputDir | Out-Null
    $localZip = Join-Path $outputDir $safeFileName

    Write-Host "目标设备：$selectedDevice"
    Write-Host "手机文件：$remotePath"
    Write-Host "本地目录：$outputDir"
    Write-Host '正在拉取 ZIP ...' -ForegroundColor Cyan

    $previousPreference = $ErrorActionPreference
    try {
        $ErrorActionPreference = 'Continue'
        $pullOutput = @(& adb -s $selectedDevice pull $remotePath $localZip 2>&1)
        $pullExitCode = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $previousPreference
    }
    $pulled = $pullExitCode -eq 0
    if ($pulled -and (Test-Path -LiteralPath $localZip -PathType Leaf)) {
        $pulled = (Get-Item -LiteralPath $localZip).Length -gt 0
    } else {
        $pulled = $false
    }

    if (-not $pulled) {
        if ([string]::IsNullOrWhiteSpace($runAsPackage)) {
            $detail = ($pullOutput -join ' ').Trim()
            throw "adb pull 失败，且该路径无法推导应用包名。$detail"
        }

        Write-Host "普通 adb pull 不可用，尝试通过 run-as $runAsPackage 读取应用私有文件 ..." -ForegroundColor Yellow
        # exec-out 的标准输出是原始二进制，不能通过 PowerShell 文本管道传输。
        & adb -s $selectedDevice exec-out run-as $runAsPackage cat $remotePath > $localZip
        $catExitCode = $LASTEXITCODE
        if ($catExitCode -ne 0) {
            throw "run-as 读取失败，退出码：$catExitCode。请确认应用为 debuggable 构建，且包名为 $runAsPackage。"
        }
    }

    if (-not (Test-Path -LiteralPath $localZip -PathType Leaf)) {
        throw "没有生成本地 ZIP：$localZip"
    }
    $localFile = Get-Item -LiteralPath $localZip
    if ($localFile.Length -le 0) {
        throw "拉取到的 ZIP 为空：$localZip"
    }

    $localHash = (Get-FileHash -Algorithm SHA256 -LiteralPath $localZip).Hash.ToUpperInvariant()
    $remoteHash = Get-RemoteSha256 -SelectedDevice $selectedDevice -Path $remotePath -Package $runAsPackage
    if (-not [string]::IsNullOrWhiteSpace($remoteHash) -and $remoteHash -ne $localHash) {
        throw "设备与本地 ZIP 的 SHA-256 不一致：设备 $remoteHash，本地 $localHash"
    }

    $processorJar = Get-ProcessorJar -ForceBuild:$BuildProcessor
    $reportPath = Join-Path $outputDir 'report.json'
    $callTreePath = Join-Path $outputDir 'report.call-tree.json'
    $htmlPath = Join-Path $outputDir 'report.html'
    $tracePath = Join-Path $outputDir 'report.pb'

    $javaArgs = @(
        '-jar', $processorJar.FullName,
        'analyze-stack',
        '--input', $localZip,
        '--output', $reportPath,
        '--call-tree-output', $callTreePath,
        '--html', $htmlPath,
        '--trace', $tracePath
    )
    if (-not [string]::IsNullOrWhiteSpace($Mapping)) {
        $mappingPath = (Resolve-Path -LiteralPath $Mapping -ErrorAction Stop).Path
        $javaArgs += @('--mapping', $mappingPath)
    }

    Write-Host "正在使用 $($processorJar.Name) 解析 ..." -ForegroundColor Cyan
    $previousPreference = $ErrorActionPreference
    try {
        $ErrorActionPreference = 'Continue'
        & java @javaArgs
        $javaExitCode = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $previousPreference
    }
    if ($javaExitCode -ne 0) {
        throw "Processor 解析进程失败，退出码：$javaExitCode"
    }

    $requiredOutputs = @($reportPath, $callTreePath, $htmlPath, $tracePath)
    foreach ($path in $requiredOutputs) {
        if (-not (Test-Path -LiteralPath $path -PathType Leaf)) {
            throw "Processor 未生成有效输出：$path"
        }
        if ((Get-Item -LiteralPath $path).Length -le 0) {
            throw "Processor 未生成有效输出：$path"
        }
    }

    # 解析 JSON 做一次轻量后验检查；Processor 的 CLI 不能只依赖退出码判断成功。
    $report = Get-Content -Raw -LiteralPath $reportPath | ConvertFrom-Json
    $callTree = Get-Content -Raw -LiteralPath $callTreePath | ConvertFrom-Json
    $segmentCount = 0
    foreach ($thread in @($report.threads)) {
        $segmentCount += @($thread.segments).Count
    }

    Write-Host ''
    Write-Host '解析完成。' -ForegroundColor Green
    Write-Host ("  本地 ZIP：{0} bytes" -f $localFile.Length)
    Write-Host ("  SHA-256：{0}" -f $localHash)
    Write-Host ("  报告类型：{0}，线程数：{1}，时间片段：{2}" -f $report.artifactType, @($report.threads).Count, $segmentCount)
    Write-Host ("  调用树类型：{0}，线程数：{1}" -f $callTree.artifactType, @($callTree.threads).Count)
    Write-Host "  输出目录：$outputDir"
    Write-Host '  输出文件：report.json、report.call-tree.json、report.html、report.pb'
} catch {
    Write-Error $_.Exception.Message
    exit 1
}
