//! For looking at the screen of a machine that cannot be sat in front of (a Linux laptop that is only reached over SSH): with
//! `TANDEM_DEBUG_SHOT=<folder>` set, the screen is taken every few seconds and the newest picture is written to `<folder>/screen.png`.
//! It goes through the same way as showing the screen to another device, so on a Wayland desktop the person is asked once.

use std::path::PathBuf;
use std::time::Duration;

use tandem_winsys::Grabber;

pub fn start() {
    let Some(folder) = std::env::var_os("TANDEM_DEBUG_SHOT").map(PathBuf::from) else { return };
    std::thread::Builder::new()
        .name("tandem-debug-shot".into())
        .spawn(move || {
            let _ = std::fs::create_dir_all(&folder);
            // Some time for the app to be up first.
            std::thread::sleep(Duration::from_secs(8));
            let Some(grabber) = Grabber::with_limit(1600, 1000) else {
                log::warn!("the debug screen picture could not be started");
                return;
            };
            loop {
                if let Some((width, height, bgra)) = grabber.grab() {
                    let mut rgb = Vec::with_capacity((width * height * 3) as usize);
                    for pixel in bgra.chunks_exact(4) {
                        rgb.extend_from_slice(&[pixel[2], pixel[1], pixel[0]]);
                    }
                    if let Some(image) = image::RgbImage::from_raw(width, height, rgb) {
                        let target = folder.join("screen.png");
                        let temp = folder.join("screen.tmp.png");
                        if image.save_with_format(&temp, image::ImageFormat::Png).is_ok() {
                            let _ = std::fs::rename(temp, target);
                        }
                    }
                }
                std::thread::sleep(Duration::from_secs(3));
            }
        })
        .ok();
}
