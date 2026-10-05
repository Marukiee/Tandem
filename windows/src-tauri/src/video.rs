//! A picture decoder of this app's own, for the systems whose web view cannot decode video: the web view of Linux mostly
//! has no WebCodecs, and then the screen and the camera of a phone, which arrive as H.264, would never show. Here the pictures
//! are decoded (OpenH264, in software), made smaller when they are large, and handed on as JPEG, which every web view draws
//! at once.
//!
//! Only built with the `native-video` feature. Windows decodes in its web view, on the graphics card.

use jpeg_encoder::{ColorType, Encoder};
use openh264::decoder::Decoder as H264;
use openh264::formats::YUVSource;

/// The longest side of what is sent on. A phone screen is 2400 pixels high, which is more than a window shows, and every
/// pixel costs time in the decoder, the encoder and the web view.
const LONGEST_SIDE: usize = 1280;
const QUALITY: u8 = 82;

pub struct Decoder {
    h264: H264,
    rgb: Vec<u8>,
    small: Vec<u8>,
}

impl Decoder {
    pub fn new() -> Option<Decoder> {
        match H264::new() {
            Ok(h264) => Some(Decoder { h264, rgb: Vec::new(), small: Vec::new() }),
            Err(error) => {
                log::warn!("the picture decoder could not start: {error}");
                None
            }
        }
    }

    /// An access unit in Annex B goes in; when it completes a picture, that comes out as a JPEG.
    pub fn picture(&mut self, access_unit: &[u8]) -> Option<Vec<u8>> {
        let yuv = match self.h264.decode(access_unit) {
            Ok(Some(yuv)) => yuv,
            Ok(None) => return None,
            Err(error) => {
                log::debug!("a picture was not decoded: {error}");
                return None;
            }
        };
        let (width, height) = yuv.dimensions();
        if width == 0 || height == 0 {
            return None;
        }
        self.rgb.resize(width * height * 3, 0);
        yuv.write_rgb8(&mut self.rgb);

        // Each step of the factor takes the average of a block, so lines stay smooth.
        let factor = width.max(height).div_ceil(LONGEST_SIDE).max(1);
        let (out, w, h) = if factor == 1 {
            (&self.rgb, width, height)
        } else {
            let (w, h) = (width / factor, height / factor);
            self.small.resize(w * h * 3, 0);
            shrink(&self.rgb, width, &mut self.small, w, h, factor);
            (&self.small, w, h)
        };
        let mut jpeg = Vec::with_capacity(w * h / 4);
        Encoder::new(&mut jpeg, QUALITY).encode(out, w as u16, h as u16, ColorType::Rgb).ok()?;
        Some(jpeg)
    }
}

/// The picture at `1 / factor` of its size, every new pixel the average of the block it stands for.
fn shrink(from: &[u8], from_width: usize, to: &mut [u8], width: usize, height: usize, factor: usize) {
    let area = (factor * factor) as u32;
    for y in 0..height {
        for x in 0..width {
            let mut sum = [0u32; 3];
            for dy in 0..factor {
                let row = ((y * factor + dy) * from_width + x * factor) * 3;
                for dx in 0..factor {
                    for c in 0..3 {
                        sum[c] += u32::from(from[row + dx * 3 + c]);
                    }
                }
            }
            let at = (y * width + x) * 3;
            for c in 0..3 {
                to[at + c] = (sum[c] / area) as u8;
            }
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use openh264::encoder::Encoder as H264Encoder;
    use openh264::formats::{RgbSliceU8, YUVBuffer};

    /// A picture that is a gradient, with a bright square that moves with the frame number.
    fn frame(width: usize, height: usize, n: usize) -> Vec<u8> {
        let mut rgb = vec![0u8; width * height * 3];
        for y in 0..height {
            for x in 0..width {
                let at = (y * width + x) * 3;
                rgb[at] = (x * 255 / width) as u8;
                rgb[at + 1] = (y * 255 / height) as u8;
                rgb[at + 2] = 80;
                let (sx, sy) = (20 + n * 8, 30);
                if x >= sx && x < sx + 40 && y >= sy && y < sy + 40 {
                    rgb[at..at + 3].copy_from_slice(&[255, 255, 255]);
                }
            }
        }
        rgb
    }

    #[test]
    fn a_stream_of_h264_comes_out_as_jpeg_pictures() {
        let (width, height) = (320usize, 240usize);
        let mut encoder = H264Encoder::new().unwrap();
        let mut decoder = Decoder::new().unwrap();
        let mut pictures = 0;
        for n in 0..8 {
            let rgb = frame(width, height, n);
            let yuv = YUVBuffer::from_rgb_source(RgbSliceU8::new(&rgb, (width, height)));
            let bytes = encoder.encode(&yuv).unwrap().to_vec();
            if let Some(jpeg) = decoder.picture(&bytes) {
                assert_eq!(&jpeg[..2], &[0xFF, 0xD8], "a JPEG starts with FFD8");
                assert_eq!(&jpeg[jpeg.len() - 2..], &[0xFF, 0xD9], "and ends with FFD9");
                pictures += 1;
            }
        }
        assert!(pictures >= 6, "most frames give a picture, got {pictures}");
    }

    #[test]
    fn large_pictures_are_made_smaller() {
        let (width, height) = (1920usize, 1080usize);
        let mut encoder = H264Encoder::new().unwrap();
        let mut decoder = Decoder::new().unwrap();
        let rgb = frame(width, height, 0);
        let yuv = YUVBuffer::from_rgb_source(RgbSliceU8::new(&rgb, (width, height)));
        let bytes = encoder.encode(&yuv).unwrap().to_vec();
        let jpeg = decoder.picture(&bytes).expect("the first picture");
        // The JPEG says how large it is in its frame header (after the marker FFC0): height, then width.
        let at = jpeg.windows(2).position(|w| w == [0xFF, 0xC0]).expect("a frame header");
        let h = u16::from_be_bytes([jpeg[at + 5], jpeg[at + 6]]) as usize;
        let w = u16::from_be_bytes([jpeg[at + 7], jpeg[at + 8]]) as usize;
        assert_eq!((w, h), (960, 540));
    }

    #[test]
    fn shrinking_averages_the_blocks() {
        let from = [0u8, 0, 0, 100, 100, 100, 200, 200, 200, 100, 100, 100];
        let mut to = [0u8; 3];
        // A block of 2 by 2 pixels laid out as a row of 4 pixels wide and one high does not fit, so use 2 wide and 2 high.
        let from2 = [from[0..3].to_vec(), from[3..6].to_vec(), from[6..9].to_vec(), from[9..12].to_vec()].concat();
        shrink(&from2, 2, &mut to, 1, 1, 2);
        assert_eq!(to, [100, 100, 100]);
    }
}
