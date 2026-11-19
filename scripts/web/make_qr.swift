// Makes the Play Store QR code for the website as a crisp SVG, using macOS Core Image (no packages).
// Usage: swift scripts/web/make_qr.swift <url> <out.svg>
// It decodes the image it generated and fails unless the result matches <url>.
import CoreImage
import Foundation

let args = CommandLine.arguments
guard args.count == 3 else { fputs("usage: make_qr.swift <url> <out.svg>\n", stderr); exit(2) }
let url = args[1], outPath = args[2]

let filter = CIFilter(name: "CIQRCodeGenerator")!
filter.setValue(url.data(using: .utf8), forKey: "inputMessage")
filter.setValue("M", forKey: "inputCorrectionLevel")
let image = filter.outputImage!                       // 1 pixel per module, includes a 1-module margin
let w = Int(image.extent.width), h = Int(image.extent.height)

let ctx = CIContext()
var px = [UInt8](repeating: 0, count: w * h * 4)
ctx.render(image, toBitmap: &px, rowBytes: w * 4, bounds: image.extent,
           format: .RGBA8, colorSpace: CGColorSpaceCreateDeviceRGB())

// Drop the generator's own margin; the page adds its own white padding.
let m = 1, n = w - 2 * m
var rects = ""
for y in 0..<n {
  var x = 0
  while x < n {
    let dark = { (xx: Int) in px[((y + m) * w + (xx + m)) * 4] < 128 }
    if dark(x) {
      var run = 1
      while x + run < n && dark(x + run) { run += 1 }
      rects += "<rect x=\"\(x)\" y=\"\(y)\" width=\"\(run)\" height=\"1\"/>"
      x += run
    } else { x += 1 }
  }
}
let svg = """
<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 \(n) \(n)" shape-rendering="crispEdges" role="img" aria-label="QR code for Retrieve on Google Play"><rect width="\(n)" height="\(n)" fill="#fff"/><g fill="#0F1115">\(rects)</g></svg>

"""
try! svg.write(toFile: outPath, atomically: true, encoding: .utf8)

// Verify: scale up with a quiet zone and decode it back.
let big = image.transformed(by: CGAffineTransform(scaleX: 12, y: 12))
let detector = CIDetector(ofType: CIDetectorTypeQRCode, context: ctx, options: [CIDetectorAccuracy: CIDetectorAccuracyHigh])!
let decoded = (detector.features(in: big).first as? CIQRCodeFeature)?.messageString
guard decoded == url else { fputs("QR verify FAILED: decoded \(decoded ?? "nothing")\n", stderr); exit(1) }
print("wrote \(outPath): \(n)x\(n) modules, decodes to \(decoded!)")
