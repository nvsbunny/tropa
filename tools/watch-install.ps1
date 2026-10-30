# Регистрирует еженедельную проверку новых версий (tools/watch.py) в Планировщике заданий Windows.
# Воскресенье, 14:00; если компьютер был выключен — при следующем включении. Без окна консоли.
# Удалить: Unregister-ScheduledTask -TaskName "Тропа — проверка обновлений" -Confirm:$false
$root = Split-Path -Parent $PSScriptRoot
$python = (Get-Command pythonw.exe -ErrorAction Stop).Source
$action = New-ScheduledTaskAction -Execute $python -Argument "-I `"$root\tools\watch.py`"" -WorkingDirectory $root
$trigger = New-ScheduledTaskTrigger -Weekly -DaysOfWeek Sunday -At 14:00
$settings = New-ScheduledTaskSettingsSet -StartWhenAvailable -ExecutionTimeLimit (New-TimeSpan -Minutes 30) -DontStopIfGoingOnBatteries -AllowStartIfOnBatteries
Register-ScheduledTask -TaskName "Тропа — проверка обновлений" -Action $action -Trigger $trigger -Settings $settings `
    -Description "Раз в неделю: новые версии Xray, mihomo, Happ и INCY. Сводка — ОБНОВЛЕНИЯ.md в папке Тропы." -Force | Out-Null
Get-ScheduledTask -TaskName "Тропа — проверка обновлений" | Select-Object TaskName, State
