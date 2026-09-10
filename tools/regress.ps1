param([string]$JavaHome = $env:JAVA_HOME, [string[]]$Cases = @('startup','ipc','cache','memory','scheduler','lifecycle','free-fault','error-cleanup','sleep','swap82'))
$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot -Parent
$java = if ($JavaHome) { Join-Path $JavaHome 'bin/java.exe' } else { 'java' }
$javac = if ($JavaHome) { Join-Path $JavaHome 'bin/javac.exe' } else { 'javac' }
$classes = Join-Path $root 'build/regression-classes'
$results = Join-Path $root ('build/regressions/' + [guid]::NewGuid().ToString())
New-Item -ItemType Directory -Force $classes,$results | Out-Null
$sources = @((Get-ChildItem (Join-Path $root 'src/*.java')).FullName) + @((Get-ChildItem (Join-Path $root 'tests/*.java')).FullName)
& $javac -encoding UTF-8 -d $classes @sources
if ($LASTEXITCODE -ne 0) { throw 'Regression compilation failed' }
$summary = foreach ($case in $Cases) {
    $dir=Join-Path $results $case
    New-Item -ItemType Directory $dir | Out-Null
    $p=Start-Process -FilePath $java -ArgumentList @('-cp',('"'+$classes+'"'),'RegressionTests',$case) -WorkingDirectory $dir -WindowStyle Hidden -PassThru -RedirectStandardOutput "$dir/stdout.log" -RedirectStandardError "$dir/stderr.log"
    $deadline=[DateTime]::UtcNow.AddSeconds(100)
    do { $ended=$p.WaitForExit(1000) } while (!$ended -and [DateTime]::UtcNow -lt $deadline)
    if (!$ended) { $p.Kill();$p.WaitForExit() }
    $status=if($ended -and $p.ExitCode -eq 0 -and (Select-String -LiteralPath "$dir/stdout.log" -Pattern 'REGRESSION PASS:' -Quiet)) {'PASS'} else {'FAIL'}
    Write-Host "$case`: $status"
    [pscustomobject]@{Case=$case;Status=$status;Directory=$dir}
}
$summary | Export-Csv "$results/summary.csv" -NoTypeInformation
Write-Host "Regression results: $results"
if(@($summary | Where-Object Status -ne 'PASS').Count){exit 1}
