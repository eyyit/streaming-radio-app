Add-Type -AssemblyName System.Drawing
$demoDirectory=$PSScriptRoot
$colors=@('#237BBA','#885BCA','#209C96','#C66942','#B85880','#6579C4')
$initials=@('AF','BN','CR','ND','SS','SA')
for($index=0;$index -lt 7;$index++) {
 $size=768
 $bitmap=[Drawing.Bitmap]::new($size,$size)
 $graphics=[Drawing.Graphics]::FromImage($bitmap)
 $graphics.SmoothingMode=[Drawing.Drawing2D.SmoothingMode]::AntiAlias
 $graphics.TextRenderingHint=[Drawing.Text.TextRenderingHint]::AntiAliasGridFit
 $rect=[Drawing.Rectangle]::new(0,0,$size,$size)
 $color=[Drawing.ColorTranslator]::FromHtml($colors[$index%6])
 $gradient=[Drawing.Drawing2D.LinearGradientBrush]::new($rect,[Drawing.Color]::FromArgb(255,15,24,43),$color,45)
 $graphics.FillRectangle($gradient,$rect)
 $pen=[Drawing.Pen]::new([Drawing.Color]::FromArgb(75,255,255,255),3)
 for($ring=0;$ring -lt 7;$ring++) {$inset=30+$ring*35;$graphics.DrawEllipse($pen,$inset,$inset,$size-2*$inset,$size-2*$inset)}
 $white=[Drawing.SolidBrush]::new([Drawing.Color]::FromArgb(245,255,255,255))
 $format=[Drawing.StringFormat]::new();$format.Alignment=[Drawing.StringAlignment]::Center;$format.LineAlignment=[Drawing.StringAlignment]::Center
 if($index -lt 6) {
  $font=[Drawing.Font]::new('Segoe UI',160,[Drawing.FontStyle]::Bold)
  $graphics.DrawString($initials[$index],$font,$white,[Drawing.RectangleF]::new(0,0,768,768),$format)
  $path=Join-Path $demoDirectory ('station-'+($index+1)+'.png')
 } else {
  $solid=[Drawing.SolidBrush]::new([Drawing.Color]::FromArgb(240,255,175,92))
  $graphics.FillEllipse($solid,230,155,308,308)
  $font=[Drawing.Font]::new('Segoe UI',36,[Drawing.FontStyle]::Bold)
  $graphics.DrawString('PAPER SATELLITES',$font,$white,[Drawing.RectangleF]::new(20,535,728,70),$format)
  $font.Dispose();$font=[Drawing.Font]::new('Segoe UI',25)
  $graphics.DrawString('MIDNIGHT COLORS',$font,$white,[Drawing.RectangleF]::new(20,605,728,55),$format)
  $solid.Dispose();$path=Join-Path $demoDirectory 'album.png'
 }
 $bitmap.Save($path,[Drawing.Imaging.ImageFormat]::Png)
 $font.Dispose();$format.Dispose();$white.Dispose();$pen.Dispose();$gradient.Dispose();$graphics.Dispose();$bitmap.Dispose()
}
