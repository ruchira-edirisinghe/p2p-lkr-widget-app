.PHONY: project build signed verify install test icon clean

APPPATH = $(shell find $(HOME)/Library/Developer/Xcode/DerivedData -name P2PMonitor.app -path '*Debug*' -maxdepth 6 2>/dev/null | head -1)

project:
	xcodegen generate

## Fast compile check ONLY. Produces an ad-hoc, entitlement-free bundle that
## macOS will NOT accept: pkd rejects an unsandboxed widget extension, so the
## widget never appears in the gallery. Never install the output of this.
build: project
	xcodebuild -project P2PMonitor.xcodeproj -scheme P2PMonitor \
	  -configuration Debug CODE_SIGNING_ALLOWED=NO build

## Properly signed build. Required for anything you actually run.
signed: project
	xcodebuild -project P2PMonitor.xcodeproj -scheme P2PMonitor \
	  -configuration Debug CODE_SIGN_STYLE=Manual \
	  CODE_SIGN_IDENTITY="Developer ID Application" \
	  OTHER_CODE_SIGN_FLAGS="--timestamp=none" build

## Assert the built bundle is shippable: real signature, sandboxed widget,
## app group on both, network on the app but not the widget.
verify:
	./tools/verify-bundle.sh "$(APPPATH)"

## The only supported way to install. Signs, verifies the build, copies with
## ditto (the correct tool for signed bundles), then re-verifies the installed
## copy before launching. `build` output can never reach /Applications this way.
install: signed verify
	osascript -e 'quit app "P2PMonitor"' 2>/dev/null || true
	pkill -f 'P2PMonitor.app/Contents/MacOS/P2PMonitor' 2>/dev/null || true
	rm -rf /Applications/P2PMonitor.app
	ditto "$(APPPATH)" /Applications/P2PMonitor.app
	./tools/verify-bundle.sh /Applications/P2PMonitor.app
	open /Applications/P2PMonitor.app
	echo "installed and launched; widget appears under Edit Widgets > USDT/LKR Rate"

test:
	cd P2PKit && swift test

icon:
	swift tools/make-icon.swift

clean:
	rm -rf P2PMonitor.xcodeproj P2PKit/.build
