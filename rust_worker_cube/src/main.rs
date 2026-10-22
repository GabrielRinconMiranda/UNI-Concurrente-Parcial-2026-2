//! Rust Worker Cube: High-safety distributed compute node for Open AI Cubes.
//! Uses exclusively the Rust standard library (std::net, std::thread).

use std::env;
use std::io::{BufRead, BufReader, Write};
use std::net::TcpStream;

pub const WIDTH: usize = 28;
pub const HEIGHT: usize = 28;
pub const TOTAL_PIXELS: usize = WIDTH * HEIGHT; // 784

pub const KERNEL_SIZE: usize = 3;
pub const NUM_FILTERS: usize = 8;
pub const CONV_OUT_DIM: usize = WIDTH - KERNEL_SIZE + 1; // 26
pub const POOL_STRIDE: usize = 2;
pub const POOL_OUT_DIM: usize = CONV_OUT_DIM / POOL_STRIDE; // 13
pub const FEATURE_DIM_PER_FILTER: usize = POOL_OUT_DIM * POOL_OUT_DIM; // 169
pub const TOTAL_FEATURE_DIM: usize = NUM_FILTERS * FEATURE_DIM_PER_FILTER; // 1352

pub const KERNELS: [[[f32; 3]; 3]; 8] = [
    // 0: Horizontal Edge
    [
        [-1.0, -1.0, -1.0],
        [ 2.0,  2.0,  2.0],
        [-1.0, -1.0, -1.0],
    ],
    // 1: Vertical Edge
    [
        [-1.0,  2.0, -1.0],
        [-1.0,  2.0, -1.0],
        [-1.0,  2.0, -1.0],
    ],
    // 2: Diagonal (+45 deg)
    [
        [-1.0, -1.0,  2.0],
        [-1.0,  2.0, -1.0],
        [ 2.0, -1.0, -1.0],
    ],
    // 3: Diagonal (-45 deg)
    [
        [ 2.0, -1.0, -1.0],
        [-1.0,  2.0, -1.0],
        [-1.0, -1.0,  2.0],
    ],
    // 4: Sharpen / Center-Surround
    [
        [ 0.0, -1.0,  0.0],
        [-1.0,  5.0, -1.0],
        [ 0.0, -1.0,  0.0],
    ],
    // 5: Ridge Horizontal
    [
        [ 1.0,  1.0,  1.0],
        [-2.0, -2.0, -2.0],
        [ 1.0,  1.0,  1.0],
    ],
    // 6: Ridge Vertical
    [
        [ 1.0, -2.0,  1.0],
        [ 1.0, -2.0,  1.0],
        [ 1.0, -2.0,  1.0],
    ],
    // 7: Laplacian Texture
    [
        [ 1.0,  1.0,  1.0],
        [ 1.0, -8.0,  1.0],
        [ 1.0,  1.0,  1.0],
    ],
];

pub const CLASSES: [&str; 5] = ["panda", "cat", "dog", "bear", "lion"];

fn set_block(p: &mut [f32], sx: usize, sy: usize, w: usize, h: usize, val: f32) {
    for y in sy..std::cmp::min(sy + h, HEIGHT) {
        for x in sx..std::cmp::min(sx + w, WIDTH) {
            p[y * WIDTH + x] = val;
        }
    }
}

pub fn create_sample(class_name: &str) -> Vec<f32> {
    let mut p = vec![0.0f32; TOTAL_PIXELS];

    match class_name {
        "panda" => {
            set_block(&mut p, 6, 6, 4, 6, 1.0);
            set_block(&mut p, 18, 6, 4, 6, 1.0);
            set_block(&mut p, 2, 0, 6, 4, 1.0);
            set_block(&mut p, 20, 0, 6, 4, 1.0);
            set_block(&mut p, 12, 16, 4, 4, 1.0);
        }
        "cat" => {
            for i in 0..4 {
                p[i * WIDTH + (4 + i)] = 1.0;
                p[i * WIDTH + (23 - i)] = 1.0;
            }
            set_block(&mut p, 4, 2, 4, 3, 1.0);
            set_block(&mut p, 20, 2, 4, 3, 1.0);
            set_block(&mut p, 2, 12, 8, 2, 1.0);
            set_block(&mut p, 18, 12, 8, 2, 1.0);
            set_block(&mut p, 8, 10, 3, 2, 1.0);
            set_block(&mut p, 17, 10, 3, 2, 1.0);
        }
        "dog" => {
            set_block(&mut p, 0, 6, 5, 10, 1.0);
            set_block(&mut p, 23, 6, 5, 10, 1.0);
            set_block(&mut p, 10, 14, 8, 6, 1.0);
            set_block(&mut p, 8, 8, 3, 3, 1.0);
            set_block(&mut p, 17, 8, 3, 3, 1.0);
        }
        "bear" => {
            set_block(&mut p, 4, 0, 6, 6, 1.0);
            set_block(&mut p, 18, 0, 6, 6, 1.0);
            set_block(&mut p, 8, 12, 12, 8, 1.0);
        }
        "lion" => {
            for x in 0..WIDTH {
                p[0 * WIDTH + x] = 1.0;
                p[1 * WIDTH + x] = 1.0;
                p[(HEIGHT - 2) * WIDTH + x] = 1.0;
                p[(HEIGHT - 1) * WIDTH + x] = 1.0;
            }
            for y in 0..HEIGHT {
                p[y * WIDTH + 0] = 1.0;
                p[y * WIDTH + 1] = 1.0;
                p[y * WIDTH + (WIDTH - 2)] = 1.0;
                p[y * WIDTH + (WIDTH - 1)] = 1.0;
            }
            set_block(&mut p, 10, 10, 8, 8, 0.8);
        }
        _ => p.fill(0.5),
    }
    p
}

pub fn convolution_chunk(pixels: &[f32]) -> Vec<f32> {
    let mut features = vec![0.0f32; TOTAL_FEATURE_DIM];
    let total_p = pixels.len();
    let side = (total_p as f64).sqrt().round() as usize;
    let in_w = if side * side == total_p { side } else { WIDTH };
    let in_h = in_w;

    let conv_w = in_w - KERNEL_SIZE + 1;
    let conv_h = in_h - KERNEL_SIZE + 1;

    std::thread::scope(|s| {
        let chunks: Vec<&mut [f32]> = features.chunks_mut(FEATURE_DIM_PER_FILTER).collect();
        for (f, out_chunk) in chunks.into_iter().enumerate() {
            let kernel = &KERNELS[f];
            s.spawn(move || {
                for py in 0..POOL_OUT_DIM {
                    let start_y = py * conv_h / POOL_OUT_DIM;
                    let end_y = (py + 1) * conv_h / POOL_OUT_DIM;

                    for px in 0..POOL_OUT_DIM {
                        let start_x = px * conv_w / POOL_OUT_DIM;
                        let end_x = (px + 1) * conv_w / POOL_OUT_DIM;

                        let mut max_val = 0.0f32;
                        for y in start_y..end_y {
                            for x in start_x..end_x {
                                let mut sum = 0.0f32;
                                for ky in 0..KERNEL_SIZE {
                                    for kx in 0..KERNEL_SIZE {
                                        sum += pixels[(y + ky) * in_w + (x + kx)] * kernel[ky][kx];
                                    }
                                }
                                if sum > max_val {
                                    max_val = sum;
                                }
                            }
                        }
                        out_chunk[py * POOL_OUT_DIM + px] = max_val;
                    }
                }
            });
        }
    });

    features
}

pub fn get_prototypes() -> Vec<Vec<f32>> {
    // 0. Check standard weights.csv first
    let weight_paths = ["dataset/weights.csv", "../dataset/weights.csv"];
    for path in &weight_paths {
        if let Ok(file) = std::fs::File::open(path) {
            let reader = std::io::BufReader::new(file);
            let mut loaded = vec![vec![]; CLASSES.len()];
            let mut count = 0;
            for line in reader.lines().map_while(Result::ok) {
                let trimmed = line.trim();
                if trimmed.is_empty() || trimmed.starts_with('#') {
                    continue;
                }
                let mut parts = trimmed.split(',');
                if let Some(label) = parts.next() {
                    let label = label.trim().to_lowercase();
                    if let Some(c_idx) = CLASSES.iter().position(|&c| c == label) {
                        let vals: Vec<f32> = parts.filter_map(|s| s.trim().parse().ok()).collect();
                        if vals.len() >= TOTAL_FEATURE_DIM {
                            loaded[c_idx] = vals;
                            count += 1;
                        }
                    }
                }
            }
            if count == CLASSES.len() {
                return loaded;
            }
        }
    }

    // 1. Fallback to database.csv
    let paths = ["dataset/database.csv", "../dataset/database.csv"];
    for path in &paths {
        if let Ok(file) = std::fs::File::open(path) {
            let reader = std::io::BufReader::new(file);
            let mut accum = vec![vec![0.0f32; TOTAL_FEATURE_DIM]; CLASSES.len()];
            let mut counts = vec![0usize; CLASSES.len()];

            for line in reader.lines().map_while(Result::ok) {
                let trimmed = line.trim();
                if trimmed.is_empty() || trimmed.starts_with('#') {
                    continue;
                }
                let mut parts = trimmed.split(',');
                if let Some(label) = parts.next() {
                    let label = label.trim().to_lowercase();
                    if let Some(class_idx) = CLASSES.iter().position(|&c| c == label) {
                        let pixels: Vec<f32> = parts.filter_map(|s| s.trim().parse().ok()).collect();
                        if pixels.len() >= TOTAL_PIXELS {
                            let feats = convolution_chunk(&pixels);
                            for (i, &f) in feats.iter().enumerate() {
                                accum[class_idx][i] += f;
                            }
                            counts[class_idx] += 1;
                        }
                    }
                }
            }

            if counts.iter().all(|&c| c > 0) {
                let mut protos = vec![vec![0.0f32; TOTAL_FEATURE_DIM]; CLASSES.len()];
                for c in 0..CLASSES.len() {
                    let count = counts[c] as f32;
                    for i in 0..TOTAL_FEATURE_DIM {
                        protos[c][i] = accum[c][i] / count;
                    }
                }
                return protos;
            }
        }
    }

    CLASSES.iter().map(|c| convolution_chunk(&create_sample(c))).collect()
}

pub fn mlp_chunk(features: &[f32]) -> (&'static str, f32, Vec<f32>) {
    let prototypes = get_prototypes();
    let mut raw_scores = Vec::with_capacity(CLASSES.len());
    let mut min_score = f32::INFINITY;
    let mut max_score = f32::NEG_INFINITY;

    for proto in &prototypes {
        let mut dot = 0.0f32;
        let mut norm_w = 1e-4f32;
        let mut norm_x = 1e-4f32;

        for i in 0..TOTAL_FEATURE_DIM {
            dot += proto[i] * features[i];
            norm_w += proto[i] * proto[i];
            norm_x += features[i] * features[i];
        }

        let cosine = dot / (norm_w.sqrt() * norm_x.sqrt());
        if cosine < min_score {
            min_score = cosine;
        }
        if cosine > max_score {
            max_score = cosine;
        }
        raw_scores.push(cosine);
    }

    let range = (max_score - min_score).max(0.001);
    let mut logits = Vec::with_capacity(CLASSES.len());
    let mut max_logit = f32::NEG_INFINITY;
    for &s in &raw_scores {
        let logit = ((s - min_score) / range) * 6.0;
        if logit > max_logit {
            max_logit = logit;
        }
        logits.push(logit);
    }

    let mut sum_exp = 0.0f32;
    let mut exp_vals = Vec::with_capacity(CLASSES.len());
    for &l in &logits {
        let e = (l - max_logit).exp();
        sum_exp += e;
        exp_vals.push(e);
    }

    let mut best_idx = 0;
    let mut best_prob = 0.0f32;
    let mut probs = Vec::with_capacity(CLASSES.len());
    for (i, &e) in exp_vals.iter().enumerate() {
        let prob = e / sum_exp;
        if prob > best_prob {
            best_prob = prob;
            best_idx = i;
        }
        probs.push(prob);
    }

    (CLASSES[best_idx], best_prob, probs)
}

pub fn handle_message(line: &str) -> Option<String> {
    let mut parts = line.splitn(3, '|');
    let action = parts.next()?;
    let task_id = parts.next()?;
    let payload = parts.next().unwrap_or("");

    match action {
        "CONV_REQ" => {
            let pixels: Vec<f32> = payload.split(',').filter_map(|s| s.parse().ok()).collect();
            let feats = convolution_chunk(&pixels);
            let out_str: Vec<String> = feats.iter().map(|f| format!("{:.4}", f)).collect();
            Some(format!("CONV_RESP|{}|{}", task_id, out_str.join(",")))
        }
        "MLP_REQ" => {
            let feats: Vec<f32> = payload.split(',').filter_map(|s| s.parse().ok()).collect();
            let (label, conf, probs) = mlp_chunk(&feats);
            let prob_str: Vec<String> = probs.iter().map(|p| format!("{:.4}", p)).collect();
            Some(format!("MLP_RESP|{}|{}:{:.2};{}", task_id, label, conf * 100.0, prob_str.join(";")))
        }
        "INFER_REQ" => {
            let csv = if let Some((_, c)) = payload.split_once('|') { c } else { payload };
            let pixels: Vec<f32> = csv.split(',').filter_map(|s| s.parse().ok()).collect();
            let feats = convolution_chunk(&pixels);
            let (label, conf, probs) = mlp_chunk(&feats);
            let prob_str: Vec<String> = probs.iter().map(|p| format!("{:.4}", p)).collect();
            Some(format!("INFER_RESP|{}|{}:{:.2};{}", task_id, label, conf * 100.0, prob_str.join(";")))
        }
        "PING" => Some(format!("PONG|{}|OK", task_id)),
        _ => Some(format!("UNKNOWN|{}|ERR", task_id)),
    }
}

fn run_http_worker(port: u16, threads: usize) -> std::io::Result<()> {
    let listener = std::net::TcpListener::bind(format!("0.0.0.0:{}", port))?;
    println!("[RustWorker-HTTP] Listening on port {} (threads: {})...", port, threads);
    for stream_res in listener.incoming() {
        if let Ok(mut stream) = stream_res {
            let mut reader = BufReader::new(stream.try_clone()?);
            let mut request_line = String::new();
            if reader.read_line(&mut request_line).is_err() {
                continue;
            }
            let mut content_length: usize = 0;
            loop {
                let mut header = String::new();
                if reader.read_line(&mut header).is_err() || header.trim().is_empty() {
                    break;
                }
                let lower = header.to_lowercase();
                if lower.starts_with("content-length:") {
                    if let Some(val) = header.split(':').nth(1) {
                        content_length = val.trim().parse().unwrap_or(0);
                    }
                }
            }

            let mut body = vec![0u8; content_length];
            let _ = std::io::Read::read_exact(&mut reader, &mut body);
            let body_str = String::from_utf8_lossy(&body).trim().to_string();

            let parts: Vec<&str> = request_line.split_whitespace().collect();
            let path = parts.get(1).copied().unwrap_or("/");

            let (status, content_type, resp_body) = if path == "/health" {
                ("200 OK", "application/json", format!("{{\"status\":\"ok\",\"worker\":\"node_rust_cloud\",\"language\":\"Rust\",\"threads\":{}}}\n", threads))
            } else if path == "/conv" {
                let r = handle_message(&format!("CONV_REQ|http|{}", body_str)).unwrap_or_default();
                let payload = r.split('|').nth(2).unwrap_or("").to_string();
                ("200 OK", "text/plain", payload)
            } else if path == "/mlp" {
                let r = handle_message(&format!("MLP_REQ|http|{}", body_str)).unwrap_or_default();
                let payload = r.split('|').nth(2).unwrap_or("").to_string();
                ("200 OK", "text/plain", payload)
            } else if path == "/infer" {
                let r = handle_message(&format!("INFER_REQ|http|{}", body_str)).unwrap_or_default();
                let payload = r.split('|').nth(2).unwrap_or("").to_string();
                ("200 OK", "text/plain", payload)
            } else {
                ("404 Not Found", "text/plain", "Not Found\n".to_string())
            };

            let response = format!(
                "HTTP/1.1 {}\r\nContent-Type: {}\r\nAccess-Control-Allow-Origin: *\r\nContent-Length: {}\r\nConnection: close\r\n\r\n{}",
                status, content_type, resp_body.len(), resp_body
            );
            let _ = stream.write_all(response.as_bytes());
            let _ = stream.flush();
        }
    }
    Ok(())
}

fn main() -> std::io::Result<()> {
    let args: Vec<String> = env::args().collect();
    if args.get(1).map(|s| s.as_str()) == Some("--http") {
        let env_port = env::var("PORT").ok().and_then(|p| p.parse().ok());
        let port: u16 = args.get(2)
            .and_then(|s| s.trim_start_matches(':').parse().ok())
            .or(env_port)
            .unwrap_or(8084);
        let threads = args.get(3).and_then(|s| s.parse().ok()).unwrap_or(4);
        return run_http_worker(port, threads);
    }

    let worker_id = args.get(1).map(|s| s.as_str()).unwrap_or("node_rust_1");
    let host = args.get(2).map(|s| s.as_str()).unwrap_or("127.0.0.1");
    let port = args.get(3).and_then(|s| s.parse().ok()).unwrap_or(9010);
    let threads = args.get(4).and_then(|s| s.parse().ok()).unwrap_or(4);

    println!("[RustWorker {}] Connecting to {}:{}...", worker_id, host, port);
    let stream = TcpStream::connect(format!("{}:{}", host, port))?;
    let mut writer = stream.try_clone()?;
    let reader = BufReader::new(stream);

    let reg_msg = format!("REGISTER|{}|Rust|{}|CONV,MLP\n", worker_id, threads);
    writer.write_all(reg_msg.as_bytes())?;
    writer.flush()?;

    for line_res in reader.lines() {
        let line = line_res?;
        if line.starts_with("REGISTER_ACK") {
            println!("[RustWorker {}] Registration acknowledged: {}", worker_id, line);
            continue;
        }

        if let Some(reply) = handle_message(&line) {
            writer.write_all(format!("{}\n", reply).as_bytes())?;
            writer.flush()?;
        }
    }

    println!("[RustWorker {}] Disconnected.", worker_id);
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_all_classes() {
        for &c in &CLASSES {
            let sample = create_sample(c);
            assert_eq!(sample.len(), 784);
            let feats = convolution_chunk(&sample);
            assert_eq!(feats.len(), 1352);
            let (label, conf, probs) = mlp_chunk(&feats);
            assert!(CLASSES.contains(&label));
            assert!(conf > 0.0 && conf <= 1.0);
            let p_sum: f32 = probs.iter().sum();
            assert!((p_sum - 1.0).abs() < 0.01);

            // Test 720x720 high-resolution
            let hi_res = vec![0.2f32; 720 * 720];
            let hi_feats = convolution_chunk(&hi_res);
            assert_eq!(hi_feats.len(), 1352);

            println!("  [PASS] Sample {:5} -> {} ({:.1}%)", c, label, conf * 100.0);
        }
    }
}
