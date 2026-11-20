//! pikodf-bench IN.f32 [OUT_PREFIX]: IN is mono float32 little-endian at 48 kHz.
//! Default: DeepFilterNet's own three graphs on the whole note are the reference ("official whole"); desktop clearcut's
//! chunking (30 s + 1 s context) is "clearcut"; every other case reports time and SI-SDR against both.
//! PIKODF_ONLY=N [PIKODF_BACKEND=ort|tract]: only the app's policy (Dfn::denoise), best of N runs.
use pikodf::{Backend, Dfn, Timing, SR};
use std::time::Instant;

fn read(p: &str) -> Vec<f32> {
    let b = std::fs::read(p).unwrap();
    b.chunks_exact(4).map(|c| f32::from_le_bytes([c[0], c[1], c[2], c[3]])).collect()
}

fn write(p: &str, x: &[f32]) {
    std::fs::write(p, x.iter().flat_map(|v| v.to_le_bytes()).collect::<Vec<u8>>()).unwrap();
}

/// Scale-invariant SDR of `est` against `reference`, dB.
fn si_sdr(est: &[f32], reference: &[f32]) -> f64 {
    let n = est.len().min(reference.len());
    let (mut dot, mut rr) = (0f64, 0f64);
    for i in 0..n {
        dot += est[i] as f64 * reference[i] as f64;
        rr += reference[i] as f64 * reference[i] as f64;
    }
    let a = dot / rr.max(1e-12);
    let (mut s, mut e) = (0f64, 0f64);
    for i in 0..n {
        let t = a * reference[i] as f64;
        s += t * t;
        let d = est[i] as f64 - t;
        e += d * d;
    }
    10.0 * (s / e.max(1e-20)).log10()
}

fn app_backend() -> Backend {
    match std::env::var("PIKODF_BACKEND").as_deref() {
        #[cfg(feature = "ort")]
        Ok("ort") => Backend::Ort,
        _ => Backend::Soniqo,
    }
}

fn main() {
    let args: Vec<String> = std::env::args().collect();
    // pikodf-bench resample IN.f32 FROM TO OUT.f32 (to compare with VoiceCut.resample)
    if args[1] == "resample" {
        let x = read(&args[2]);
        let start = Instant::now();
        let y = pikodf::resample(&x, args[3].parse().unwrap(), args[4].parse().unwrap());
        println!("resampled {} -> {} samples in {:.1} ms", x.len(), y.len(), start.elapsed().as_secs_f64() * 1e3);
        write(&args[5], &y);
        return;
    }
    let x = read(&args[1]);
    let out = args.get(2).cloned();
    let secs = x.len() as f64 / SR as f64;

    if let Some(n) = std::env::var("PIKODF_ONLY").ok().and_then(|v| v.parse::<usize>().ok()) {
        let b = app_backend();
        let mut best = (f64::MAX, Timing::default());
        for _ in 0..n {
            let d = Dfn::new(b); // as the app: a fresh session per note
            let mut t = Timing::default();
            let start = Instant::now();
            let y = d.denoise(&x, None, &mut t).unwrap();
            if let Some(o) = &out { write(&format!("{o}.app.f32"), &y); }
            let e = start.elapsed().as_secs_f64();
            if e < best.0 { best = (e, t); }
        }
        let t = best.1;
        println!("{b:?} app policy {secs:.0}s: best of {n} {:.3} s = RTF {:.4} [load {:.3}, features {:.3}, net {:.3}, filter+synth {:.3}; summed over workers]",
            best.0, best.0 / secs, t.load, t.features, t.network, t.filter);
        return;
    }

    println!("input {secs:.1} s");
    let mut refs: Vec<(&str, Vec<f32>)> = vec![];
    #[cfg(feature = "official")]
    {
        let d = Dfn::new(Backend::Official);
        let r = d.enhance(&x, 0.0, &mut Timing::default()).unwrap();
        let pc = d.enhance_chunked(&x, 30.0, 1.0, 1, None, &mut Timing::default()).unwrap();
        if let Some(o) = &out { write(&format!("{o}.official.f32"), &r); write(&format!("{o}.pc.f32"), &pc); }
        println!("clearcut (official, 30 s + 1 s chunks) vs official whole: SI-SDR {:.1} dB", si_sdr(&pc, &r));
        refs.push(("whole", r));
        refs.push(("clearcut", pc));
    }

    #[allow(unused_mut)]
    let mut cases: Vec<(&str, Backend, bool)> = vec![("tract soniqo whole", Backend::Soniqo, false), ("tract soniqo app", Backend::Soniqo, true)];
    #[cfg(feature = "ort")]
    { cases.push(("ORT soniqo whole", Backend::Ort, false)); cases.push(("ORT soniqo app", Backend::Ort, true)); }
    for (name, backend, app) in cases {
        let mut runs = vec![];
        let mut y = vec![];
        for _ in 0..2 {
            let d = Dfn::new(backend);
            let mut t = Timing::default();
            let start = Instant::now();
            y = if app { d.denoise(&x, None, &mut t).unwrap() } else { d.enhance(&x, 0.0, &mut t).unwrap() };
            runs.push((start.elapsed().as_secs_f64(), t));
        }
        let q: Vec<String> = refs.iter().map(|(n, r)| format!("vs {n} {:6.1} dB", si_sdr(&y, r))).collect();
        let (warm, tw) = runs[1];
        println!("{name:20} {warm:6.2} s = RTF {:.4} [load {:.2}, features {:.2}, net {:.2}, filter+synth {:.2}] | SI-SDR {}",
            warm / secs, tw.load, tw.features, tw.network, tw.filter, q.join(", "));
        if let Some(o) = &out { write(&format!("{o}.{}.f32", name.replace(' ', "_")), &y); }
    }
}
