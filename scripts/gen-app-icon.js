// Render resources/nido-icon.png as a macOS app icon: the artwork clipped to the
// standard rounded square (824pt body on a 1024 canvas, 100pt transparent margin).
// macOS 26 shrinks any icon that is not this shape into a grey tile, which is
// what makes a full-bleed square read as a small badge on a notification.
//
// AppKit through JXA, because nothing else that draws ships with macOS. Regenerate:
//   osascript -l JavaScript scripts/gen-app-icon.js "$PWD/resources/nido-icon.png" /tmp/icon.png
//   sips -z 512 512 /tmp/icon.png --out resources/nido-app-icon.png
ObjC.import('AppKit');
function run(argv) {
  const [src, out] = argv;
  const img = $.NSImage.alloc.initWithContentsOfFile(src);
  if (img.isNil()) throw new Error('cannot read ' + src);
  const N = 1024, inset = 100, side = N - 2 * inset, radius = side * 0.225;
  const rep = $.NSBitmapImageRep.alloc.initWithBitmapDataPlanesPixelsWidePixelsHighBitsPerSampleSamplesPerPixelHasAlphaIsPlanarColorSpaceNameBytesPerRowBitsPerPixel(
    null, N, N, 8, 4, true, false, $.NSDeviceRGBColorSpace, 0, 0);
  rep.size = $.NSMakeSize(N, N);
  $.NSGraphicsContext.saveGraphicsState;
  $.NSGraphicsContext.setCurrentContext($.NSGraphicsContext.graphicsContextWithBitmapImageRep(rep));
  const r = $.NSMakeRect(inset, inset, side, side);
  $.NSBezierPath.bezierPathWithRoundedRectXRadiusYRadius(r, radius, radius).addClip;
  img.drawInRectFromRectOperationFraction(r, $.NSZeroRect, $.NSCompositingOperationSourceOver, 1.0);
  $.NSGraphicsContext.restoreGraphicsState;
  rep.representationUsingTypeProperties($.NSBitmapImageFileTypePNG, $()).writeToFileAtomically(out, true);
}
