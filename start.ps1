# Spring AI RAG Demo —— 一键启动
# 用法：双击 start.cmd，或在 PowerShell 里执行 .\start.ps1
$ErrorActionPreference = 'Continue'
$ProjectDir = Split-Path -Parent $MyInvocation.MyCommand.Path
Set-Location $ProjectDir

function Info($m) { Write-Host "[*] $m" -ForegroundColor Cyan }
function Ok($m)   { Write-Host "[OK] $m" -ForegroundColor Green }
function Bad($m)  { Write-Host "[X] $m" -ForegroundColor Red }

Write-Host ''
Write-Host '  Spring AI RAG Demo' -ForegroundColor White
Write-Host '  ------------------' -ForegroundColor DarkGray
Write-Host ''

# ---------- 1. Docker 引擎 ----------
Info '检查 Docker 引擎…'
docker info --format '{{.ServerVersion}}' *> $null
$engineUp = ($LASTEXITCODE -eq 0)

if (-not $engineUp) {
    Info 'Docker 没启动，正在拉起 Docker Desktop（首次启动可能要 1-2 分钟）'
    $exe = 'C:\Program Files\Docker\Docker\Docker Desktop.exe'
    if (Test-Path $exe) {
        Start-Process $exe
    } else {
        Bad '找不到 Docker Desktop，请手动安装或启动'
        exit 1
    }
    for ($i = 0; $i -lt 60; $i++) {
        Start-Sleep -Seconds 3
        docker info --format '{{.ServerVersion}}' *> $null
        if ($LASTEXITCODE -eq 0) { $engineUp = $true; break }
        Write-Host '    等待 Docker 引擎…' -ForegroundColor DarkGray
    }
}
if (-not $engineUp) {
    Bad 'Docker 引擎没能启动，请手动打开 Docker Desktop 等它显示 Running 后再试'
    exit 1
}
Ok 'Docker 引擎就绪'

# ---------- 2. 数据库容器 ----------
Info '启动 PostgreSQL + pgvector 容器…'
docker compose up -d
if ($LASTEXITCODE -ne 0) {
    Bad '容器启动失败，请查看上面的报错'
    exit 1
}

$health = ''
for ($i = 0; $i -lt 40; $i++) {
    $health = (docker inspect --format '{{.State.Health.Status}}' ragdemo-pgvector 2>$null)
    if ($health -eq 'healthy') { break }
    Start-Sleep -Seconds 2
}
if ($health -eq 'healthy') { Ok '数据库健康（301 个知识分块已就绪）' }
else { Write-Host "[!] 数据库状态是 $health，仍尝试启动应用" -ForegroundColor Yellow }

# ---------- 3. 就绪后自动开浏览器 ----------
Start-Job -ScriptBlock {
    for ($i = 0; $i -lt 90; $i++) {
        if ((Test-NetConnection -ComputerName 127.0.0.1 -Port 8080 -WarningAction SilentlyContinue).TcpTestSucceeded) {
            Start-Process 'http://localhost:8080'
            break
        }
        Start-Sleep -Seconds 2
    }
} | Out-Null

# ---------- 4. 应用（前台运行） ----------
Info '启动应用…（约 20 秒；就绪后浏览器会自动打开）'
Write-Host '    停止服务：按 Ctrl+C，或直接关闭本窗口' -ForegroundColor DarkGray
Write-Host ''

java -jar 'target\spring-ai-rag-demo-3.0.1.jar' `
     --spring.profiles.active=aliyun `
     --ENV_FILE="$ProjectDir\.env"

Write-Host ''
Write-Host '应用已停止。数据库容器仍在后台运行，可用 stop.cmd 一并关闭。' -ForegroundColor DarkGray