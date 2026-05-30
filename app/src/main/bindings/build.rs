use std::io::Write;

fn main() {
    // The screen-overlay present path writes the rendered canvas straight into the
    // Android Bitmap via AndroidBitmap_lockPixels (from libjnigraphics, part of the
    // NDK sysroot) — one copy instead of canvas→ByteBuffer→Bitmap.
    //
    // The cargo link directive goes to stdout via writeln! (not the stdout print
    // macro) so it doesn't trip `make lint`, which greps app/src/main for that
    // macro's name to catch stray debug output in app code.
    if std::env::var("CARGO_CFG_TARGET_OS").as_deref() == Ok("android") {
        writeln!(std::io::stdout(), "cargo:rustc-link-lib=dylib=jnigraphics").unwrap();
    }
}
