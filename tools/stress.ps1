param(
    [string]$JavaHome = $env:JAVA_HOME,
    [ValidateRange(1,100)][int]$Repetitions = 5,
    [ValidateRange(1,8)][int]$Concurrency = 4
)
$ErrorActionPreference = 'Stop'
$root=Split-Path $PSScriptRoot -Parent
$java=if($JavaHome){Join-Path $JavaHome 'bin/java.exe'}else{'java'}
$javac=if($JavaHome){Join-Path $JavaHome 'bin/javac.exe'}else{'javac'}
$classes=Join-Path $root 'build/stress-classes'
$results=Join-Path $root ('build/stress/'+[guid]::NewGuid().ToString())
New-Item -ItemType Directory -Force $classes,$results | Out-Null
$sources=@((Get-ChildItem (Join-Path $root 'src/*.java')).FullName)+@((Get-ChildItem (Join-Path $root 'tools/*.java')).FullName)+@((Get-ChildItem (Join-Path $root 'tests/*.java')).FullName)
& $javac -encoding UTF-8 -d $classes @sources
if($LASTEXITCODE -ne 0){throw 'Stress compilation failed'}
$pending=[System.Collections.Generic.Queue[object]]::new()
for($round=1;$round -le $Repetitions;$round++){
    foreach($case in @('Main','InitSwapTests','InitPagingTests','InitPingPong','Piggy','startup')){
        $pending.Enqueue(@{Name="$case-$round";Case=$case})
    }
}
$active=[System.Collections.Generic.List[object]]::new()
$summary=[System.Collections.Generic.List[object]]::new()
while($pending.Count -or $active.Count){
    while($pending.Count -and $active.Count -lt $Concurrency){
        $job=$pending.Dequeue();$dir=Join-Path $results $job.Name
        New-Item -ItemType Directory $dir | Out-Null
        $launcher=if($job.Case -eq 'startup'){'RegressionTests'}else{'CheckScenario'}
        $arguments=@('-cp',('"'+$classes+'"'),'-Dverify.threadCleanup=true',$launcher,$job.Case)
        if($job.Case -eq 'Piggy'){$arguments+='1'}
        $p=Start-Process -FilePath $java -ArgumentList $arguments -WorkingDirectory $dir -WindowStyle Hidden -PassThru -RedirectStandardOutput "$dir/stdout.log" -RedirectStandardError "$dir/stderr.log"
        $active.Add(@{Name=$job.Name;Case=$job.Case;Process=$p;Directory=$dir;Deadline=[DateTime]::UtcNow.AddSeconds(120)})
    }
    foreach($job in @($active.ToArray())){
        $p=$job.Process;$timedOut=[DateTime]::UtcNow -gt $job.Deadline
        if(!$p.HasExited -and !$timedOut){continue}
        if(!$p.HasExited){$p.Kill()};$p.WaitForExit()
        $marker=if($job.Case -eq 'startup'){'REGRESSION PASS:'}else{'SCENARIO PASS:'}
        $status=if($timedOut){'TIMEOUT'}elseif($p.ExitCode -eq 0 -and (Select-String -LiteralPath "$($job.Directory)/stdout.log" -Pattern $marker -Quiet)){'PASS'}else{'FAIL'}
        Write-Host "$($job.Name): $status"
        $summary.Add([pscustomobject]@{Run=$job.Name;Status=$status;Directory=$job.Directory})
        $active.Remove($job) | Out-Null
    }
    if($active.Count){Start-Sleep -Milliseconds 100}
}
$summary | Export-Csv "$results/summary.csv" -NoTypeInformation
Write-Host "Stress results: $results"
Write-Host "$(@($summary | Where-Object Status -eq 'PASS').Count)/$($summary.Count) passed"
if(@($summary | Where-Object Status -ne 'PASS').Count){exit 1}
