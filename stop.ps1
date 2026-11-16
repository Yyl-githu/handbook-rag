# 停止服务：关闭应用窗口后运行本脚本，可一并停掉数据库容器
$ProjectDir = Split-Path -Parent $MyInvocation.MyCommand.Path
Set-Location $ProjectDir
Write-Host '[*] 停止数据库容器…' -ForegroundColor Cyan
docker compose stop
Write-Host '[OK] 已停止（数据保留在 Docker 卷里，下次启动自动恢复）' -ForegroundColor Green
Start-Sleep -Seconds 2