# Rebuild the 320 x 180 xhdpi Android TV launcher banner using the bundled Manrope font.
Add-Type -AssemblyName System.Drawing
$projectDirectory = Split-Path -Parent $PSScriptRoot
$bannerDirectory = Join-Path $projectDirectory 'app/src/main/res/drawable-xhdpi'
New-Item -ItemType Directory -Path $bannerDirectory -Force | Out-Null
$fonts = New-Object System.Drawing.Text.PrivateFontCollection
$fonts.AddFontFile((Join-Path $projectDirectory 'shared/resources/font/manrope_bold.ttf'))
$bitmap = New-Object System.Drawing.Bitmap(320, 180)
$graphics = [System.Drawing.Graphics]::FromImage($bitmap)
$graphics.Clear([System.Drawing.Color]::Black)
$graphics.TextRenderingHint = [System.Drawing.Text.TextRenderingHint]::AntiAliasGridFit
$font = New-Object System.Drawing.Font($fonts.Families[0], 43, [System.Drawing.FontStyle]::Regular, [System.Drawing.GraphicsUnit]::Pixel)
$format = New-Object System.Drawing.StringFormat
$format.Alignment = [System.Drawing.StringAlignment]::Center
$format.LineAlignment = [System.Drawing.StringAlignment]::Center
$graphics.DrawString('Troit /', $font, [System.Drawing.Brushes]::White, [System.Drawing.RectangleF]::new(0, 0, 320, 180), $format)
$bitmap.Save((Join-Path $bannerDirectory 'troit_tv_banner.png'), [System.Drawing.Imaging.ImageFormat]::Png)
$format.Dispose()
$font.Dispose()
$graphics.Dispose()
$bitmap.Dispose()
$fonts.Dispose()
