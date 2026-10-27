# thumb.ps1 IN OUT [W H] [CX CY CW CH]  -- downscale (and optionally crop first) a PNG with System.Drawing
param([string]$In, [string]$Out, [int]$W = 800, [int]$H = 800, [int]$CX = -1, [int]$CY = 0, [int]$CW = 0, [int]$CH = 0)
Add-Type -AssemblyName System.Drawing
$b = [System.Drawing.Bitmap]::FromFile($In)
if ($CX -ge 0) { $r = New-Object System.Drawing.Rectangle $CX, $CY, $CW, $CH; $b2 = $b.Clone($r, $b.PixelFormat); $b.Dispose(); $b = $b2 }
$c = New-Object System.Drawing.Bitmap $W, $H
$g = [System.Drawing.Graphics]::FromImage($c)
$g.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
$g.DrawImage($b, 0, 0, $W, $H)
$c.Save($Out, [System.Drawing.Imaging.ImageFormat]::Png)
$b.Dispose(); $c.Dispose()
Write-Output "thumb $Out"
