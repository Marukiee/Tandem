//! Linux: files that another computer dropped here with its pointer land in Downloads, and a card with them stays under the pointer for a
//! moment, to be picked up and dragged on into a folder, a mail or a field of a website. GNOME breaks off a drag that an app starts by
//! itself, but not one the person starts with a press of their own, which is what the card is for. Doing nothing leaves the files in
//! Downloads, where the notice says they are.
//!
//! The card is drawn here, as pixels: a tile with a picture of the file (the picture itself for a photo), stacked when there are more,
//! with their number on a badge. The window and the drag are in `tandem_winsys::dragcard`.

use std::path::{Path, PathBuf};

/// The size of the picture, with room around the card for its shadow.
pub const SIZE: u32 = 112;
/// Where the tile sits in the picture, and how big it is.
const TILE: f32 = 76.0;
const TILE_AT: f32 = 16.0;
const RADIUS: f32 = 18.0;

/// What a card looks like: its pixels, as the X server takes them (blue, green, red, alpha, with the colours already multiplied by
/// the alpha), `SIZE` by `SIZE`.
pub fn paint(files: &[PathBuf], dark: bool) -> Vec<u8> {
    let mut canvas = Canvas::new(SIZE);
    let count = files.len().max(1);
    // In the light the tile is a little grey, so the white page on it stands out.
    let (card, edge) = if dark { ([44, 44, 48], [255, 255, 255, 30]) } else { ([240, 240, 244], [0, 0, 0, 22]) };
    // More files: one or two tiles behind, turned a little, as a pile.
    let behind = (count - 1).min(2);
    for i in (1..=behind).rev() {
        let shift = i as f32 * 4.0;
        canvas.shadow(TILE_AT + shift, TILE_AT - shift, TILE, RADIUS, 0.16);
        let shade = if dark { [58, 58, 64] } else { [226, 226, 232] };
        canvas.rounded(TILE_AT + shift, TILE_AT - shift, TILE, TILE, RADIUS, [shade[0], shade[1], shade[2], 255]);
    }
    canvas.shadow(TILE_AT, TILE_AT, TILE, RADIUS, 0.3);
    canvas.rounded(TILE_AT, TILE_AT, TILE, TILE, RADIUS, [card[0], card[1], card[2], 255]);
    canvas.ring(TILE_AT, TILE_AT, TILE, RADIUS, edge);

    let inner = 56.0;
    let at = TILE_AT + (TILE - inner) / 2.0;
    match files.first().and_then(|f| thumbnail(f, inner as u32)) {
        Some(picture) => canvas.picture(&picture, at, at, inner, 10.0),
        None => canvas.page(at + 8.0, at, inner - 16.0, inner, kind_colour(files.first().map(PathBuf::as_path)), dark),
    }
    if count > 1 {
        canvas.badge(TILE_AT + TILE - 6.0, TILE_AT + 6.0, count);
    }
    canvas.into_bgra()
}

/// The colour of the page of a file without a picture, by what kind of file it is.
fn kind_colour(file: Option<&Path>) -> [u8; 3] {
    let extension = file.and_then(|f| f.extension()).map(|e| e.to_string_lossy().to_lowercase()).unwrap_or_default();
    match extension.as_str() {
        "pdf" => [229, 57, 53],
        "mp4" | "mov" | "mkv" | "webm" | "avi" => [142, 68, 173],
        "mp3" | "m4a" | "wav" | "flac" | "ogg" | "opus" => [233, 30, 99],
        "zip" | "tar" | "gz" | "xz" | "7z" | "rar" => [121, 85, 72],
        "doc" | "docx" | "odt" | "txt" | "md" | "rtf" | "pages" => [30, 136, 229],
        "xls" | "xlsx" | "ods" | "csv" | "numbers" => [67, 160, 71],
        "ppt" | "pptx" | "odp" | "key" => [251, 140, 0],
        _ => [120, 128, 140],
    }
}

/// A photo, cut to a square of `size`. Only what the decoder knows, and not too big to read at once.
fn thumbnail(file: &Path, size: u32) -> Option<image::RgbaImage> {
    let extension = file.extension()?.to_string_lossy().to_lowercase();
    if !matches!(extension.as_str(), "png" | "jpg" | "jpeg") || std::fs::metadata(file).ok()?.len() > 40 << 20 {
        return None;
    }
    let picture = image::open(file).ok()?;
    let side = picture.width().min(picture.height());
    let square = picture.crop_imm((picture.width() - side) / 2, (picture.height() - side) / 2, side, side);
    Some(square.resize_exact(size, size, image::imageops::FilterType::Triangle).to_rgba8())
}

/// Straight colours, drawn one over the other, turned into what the X server takes at the end.
struct Canvas {
    size: u32,
    /// Red, green, blue and alpha of each pixel, from 0 to 1, colours multiplied by alpha.
    pixels: Vec<[f32; 4]>,
}

impl Canvas {
    fn new(size: u32) -> Canvas {
        Canvas { size, pixels: vec![[0.0; 4]; (size * size) as usize] }
    }

    fn over(&mut self, x: u32, y: u32, colour: [f32; 3], alpha: f32) {
        if x >= self.size || y >= self.size || alpha <= 0.0 {
            return;
        }
        let p = &mut self.pixels[(y * self.size + x) as usize];
        let a = alpha.min(1.0);
        for c in 0..3 {
            p[c] = colour[c] * a + p[c] * (1.0 - a);
        }
        p[3] = a + p[3] * (1.0 - a);
    }

    fn fill(&mut self, coverage: impl Fn(f32, f32) -> f32, colour: [u8; 4]) {
        let rgb = [colour[0] as f32 / 255.0, colour[1] as f32 / 255.0, colour[2] as f32 / 255.0];
        let alpha = colour[3] as f32 / 255.0;
        for y in 0..self.size {
            for x in 0..self.size {
                let c = coverage(x as f32 + 0.5, y as f32 + 0.5);
                if c > 0.0 {
                    self.over(x, y, rgb, c * alpha);
                }
            }
        }
    }

    fn rounded(&mut self, x: f32, y: f32, w: f32, h: f32, r: f32, colour: [u8; 4]) {
        self.fill(|px, py| (0.5 - rounded_distance(px, py, x, y, w, h, r)).clamp(0.0, 1.0), colour);
    }

    /// The thin line around the tile, so a light tile still stands out on a light window.
    fn ring(&mut self, x: f32, y: f32, size: f32, r: f32, colour: [u8; 4]) {
        self.fill(|px, py| (1.0 - (rounded_distance(px, py, x, y, size, size, r) + 0.5).abs()).clamp(0.0, 1.0), colour);
    }

    /// A soft shadow under a tile, a little lower than the tile itself.
    fn shadow(&mut self, x: f32, y: f32, size: f32, r: f32, strength: f32) {
        let colour = [0, 0, 0, (strength * 255.0) as u8];
        self.fill(
            |px, py| {
                let d = rounded_distance(px, py, x, y + 3.0, size, size, r);
                // Strongest at the edge of the tile, gone 12 pixels out.
                let t = ((d + 2.0) / 14.0).clamp(0.0, 1.0);
                (1.0 - t) * (1.0 - t)
            },
            colour,
        );
    }

    /// A photo with rounded corners.
    fn picture(&mut self, picture: &image::RgbaImage, x: f32, y: f32, size: f32, r: f32) {
        for (px, py, pixel) in picture.enumerate_pixels() {
            let (cx, cy) = (x + px as f32 + 0.5, y + py as f32 + 0.5);
            let edge = (0.5 - rounded_distance(cx, cy, x, y, size, size, r)).clamp(0.0, 1.0);
            let [red, green, blue, alpha] = pixel.0;
            let colour = [red as f32 / 255.0, green as f32 / 255.0, blue as f32 / 255.0];
            self.over((x as u32) + px, (y as u32) + py, colour, edge * alpha as f32 / 255.0);
        }
    }

    /// A page with a folded corner, in the colour of its kind.
    fn page(&mut self, x: f32, y: f32, w: f32, h: f32, colour: [u8; 3], dark: bool) {
        let fold = w * 0.32;
        let paper = if dark { [72, 72, 78, 255] } else { [255, 255, 255, 255] };
        // The page without its top right corner.
        self.fill(
            |px, py| {
                let inside = (0.5 - rounded_distance(px, py, x, y, w, h, 5.0)).clamp(0.0, 1.0);
                let cut = ((px - (x + w - fold)) + (y + fold - py) - fold) / std::f32::consts::SQRT_2;
                inside * (0.5 - cut).clamp(0.0, 1.0)
            },
            paper,
        );
        // The corner folded down: the half of its square under the cut, a shade darker than the paper.
        let folded = if dark { [100, 100, 108, 255] } else { [222, 223, 230, 255] };
        self.fill(
            |px, py| {
                let in_box = px >= x + w - fold && px <= x + w && py >= y && py <= y + fold;
                let under = ((py - y) - (px - (x + w - fold))) / std::f32::consts::SQRT_2;
                if in_box { (0.5 + under).clamp(0.0, 1.0) } else { 0.0 }
            },
            folded,
        );
        // A band across the lower half, like the label of a file.
        self.rounded(x + 6.0, y + h * 0.56, w - 12.0, h * 0.2, 3.0, [colour[0], colour[1], colour[2], 255]);
    }

    /// A red badge with how many files there are, as the Finder has on a drag.
    fn badge(&mut self, cx: f32, cy: f32, count: usize) {
        let text = if count > 99 { "99+".to_string() } else { count.to_string() };
        let scale = 2.0;
        let width = text.len() as f32 * 4.0 * scale - scale;
        let w = (width + 12.0).max(24.0);
        let (x, y) = (cx - w / 2.0, cy - 12.0);
        self.rounded(x - 2.0, y - 2.0, w + 4.0, 28.0, 14.0, [255, 255, 255, 255]);
        self.rounded(x, y, w, 24.0, 12.0, [255, 59, 48, 255]);
        let mut left = cx - width / 2.0;
        for ch in text.chars() {
            self.glyph(ch, left, cy - 2.5 * scale, scale);
            left += 4.0 * scale;
        }
    }

    /// A digit (or a plus) in a font of three by five dots, in white.
    fn glyph(&mut self, ch: char, x: f32, y: f32, scale: f32) {
        let rows: [u8; 5] = match ch {
            '0' => [7, 5, 5, 5, 7],
            '1' => [2, 6, 2, 2, 7],
            '2' => [7, 1, 7, 4, 7],
            '3' => [7, 1, 7, 1, 7],
            '4' => [5, 5, 7, 1, 1],
            '5' => [7, 4, 7, 1, 7],
            '6' => [7, 4, 7, 5, 7],
            '7' => [7, 1, 1, 1, 1],
            '8' => [7, 5, 7, 5, 7],
            '9' => [7, 5, 7, 1, 7],
            '+' => [0, 2, 7, 2, 0],
            _ => return,
        };
        for (row, bits) in rows.iter().enumerate() {
            for col in 0..3 {
                if bits & (4 >> col) != 0 {
                    let (bx, by) = (x + col as f32 * scale, y + row as f32 * scale);
                    self.rounded(bx, by, scale, scale, 0.0, [255, 255, 255, 255]);
                }
            }
        }
    }

    fn into_bgra(self) -> Vec<u8> {
        let mut out = Vec::with_capacity(self.pixels.len() * 4);
        for [r, g, b, a] in self.pixels {
            out.extend_from_slice(&[(b * 255.0).round() as u8, (g * 255.0).round() as u8, (r * 255.0).round() as u8, (a * 255.0).round() as u8]);
        }
        out
    }
}

/// How far a point lies outside a rectangle with rounded corners (less than zero inside).
fn rounded_distance(px: f32, py: f32, x: f32, y: f32, w: f32, h: f32, r: f32) -> f32 {
    let (cx, cy) = (x + w / 2.0, y + h / 2.0);
    let r = r.min(w / 2.0).min(h / 2.0);
    let qx = (px - cx).abs() - (w / 2.0 - r);
    let qy = (py - cy).abs() - (h / 2.0 - r);
    let outside = (qx.max(0.0).powi(2) + qy.max(0.0).powi(2)).sqrt();
    outside + qx.max(qy).min(0.0) - r
}

/// Shows the card for files that were just put down, under the pointer of the computer that dropped them. False when there is no card
/// (no X server, or the place of the pointer is not known): the notice is all there is then.
#[cfg(target_os = "linux")]
pub fn show(app: &tauri::AppHandle, files: Vec<PathBuf>) -> bool {
    use tauri::Manager;
    // Files of the same drop that come in a little later go on the card that is up, as long as nobody picked it up.
    static SHOWN: std::sync::Mutex<Vec<PathBuf>> = std::sync::Mutex::new(Vec::new());
    let Some(at) = crate::input::shared_at() else { return false };
    if files.is_empty() {
        return false;
    }
    let files = {
        let mut shown = SHOWN.lock().unwrap();
        if !tandem_winsys::dragcard::open() {
            shown.clear();
        }
        shown.extend(files);
        shown.clone()
    };
    let dark = app.get_webview_window("main").and_then(|w| w.theme().ok()).is_some_and(|t| t == tauri::Theme::Dark);
    let pixels = paint(&files, dark);
    let uris = files.iter().map(|f| tandem_winsys::file_uri(&f.to_string_lossy())).collect();
    tandem_winsys::dragcard::show(pixels, SIZE as u16, at, uris)
}

#[cfg(test)]
mod tests {
    use super::*;

    fn alpha_at(pixels: &[u8], x: u32, y: u32) -> u8 {
        pixels[((y * SIZE + x) * 4 + 3) as usize]
    }

    #[test]
    fn the_card_is_solid_in_the_middle_and_clear_at_the_corners() {
        let pixels = paint(&[PathBuf::from("/nowhere/report.pdf")], false);
        assert_eq!(pixels.len(), (SIZE * SIZE * 4) as usize);
        let middle = (TILE_AT + TILE / 2.0) as u32;
        assert_eq!(alpha_at(&pixels, middle, middle), 255);
        assert_eq!(alpha_at(&pixels, 0, 0), 0);
        assert_eq!(alpha_at(&pixels, SIZE - 1, 0), 0);
        // The colours never go above their alpha, which is what the X server expects.
        for px in pixels.chunks(4) {
            assert!(px[0] <= px[3] && px[1] <= px[3] && px[2] <= px[3], "{px:?}");
        }
    }

    #[test]
    fn more_files_get_a_pile_and_a_badge() {
        let one = paint(&[PathBuf::from("/a.txt")], true);
        let three = paint(&[PathBuf::from("/a.txt"), PathBuf::from("/b.txt"), PathBuf::from("/c.txt")], true);
        // The badge is red at the top right of the tile.
        let (bx, by) = ((TILE_AT + TILE - 6.0 - 8.0) as u32, (TILE_AT + 6.0) as u32);
        let at = ((by * SIZE + bx) * 4) as usize;
        assert!(three[at + 2] > 200 && three[at + 1] < 120, "{:?}", &three[at..at + 4]);
        assert_ne!(one[at..at + 4], three[at..at + 4]);
    }

    /// With `TANDEM_CARD_PNG=<folder>` the cards are written there as pictures, on a grey like a window, to look at.
    #[test]
    fn pictures_of_the_cards() {
        let Ok(folder) = std::env::var("TANDEM_CARD_PNG") else { return };
        for (name, files, dark) in [
            ("pdf-light", vec![PathBuf::from("/a/report.pdf")], false),
            ("three-dark", vec![PathBuf::from("/a/song.mp3"), PathBuf::from("/a/b.txt"), PathBuf::from("/a/c.txt")], true),
            ("twelve-light", (0..12).map(|i| PathBuf::from(format!("/a/{i}.zip"))).collect(), false),
        ] {
            let bgra = paint(&files, dark);
            let ground = if dark { 30.0 } else { 225.0 };
            let mut out = image::RgbImage::new(SIZE, SIZE);
            for (i, px) in bgra.chunks(4).enumerate() {
                let a = px[3] as f32 / 255.0;
                let mix = |c: u8| (c as f32 + ground * (1.0 - a)).round() as u8;
                out.put_pixel(i as u32 % SIZE, i as u32 / SIZE, image::Rgb([mix(px[2]), mix(px[1]), mix(px[0])]));
            }
            out.save(Path::new(&folder).join(format!("{name}.png"))).unwrap();
        }
    }

    #[test]
    fn a_photo_shows_itself() {
        let dir = std::env::temp_dir().join(format!("tandem-card-{}", std::process::id()));
        std::fs::create_dir_all(&dir).unwrap();
        let file = dir.join("green.png");
        image::RgbaImage::from_pixel(200, 120, image::Rgba([0, 200, 0, 255])).save(&file).unwrap();
        let pixels = paint(&[file], false);
        let middle = (TILE_AT + TILE / 2.0) as u32;
        let at = ((middle * SIZE + middle) * 4) as usize;
        assert_eq!(&pixels[at..at + 4], &[0, 200, 0, 255]);
        let _ = std::fs::remove_dir_all(dir);
    }
}
