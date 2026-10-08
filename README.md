# libtray-java

Java 25 port of [libtray](https://github.com/Kitty-Hivens/libtray) by
Kitty-Hivens. Maven, Project Panama, no Kotlin and no SLF4J.
No external Java runtime dependencies; Linux uses the OS's `libdbus-1`.

## Platforms

- Linux: StatusNotifierItem and DBusMenu over D-Bus, including Wayland tray hosts.
- Windows: Win32 notification area, popup menus and Explorer restart recovery.
- macOS: AppKit status item and menus, with mutations on the Cocoa main queue.
- Left click emits `Activated`; right click opens the menu and emits
  `MenuRequested`. Menu selections emit `MenuItemSelected`.

Linux still needs a StatusNotifierWatcher/tray host. Wayland alone does not
provide a tray; GNOME needs an appropriate extension. Windows and macOS need
a desktop session. If a backend cannot initialize, `Tray.create` returns null.

## Maven

GitHub Packages repository:

```xml
<repositories>
    <repository>
        <id>github-libtray-java</id>
        <url>https://maven.pkg.github.com/red171/libtray-java</url>
    </repository>
</repositories>

<dependencies>
    <dependency>
        <groupId>io.github.red171</groupId>
        <artifactId>libtray-java</artifactId>
        <version>1.2.0</version>
    </dependency>
</dependencies>
```

GitHub's Maven registry requires authentication even for public packages.
Put credentials in `~/.m2/settings.xml`, not in the project:

```xml
<settings>
    <servers>
        <server>
            <id>github-libtray-java</id>
            <username>YOUR_GITHUB_USERNAME</username>
            <password>${env.GITHUB_PACKAGES_TOKEN}</password>
        </server>
    </servers>
</settings>
```

Use a GitHub personal access token (classic) with `read:packages`. For GitHub
Actions consumers, a `GITHUB_TOKEN` with package read access can be used instead.

Launch with `--enable-native-access=ALL-UNNAMED` when using the classpath.
One JAR serves amd64 and aarch64; native system calls use the current JVM ABI.

## Java API

```java
import io.github.red171.libtray.*;
import java.util.List;

var menu = new TrayMenu(List.of(
    new TrayMenuItem.Standard("show", "Show"),
    TrayMenuItem.Separator.INSTANCE,
    new TrayMenuItem.Standard("quit", "Quit")
));
var config = new TrayBuilder(
    "appleJuice Core", pngBytes, "appleJuice Core", menu, 256,
    "io.github.applejuicenetz.core.StatusNotifierItem"
);
Tray tray = Tray.create(config);
if (tray != null) {
    Runnable unsubscribe = tray.onEvent(event -> {
        if (event instanceof TrayEvent.Activated) {
            toggleWindow();
        } else if (event instanceof TrayEvent.MenuItemSelected selected) {
            handleMenuItem(selected.id());
        }
    });
    tray.setTooltip("Ready");
}
```

On application shutdown, call `unsubscribe.run()` and `tray.close()`.

Callbacks run on the native backend's dispatch thread. Dispatch UI changes to
Swing's EDT or the JavaFX application thread yourself. A throwing event handler
does not stop other handlers. Updates return false after close; close is
idempotent. A successful setter accepts the update; it does not guarantee that
the desktop has already rendered it. Windows updates use its message pump;
macOS worker-thread updates use the Cocoa main queue. This library does not
own or replace the application's event loop.

On macOS, creation runs on the Cocoa main thread. Swing callers initialize AWT
and dispatch creation to that thread; a standalone native application can use
`-XstartOnFirstThread` and create the tray from main. The application must keep
its normal AppKit/AWT/JavaFX event loop running for clicks and queued updates.

### Flatpak

Set `linuxBusName` within your application's D-Bus namespace and allow access
to the watcher:

```yaml
finish-args:
  - --talk-name=org.kde.StatusNotifierWatcher
```

No `--own-name=org.kde.*` permission is needed with the configured bus name.
Names must be unique for multiple instances/tray icons. Without configuration,
the original generated `org.kde.StatusNotifierItem-PID-N` naming is preserved.
The configured bus name, D-Bus title property, Activate callback and clean close
have also been smoke-tested inside the existing Core Flatpak sandbox, without
replacing the installed application. Visual panel rendering remains untested.

## Build and tests

Requires JDK 25 and Maven:

```sh
mvn verify
dbus-run-session -- mvn -Pnative-tests verify
```

Linux native tests use a real isolated session bus and a test tray watcher.
They exercise registration/re-registration, properties, icon byte order, menus,
callbacks, malformed requests and cleanup. They do not inspect a real desktop
panel. Windows native tests exercise native windows, icons and FFM callbacks.
The shell lifecycle test first probes a stock Windows icon and skips only when
the runner shell cannot accept it. The Windows ARM runner currently rejects even
an independent C# P/Invoke stock-icon probe; its shell lifecycle remains unverified.
macOS smoke tests exercise AppKit creation, updates, menu callbacks and cleanup.
Visual appearance and physical mouse interaction still need desktop testing.

GitHub Actions tests Linux, Windows and macOS on amd64 and aarch64. Only after
all jobs pass, pushing a `v*` tag publishes that release version to GitHub
Packages. Branch pushes and pull requests never publish. No GitHub Release is created.

Existing Core, Collector and JavaGUI are not switched over automatically.

## Changes in 1.2.0

- Events are delivered on one thread per tray, in firing order, instead of on the
  backend thread (D-Bus I/O thread, Win32 message pump, Cocoa main thread). A
  blocking handler can no longer stall replies to the tray host or `close()`.
  Handlers that touch AppKit directly must register with an executor.
- `Tray.onEvent(Executor, Consumer)` submits each event to an executor, e.g.
  `SwingUtilities::invokeLater` or `Platform::runLater`. A class implementing
  `Tray` itself must implement both `onEvent` overloads.
- Linux: no `dbus_connection_flush` on the I/O thread, so `close()` always joins
  even when the bus daemon stopped reading.
- Windows: a right click opens the menu and fires `MenuRequested` once, not twice.
- macOS: `close()` waits for the teardown on the Cocoa main queue, cancels an open
  menu and defers the last releases until menu tracking has unwound.
- Oversized icons are scaled by area averaging on the pixel array, without the AWT
  Toolkit or Java2D.

Apache License 2.0; see `LICENSE` and `NOTICE`, also included in the JAR.
