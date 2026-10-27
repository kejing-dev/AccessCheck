# sheet.ps1 OUT CELL COLS IN1 IN2 ...  -- contact sheet of PNGs (each scaled to CELL x CELL, labelled with its file name)
param([string]$Out, [int]$Cell = 480, [int]$Cols = 4, [Parameter(ValueFromRemainingArguments = $true)][string[]]$Ins)
Add-Type -AssemblyName System.Drawing
$n = $Ins.Count; $rows = [math]::Ceiling($n / $Cols)
$sheet = New-Object System.Drawing.Bitmap ($Cell * $Cols), ($Cell * $rows)
$g = [System.Drawing.Graphics]::FromImage($sheet)
$g.Clear([System.Drawing.Color]::Black)
$g.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
$font = New-Object System.Drawing.Font([string]"Arial", [single]22, [System.Drawing.FontStyle]::Bold)
$brush = New-Object System.Drawing.SolidBrush ([System.Drawing.Color]::Yellow)
for ($i = 0; $i -lt $n; $i++) {
    $x = ($i % $Cols) * $Cell; $y = [math]::Floor($i / $Cols) * $Cell
    if (Test-Path $Ins[$i]) { $b = [System.Drawing.Bitmap]::FromFile($Ins[$i]); $g.DrawImage($b, $x, $y, $Cell, $Cell); $b.Dispose() }
    $g.DrawString([System.IO.Path]::GetFileNameWithoutExtension($Ins[$i]), $font, $brush, $x + 8, $y + 6)
}
$sheet.Save($Out, [System.Drawing.Imaging.ImageFormat]::Png); $sheet.Dispose()
Write-Output "sheet $Out ($n images)"
