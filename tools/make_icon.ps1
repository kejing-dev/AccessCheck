# Access Check launcher icon: the 512 export for the factory showcase and the 192 legacy mipmap,
# drawn with System.Drawing from the same shapes as res/drawable/ic_launcher_foreground.xml
# (pine green #0E7C66 tile, white wheelchair silhouette, dashed turning circle with one red dash).
#
#   powershell -ExecutionPolicy Bypass -File tools\make_icon.ps1 <export512.png>
param([string]$Export = "icon.png")
Add-Type -AssemblyName System.Drawing

function Draw($size) {
    $bmp = New-Object System.Drawing.Bitmap $size, $size
    $g = [System.Drawing.Graphics]::FromImage($bmp)
    $g.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::AntiAlias
    $g.Clear([System.Drawing.Color]::FromArgb(255, 14, 124, 102))
    $k = $size / 108.0
    $white = [System.Drawing.Color]::White
    $red = [System.Drawing.Color]::FromArgb(255, 255, 95, 82)
    # dashed turning circle: 8 arcs of 30 degrees with 15 degree gaps, the lower-right one red
    $r = 32 * $k
    $rect = New-Object System.Drawing.RectangleF (54 * $k - $r), (54 * $k - $r), (2 * $r), (2 * $r)
    for ($i = 0; $i -lt 8; $i++) {
        $start = -90 + 45 * $i
        $isRed = ($i -eq 3)
        $pen = New-Object System.Drawing.Pen ($(if ($isRed) { $red } else { $white })), ($(if ($isRed) { 5 } else { 4 }) * $k)
        $pen.StartCap = [System.Drawing.Drawing2D.LineCap]::Round
        $pen.EndCap = [System.Drawing.Drawing2D.LineCap]::Round
        $g.DrawArc($pen, $rect, $start, 30)
        $pen.Dispose()
    }
    $p5 = New-Object System.Drawing.Pen $white, (5 * $k)
    $p5.StartCap = [System.Drawing.Drawing2D.LineCap]::Round
    $p5.EndCap = [System.Drawing.Drawing2D.LineCap]::Round
    $p5.LineJoin = [System.Drawing.Drawing2D.LineJoin]::Round
    # rear wheel + hub
    $g.DrawEllipse($p5, (47 - 11) * $k, (62 - 11) * $k, 22 * $k, 22 * $k)
    $brush = New-Object System.Drawing.SolidBrush $white
    $g.FillEllipse($brush, (47 - 3) * $k, (62 - 3) * $k, 6 * $k, 6 * $k)
    # backrest, seat, leg to footrest
    $pts = @(
        (New-Object System.Drawing.PointF (49 * $k), (32 * $k)),
        (New-Object System.Drawing.PointF (49 * $k), (51 * $k)),
        (New-Object System.Drawing.PointF (63 * $k), (51 * $k)),
        (New-Object System.Drawing.PointF (69 * $k), (64 * $k))
    )
    $g.DrawLines($p5, $pts)
    # push handle, footrest, front caster
    $g.DrawLine($p5, 49 * $k, 33 * $k, 56 * $k, 33 * $k)
    $g.DrawLine($p5, 66 * $k, 70 * $k, 74 * $k, 70 * $k)
    $g.FillEllipse($brush, (69 - 4) * $k, (69 - 4) * $k, 8 * $k, 8 * $k)
    $p5.Dispose(); $brush.Dispose(); $g.Dispose()
    return $bmp
}

$root = Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path)
$big = Draw 512
$big.Save($Export, [System.Drawing.Imaging.ImageFormat]::Png)
$small = Draw 192
$small.Save((Join-Path $root "app\src\main\res\mipmap-xxxhdpi\ic_launcher.png"), [System.Drawing.Imaging.ImageFormat]::Png)
$big.Dispose(); $small.Dispose()
Write-Output "icon written: $Export + mipmap-xxxhdpi/ic_launcher.png"
