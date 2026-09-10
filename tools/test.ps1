param(
    [string]$JavaHome = $env:JAVA_HOME,
    [ValidateRange(1, 300)][int]$Seconds = 60,
    [string]$Label = 'suite',
    [switch]$RequireThreadCleanup = $true
)
$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot -Parent
$java = if ($JavaHome) { Join-Path $JavaHome 'bin/java.exe' } else { 'java' }
$javac = if ($JavaHome) { Join-Path $JavaHome 'bin/javac.exe' } else { 'javac' }
$classes = Join-Path $root 'build/classes'
$results = Join-Path $root ('build/test-results/' + $Label + '-' + [guid]::NewGuid().ToString())
New-Item -ItemType Directory -Force $classes, $results | Out-Null
$sources = @((Get-ChildItem (Join-Path $root 'src/*.java')).FullName)
& $javac -encoding UTF-8 -d $classes @sources (Join-Path $PSScriptRoot 'RunScenario.java') (Join-Path $PSScriptRoot 'CheckScenario.java')
if ($LASTEXITCODE -ne 0) { throw "Compilation failed: $LASTEXITCODE" }

# These are the original console scenarios, checked without modifying their code.
# Each JVM gets its own working directory; no existing data files are reused.
$scenarios = @(
    @{ Name='Main'; Args=@() },
    @{ Name='Init'; Args=@() },
    @{ Name='InitPagingTests'; Args=@() },
    @{ Name='InitPingPong'; Args=@() },
    @{ Name='InitSwapTests'; Args=@() },
    @{ Name='PIDTest'; Args=@() },
    @{ Name='SleepTest'; Args=@() },
    @{ Name='ExitTest'; Args=@() },
    @{ Name='HelloWorld'; Args=@() },
    @{ Name='GoodbyeWorld'; Args=@() },
    @{ Name='MemoryReadWriteTest'; Args=@() },
    @{ Name='MemoryExtendTest'; Args=@() },
    @{ Name='MemoryFaultTest'; Args=@() },
    @{ Name='MemSmoke'; Args=@() },
    @{ Name='Piggy'; Args=@('1') },
    @{ Name='TestRandomOnce'; Args=@('random') },
    @{ Name='TestRandomOnceSeeded'; Class='TestRandomOnce'; Args=@('random 42') },
    @{ Name='TestFileWriteRead'; Args=@('file simple.dat') },
    @{ Name='TestFileSeed'; Args=@('file shared.dat') },
    @{ Name='TestFileReader'; Args=@('file shared.dat', 'R1') },
    @{ Name='TestOpenCapacity'; Args=@() }
)
$summary = foreach ($scenario in $scenarios) {
    $dir = Join-Path $results $scenario.Name
    New-Item -ItemType Directory -Force $dir | Out-Null
    # A unique run directory keeps reruns independent of earlier generated data.
    $run = Join-Path $dir ([guid]::NewGuid().ToString())
    New-Item -ItemType Directory $run | Out-Null
    $stdout = Join-Path $run 'stdout.log'
    $stderr = Join-Path $run 'stderr.log'
    $class = if ($scenario.Class) { $scenario.Class } else { $scenario.Name }
    $arguments = @('-cp', ('"' + $classes + '"'), ('-Dverify.threadCleanup=' + $RequireThreadCleanup.IsPresent.ToString().ToLower()), 'CheckScenario', $class) + @($scenario.Args | ForEach-Object { '"' + $_ + '"' })
    $process = Start-Process -FilePath $java -ArgumentList $arguments -WorkingDirectory $run -WindowStyle Hidden -PassThru -RedirectStandardOutput $stdout -RedirectStandardError $stderr
    $ended = $process.WaitForExit($Seconds * 1000)
    if (!$ended) { $process.Kill(); $process.WaitForExit() }
    $status = if (!$ended) { "TIMEOUT ($Seconds seconds)" } elseif ($process.ExitCode -eq 0 -and (Select-String -LiteralPath $stdout -Pattern 'SCENARIO PASS:' -Quiet)) { 'PASS' } else { 'FAIL' }
    Write-Host "$($scenario.Name): $status"
    [pscustomobject]@{ Scenario=$scenario.Name; Status=$status; Stdout=$stdout; Stderr=$stderr }
}
$summary | Export-Csv (Join-Path $results 'summary.csv') -NoTypeInformation
Write-Host "Results saved under $results"
$failed = @($summary | Where-Object Status -ne 'PASS')
Write-Host "$($summary.Count - $failed.Count)/$($summary.Count) scenarios passed"
if ($failed.Count) { exit 1 }
