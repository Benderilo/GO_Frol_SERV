<#
.SYNOPSIS
    Забирает резервные копии CRM «Фролов Системы» с сервера на этот компьютер.

.DESCRIPTION
    Копии на сервере лежат на том же диске, что и база: они спасают от
    ошибочного удаления, но не от потери самого сервера. Этот скрипт уносит
    их наружу — на локальную машину, тем же ключом SSH, что и развёртывание.

    Скачивается только то, чего ещё нет: файлы именованы по времени снимка
    и не меняются. Каждый новый файл проверяется на целостность распаковкой —
    копия, которую ни разу не открыли, копией не считается.

.EXAMPLE
    powershell -ExecutionPolicy Bypass -File pull-backups.ps1

.EXAMPLE
    # Сначала снять на сервере свежий снимок, потом забрать
    powershell -ExecutionPolicy Bypass -File pull-backups.ps1 -Fresh
#>
[CmdletBinding()]
param(
    [string] $Server    = 'root@91.184.246.64',
    [string] $RemoteDir = '/var/backups/frolov-crm',
    [string] $LocalDir  = "$env:USERPROFILE\FrolovCRM-backups",

    # Локально держим дольше, чем на сервере (там 14 дней): в этом и смысл
    # выгрузки наружу — иметь глубину, которой на сервере нет.
    [int]    $KeepDays  = 90,

    [string] $KeyFile   = '',

    # Попросить сервер снять свежий снимок перед забором.
    [switch] $Fresh
)

$ErrorActionPreference = 'Stop'

# ssh и scp могут быть как из состава Windows, так и из Git for Windows.
function Find-Tool {
    param([string] $Name)
    $cmd = Get-Command $Name -ErrorAction SilentlyContinue
    if ($cmd) { return $cmd.Source }
    $candidates = @(
        "C:\Windows\System32\OpenSSH\$Name.exe",
        "C:\Program Files\Git\usr\bin\$Name.exe",
        "C:\Program Files (x86)\Git\usr\bin\$Name.exe"
    )
    foreach ($p in $candidates) {
        if (Test-Path $p) { return $p }
    }
    throw "Не найден $Name.exe. Установите OpenSSH или Git for Windows."
}

# Полная распаковка в никуда: единственный способ убедиться, что архив цел.
# Возвращает размер распакованных данных.
function Test-GzipFile {
    param([string] $Path)
    $in = $null
    $gz = $null
    try {
        $in = [System.IO.File]::OpenRead($Path)
        $gz = New-Object System.IO.Compression.GZipStream(
            $in, [System.IO.Compression.CompressionMode]::Decompress)
        $buffer = New-Object byte[] 65536
        $total = [long]0
        while ($true) {
            $read = $gz.Read($buffer, 0, $buffer.Length)
            if ($read -le 0) { break }
            $total += $read
        }
        return $total
    } finally {
        if ($gz) { $gz.Dispose() }
        if ($in) { $in.Dispose() }
    }
}

function Write-Log {
    param([string] $Text)
    $stamp = Get-Date -Format 'yyyy-MM-dd HH:mm:ss'
    Write-Host $Text
    Add-Content -Path (Join-Path $LocalDir 'pull.log') -Value "$stamp  $Text" -Encoding UTF8
}

try {
    $ssh = Find-Tool 'ssh'
    $scp = Find-Tool 'scp'

    if (-not (Test-Path $LocalDir)) {
        New-Item -ItemType Directory -Path $LocalDir -Force | Out-Null
    }

    # BatchMode: без ключа скрипт должен падать, а не ждать ввода пароля
    # в планировщике, где никто его не введёт.
    $sshOpts = @('-o', 'BatchMode=yes', '-o', 'ConnectTimeout=20')
    if ($KeyFile) { $sshOpts += @('-i', $KeyFile) }

    if ($Fresh) {
        Write-Log 'Прошу сервер снять свежий снимок...'
        & $ssh @sshOpts $Server 'systemctl start frolov-crm-backup.service' | Out-Null
        if ($LASTEXITCODE -ne 0) { throw "Не удалось запустить копирование на сервере (код $LASTEXITCODE)" }
        Start-Sleep -Seconds 5
    }

    $listing = & $ssh @sshOpts $Server "ls -1 $RemoteDir"
    if ($LASTEXITCODE -ne 0) {
        throw "Не удалось получить список копий с $Server (код $LASTEXITCODE)"
    }

    $remote = @($listing | Where-Object { $_ -and $_.Trim().EndsWith('.gz') } | ForEach-Object { $_.Trim() })
    if ($remote.Count -eq 0) {
        throw "На сервере в $RemoteDir нет ни одной копии — проверьте таймер frolov-crm-backup"
    }

    $missing = @($remote | Where-Object { -not (Test-Path (Join-Path $LocalDir $_)) })
    Write-Log "На сервере копий: $($remote.Count), из них новых: $($missing.Count)"

    $downloaded = 0
    $failed = @()
    foreach ($name in $missing) {
        $target = Join-Path $LocalDir $name
        $temp   = "$target.part"
        # -p сохраняет время файла: по нему потом считается срок хранения.
        & $scp @sshOpts '-p' "${Server}:${RemoteDir}/${name}" $temp
        if ($LASTEXITCODE -ne 0) {
            Remove-Item $temp -Force -ErrorAction SilentlyContinue
            $failed += $name
            continue
        }
        try {
            $size = Test-GzipFile $temp
            # Переименовываем только проверенное: оборванная закачка не должна
            # выглядеть как готовая копия и пропускаться при следующем заборе.
            Move-Item $temp $target -Force
            $downloaded++
            Write-Log ("  {0} — {1:N0} КБ в распакованном виде" -f $name, ($size / 1KB))
        } catch {
            Remove-Item $temp -Force -ErrorAction SilentlyContinue
            $failed += $name
            Write-Log "  ПОВРЕЖДЁН при передаче: $name"
        }
    }

    # Чистим только свои файлы: каталог может быть общим с чем-то ещё.
    $cutoff = (Get-Date).AddDays(-$KeepDays)
    $stale = @(Get-ChildItem $LocalDir -Filter '*.gz' -File |
               Where-Object { $_.LastWriteTime -lt $cutoff })
    foreach ($f in $stale) { Remove-Item $f.FullName -Force }

    $all = @(Get-ChildItem $LocalDir -Filter '*.gz' -File)
    $mb = 0
    if ($all.Count -gt 0) { $mb = ($all | Measure-Object -Property Length -Sum).Sum / 1MB }

    # Свежесть проверяем по самой новой копии базы: молчащий таймер на сервере
    # иначе заметить нечем — скрипт отработает «успешно», ничего не скачав.
    $newest = $all | Where-Object { $_.Name -like 'frolov-*.db.gz' } |
              Sort-Object LastWriteTime -Descending | Select-Object -First 1
    $ageDays = 999
    if ($newest) { $ageDays = [int]((Get-Date) - $newest.LastWriteTime).TotalDays }

    Write-Log ("Итог: скачано {0}, удалено старых {1}, хранится {2} файлов ({3:N1} МБ) в {4}" -f `
        $downloaded, $stale.Count, $all.Count, $mb, $LocalDir)

    if ($failed.Count -gt 0) {
        Write-Log "ОШИБКА: не удалось забрать $($failed.Count) файлов: $($failed -join ', ')"
        exit 1
    }
    if ($ageDays -gt 2) {
        Write-Log "ВНИМАНИЕ: самой свежей копии базы $ageDays дн. — проверьте таймер frolov-crm-backup на сервере"
        exit 1
    }
    exit 0
} catch {
    $message = $_.Exception.Message
    if (Test-Path $LocalDir) {
        Add-Content -Path (Join-Path $LocalDir 'pull.log') `
            -Value ("{0}  ОШИБКА: {1}" -f (Get-Date -Format 'yyyy-MM-dd HH:mm:ss'), $message) -Encoding UTF8
    }
    Write-Host "ОШИБКА: $message"
    exit 1
}
