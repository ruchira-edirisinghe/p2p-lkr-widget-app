#!/usr/bin/env swift
//
// Renders the icon macOS ACTUALLY resolves for a bundle, which is not
// necessarily the artwork you shipped — macOS 26 composites legacy .icns files
// onto its own rounded container. Use this to see the real result.
//
//   swift tools/resolve-icon.swift /Applications/P2PMonitor.app /tmp/icon.png
//
import AppKit
let path = CommandLine.arguments[1]
let out = CommandLine.arguments[2]
let icon = NSWorkspace.shared.icon(forFile: path)
icon.size = NSSize(width: 256, height: 256)
guard let tiff = icon.tiffRepresentation,
      let rep = NSBitmapImageRep(data: tiff),
      let png = rep.representation(using: .png, properties: [:]) else {
    print("FAILED to get icon"); exit(1)
}
try! png.write(to: URL(fileURLWithPath: out))
print("wrote \(out) — \(png.count) bytes, reps: \(icon.representations.count)")
for r in icon.representations { print("  rep \(Int(r.size.width))x\(Int(r.size.height)) px \(r.pixelsWide)x\(r.pixelsHigh)") }
