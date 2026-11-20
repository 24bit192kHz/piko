//! DeepFilterNet3 (v0.5.6) for Piko's voice notes. STFT, ERB/complex features, deep filtering and synthesis are
//! libDF's own code (DeepFilterNet's Rust crate), computed like Python's df.enhance (whole span: pad_feat -> nets ->
//! ERB mask for the high bins, deep filter order 5 / lookahead 2 over the low 96 bins). Long audio is cut into
//! chunks with context on both sides, as desktop clearcut's denoise does. The network runs either in ONNX Runtime (the
//! app: soniqo's single verified graph) or in tract (benchmarks: DeepFilterNet's own three graphs, the reference).
use anyhow::{anyhow, bail, Result};
#[cfg(feature = "tract")]
use anyhow::ensure;
use df::DFState;
use num_complex::Complex32;
use std::collections::HashMap;
use std::sync::atomic::{AtomicBool, AtomicUsize, Ordering};
use std::sync::{Arc, Mutex};
use std::time::Instant;

pub const SR: usize = 48000;
const FFT: usize = 960;
const HOP: usize = 480;
const NB_ERB: usize = 32;
const NB_DF: usize = 96;
const FREQS: usize = FFT / 2 + 1;
const DF_ORDER: usize = 5;
const DF_LOOKAHEAD: usize = 2;
const COEFS: usize = NB_DF * DF_ORDER * 2;

/// soniqo/DeepFilterNet3-ONNX deepfilter.onnx (sha256 e1157049…56d3): pad_feat, encoder, both decoders.
static SONIQO: &[u8] = include_bytes!("../models/soniqo/deepfilter.onnx");

/// What the app runs: notes up to this long in one pass, longer ones in chunks (core + context on both sides).
pub const WHOLE_MAX_S: f32 = 20.0;
pub const CHUNK_CORE_S: f32 = 15.0;
pub const CHUNK_CTX_S: f32 = 2.0;
pub const WORKERS: usize = 2;

#[derive(Clone, Copy, Debug, PartialEq, Eq, Hash)]
pub enum Backend {
    /// DeepFilterNet's own three graphs (enc, erb_dec, df_dec) in tract, pad_feat done outside (desktop clearcut).
    #[cfg(feature = "official")]
    Official,
    /// soniqo's single graph in tract.
    #[cfg(feature = "tract")]
    Soniqo,
    /// soniqo's single graph in ONNX Runtime.
    #[cfg(feature = "ort")]
    Ort,
}

// region networks

#[cfg(feature = "tract")]
mod tr {
    use super::*;
    pub use tract_onnx::prelude::*;
    pub type Model = Arc<TypedRunnableModel>;

    #[cfg(feature = "official")]
    pub static ENC: &[u8] = include_bytes!("../models/official/enc.onnx");
    #[cfg(feature = "official")]
    pub static ERB_DEC: &[u8] = include_bytes!("../models/official/erb_dec.onnx");
    #[cfg(feature = "official")]
    pub static DF_DEC: &[u8] = include_bytes!("../models/official/df_dec.onnx");

    pub fn fact(shape: &[usize]) -> InferenceFact {
        InferenceFact::dt_shape(f32::datum_type(), shape.iter().map(|&d| TDim::from(d)).collect::<TVec<_>>())
    }

    #[cfg(feature = "official")]
    pub fn official(t: usize) -> Result<(Model, Model, Model)> {
        let enc = tract_onnx::onnx().with_ignore_output_shapes(true).model_for_read(&mut &ENC[..])?
            .with_input_fact(0, fact(&[1, 1, t, NB_ERB]))?.with_input_fact(1, fact(&[1, 2, t, NB_DF]))?.into_typed()?;
        let outs: Vec<TypedFact> = (0..enc.outputs.len()).map(|i| enc.output_fact(i).cloned()).collect::<TractResult<_>>()?;
        let of = |k: usize| -> InferenceFact { fact(outs[k].shape.as_concrete().unwrap()) };
        // enc outputs: e0 e1 e2 e3 emb c0 lsnr; erb_dec(emb, e3, e2, e1, e0); df_dec(emb, c0)
        let erb = tract_onnx::onnx().with_ignore_output_shapes(true).model_for_read(&mut &ERB_DEC[..])?
            .with_input_fact(0, of(4))?.with_input_fact(1, of(3))?.with_input_fact(2, of(2))?.with_input_fact(3, of(1))?.with_input_fact(4, of(0))?;
        let dfd = tract_onnx::onnx().with_ignore_output_shapes(true).model_for_read(&mut &DF_DEC[..])?
            .with_input_fact(0, of(4))?.with_input_fact(1, of(5))?;
        Ok((enc.into_optimized()?.into_runnable()?, erb.into_optimized()?.into_runnable()?, dfd.into_optimized()?.into_runnable()?))
    }

    pub fn soniqo(t: usize) -> Result<Model> {
        let m = tract_onnx::onnx().with_ignore_output_shapes(true).model_for_read(&mut &SONIQO[..])?
            .with_input_fact(0, fact(&[1, 1, t, NB_ERB]))?.with_input_fact(1, fact(&[1, 2, t, NB_DF]))?;
        Ok(m.into_optimized()?.into_runnable()?)
    }
}

#[cfg(feature = "ort")]
mod ort {
    use super::*;
    use std::os::raw::{c_char, c_int, c_void};

    extern "C" {
        fn pikodf_ort_open(model: *const c_void, len: usize, threads: c_int, err: *mut c_char, n: usize) -> *mut c_void;
        fn pikodf_ort_run(s: *mut c_void, erb: *const f32, spec: *const f32, t: i64, mask: *mut f32, coefs: *mut f32, err: *mut c_char, n: usize) -> c_int;
        fn pikodf_ort_close(s: *mut c_void);
    }

    fn message(err: &[u8]) -> String {
        let end = err.iter().position(|&b| b == 0).unwrap_or(err.len());
        String::from_utf8_lossy(&err[..end]).into_owned()
    }

    /// One ONNX Runtime session (one intra-op thread); Run may be called from several threads at once.
    pub struct Session(*mut c_void);
    unsafe impl Send for Session {}
    unsafe impl Sync for Session {}

    impl Session {
        pub fn open() -> Result<Session> {
            let mut err = [0u8; 512];
            let s = unsafe { pikodf_ort_open(SONIQO.as_ptr() as *const c_void, SONIQO.len(), 1, err.as_mut_ptr() as *mut c_char, err.len()) };
            if s.is_null() { bail!("ONNX Runtime: {}", message(&err)); }
            Ok(Session(s))
        }

        /// erb [t,32], spec [2,t,96] -> mask [t,32], coefs [5,t,96,2]
        pub fn run(&self, erb: &[f32], spec: &[f32], t: usize, mask: &mut [f32], coefs: &mut [f32]) -> Result<()> {
            let mut err = [0u8; 512];
            let rc = unsafe { pikodf_ort_run(self.0, erb.as_ptr(), spec.as_ptr(), t as i64, mask.as_mut_ptr(), coefs.as_mut_ptr(), err.as_mut_ptr() as *mut c_char, err.len()) };
            if rc != 0 { bail!("ONNX Runtime: {}", message(&err)); }
            Ok(())
        }
    }

    impl Drop for Session {
        fn drop(&mut self) { unsafe { pikodf_ort_close(self.0) } }
    }
}

enum Nets {
    #[cfg(feature = "official")]
    Three(tr::Model, tr::Model, tr::Model),
    #[cfg(feature = "tract")]
    One(tr::Model),
    #[cfg(feature = "ort")]
    Ort(ort::Session),
}

// endregion

/// df.utils.get_norm_alpha(False) for sr 48000, hop 480, tau 1 (= 0.99)
fn norm_alpha() -> f32 {
    let a_ = (-(HOP as f64 / SR as f64) / 1.0).exp();
    let (mut a, mut p) = (1.0f64, 3);
    while a >= 1.0 {
        let s = 10f64.powi(p);
        a = (a_ * s).round() / s;
        p += 1;
    }
    a as f32
}

/// Time spent per stage (seconds, summed over chunks and workers).
#[derive(Default, Debug, Clone, Copy)]
pub struct Timing {
    pub resample: f64,
    pub load: f64,
    pub features: f64,
    pub network: f64,
    pub filter: f64,
}

impl Timing {
    fn add(&mut self, o: &Timing) {
        self.resample += o.resample;
        self.load += o.load;
        self.features += o.features;
        self.network += o.network;
        self.filter += o.filter;
    }
}

pub struct Dfn {
    backend: Backend,
    alpha: f32,
    nets: Mutex<HashMap<usize, Arc<Nets>>>,
}

impl Dfn {
    pub fn new(backend: Backend) -> Dfn {
        Dfn { backend, alpha: norm_alpha(), nets: Mutex::new(HashMap::new()) }
    }

    /// The networks for `t` frames (tract compiles per length; ONNX Runtime takes any length).
    fn nets(&self, t: usize, timing: &mut Timing) -> Result<Arc<Nets>> {
        #[allow(unused_mut)]
        let mut key = t;
        #[cfg(feature = "ort")]
        if self.backend == Backend::Ort { key = 0; }
        let mut cache = self.nets.lock().unwrap(); // held while loading: workers share one session
        if let Some(n) = cache.get(&key) {
            return Ok(n.clone());
        }
        let start = Instant::now();
        let n: Nets = match self.backend {
            #[cfg(feature = "official")]
            Backend::Official => { let (a, b, c) = tr::official(t)?; Nets::Three(a, b, c) }
            #[cfg(feature = "tract")]
            Backend::Soniqo => Nets::One(tr::soniqo(t)?),
            #[cfg(feature = "ort")]
            Backend::Ort => Nets::Ort(ort::Session::open()?),
        };
        timing.load += start.elapsed().as_secs_f64();
        let n = Arc::new(n);
        cache.insert(key, n.clone());
        Ok(n)
    }

    /// Mask [t, 32] and coefficients [t, 96, order, 2] for features erb [t, 32], cplx [t, 96].
    fn run(&self, nets: &Nets, erb: &[f32], cplx: &[Complex32], t: usize) -> Result<(Vec<f32>, Vec<f32>)> {
        // soniqo's graph takes [2, t, 96] (real parts, then imaginary parts) and pads the features itself
        #[allow(unused)]
        let spec = || {
            let mut spec = vec![0f32; 2 * t * NB_DF];
            for (i, c) in cplx.iter().enumerate() {
                spec[i] = c.re;
                spec[t * NB_DF + i] = c.im;
            }
            spec
        };
        // its coefficients [1, order, t, 96, 2] -> [t, 96, order, 2]
        #[allow(unused)]
        let reorder = |k: &[f32]| -> Vec<f32> {
            let mut c = vec![0f32; t * COEFS];
            for o in 0..DF_ORDER {
                for i in 0..t {
                    for f in 0..NB_DF {
                        let src = ((o * t + i) * NB_DF + f) * 2;
                        let dst = ((i * NB_DF + f) * DF_ORDER + o) * 2;
                        c[dst] = k[src];
                        c[dst + 1] = k[src + 1];
                    }
                }
            }
            c
        };
        match nets {
            #[cfg(feature = "official")]
            Nets::Three(enc, erbd, dfd) => {
                use tr::*;
                // pad_feat: ConstantPad2d((0, 0, -conv_lookahead, conv_lookahead)) -> frame i takes feature i + 2
                let mut fe = tract_ndarray::Array4::<f32>::zeros((1, 1, t, NB_ERB));
                let mut fc = tract_ndarray::Array4::<f32>::zeros((1, 2, t, NB_DF));
                for i in 0..t.saturating_sub(2) {
                    for b in 0..NB_ERB {
                        fe[[0, 0, i, b]] = erb[(i + 2) * NB_ERB + b];
                    }
                    for f in 0..NB_DF {
                        fc[[0, 0, i, f]] = cplx[(i + 2) * NB_DF + f].re;
                        fc[[0, 1, i, f]] = cplx[(i + 2) * NB_DF + f].im;
                    }
                }
                let mut out = enc.run(tvec!(fe.into_tvalue(), fc.into_tvalue()))?;
                let (_lsnr, c0, emb) = (out.pop().unwrap(), out.pop().unwrap(), out.pop().unwrap());
                let (e3, e2, e1, e0) = (out.pop().unwrap(), out.pop().unwrap(), out.pop().unwrap(), out.pop().unwrap());
                let m = erbd.run(tvec!(emb.clone(), e3, e2, e1, e0))?.remove(0);
                let c = dfd.run(tvec!(emb, c0))?.remove(0);
                let m: Vec<f32> = m.to_plain_array_view::<f32>()?.iter().copied().collect(); // [1,1,T,32]
                let c: Vec<f32> = c.to_plain_array_view::<f32>()?.iter().copied().collect(); // [1,T,96,order*2]
                ensure!(m.len() == t * NB_ERB && c.len() == t * COEFS, "unexpected output sizes");
                Ok((m, c))
            }
            #[cfg(feature = "tract")]
            Nets::One(m) => {
                use tr::*;
                let fe = tract_ndarray::Array4::from_shape_vec((1, 1, t, NB_ERB), erb.to_vec())?;
                let fc = tract_ndarray::Array4::from_shape_vec((1, 2, t, NB_DF), spec())?;
                let mut out = m.run(tvec!(fe.into_tvalue(), fc.into_tvalue()))?;
                let coefs = out.pop().unwrap();
                let mask = out.pop().unwrap();
                let mask: Vec<f32> = mask.to_plain_array_view::<f32>()?.iter().copied().collect();
                let k: Vec<f32> = coefs.to_plain_array_view::<f32>()?.iter().copied().collect();
                ensure!(mask.len() == t * NB_ERB && k.len() == t * COEFS, "unexpected output sizes");
                Ok((mask, reorder(&k)))
            }
            #[cfg(feature = "ort")]
            Nets::Ort(s) => {
                let mut mask = vec![0f32; t * NB_ERB];
                let mut k = vec![0f32; t * COEFS];
                s.run(erb, &spec(), t, &mut mask, &mut k)?;
                Ok((mask, reorder(&k)))
            }
        }
    }

    /// STFT and the network's features (fresh norm states, as erb_norm()/unit_norm() with state=None).
    fn features(&self, x: &[f32], t: usize) -> (Vec<Complex32>, Vec<f32>, Vec<Complex32>, DFState) {
        let mut st = DFState::new(SR, FFT, HOP, NB_ERB, 2);
        let mut spec = vec![Complex32::new(0.0, 0.0); t * FREQS];
        for i in 0..t {
            st.analysis(&x[i * HOP..(i + 1) * HOP], &mut spec[i * FREQS..(i + 1) * FREQS]);
        }
        let mut fs = DFState::new(SR, FFT, HOP, NB_ERB, 2);
        fs.init_norm_states(NB_DF);
        let mut erb = vec![0f32; t * NB_ERB];
        let mut cplx = vec![Complex32::new(0.0, 0.0); t * NB_DF];
        for i in 0..t {
            let sp = &spec[i * FREQS..(i + 1) * FREQS];
            fs.feat_erb(sp, self.alpha, &mut erb[i * NB_ERB..(i + 1) * NB_ERB]);
            fs.feat_cplx(&sp[..NB_DF], self.alpha, &mut cplx[i * NB_DF..(i + 1) * NB_DF]);
        }
        (spec, erb, cplx, st)
    }

    /// ERB mask on the high bins, deep filter on the low 96 bins (frames i-2 .. i+2), optional attenuation limit,
    /// then synthesis with a fresh state.
    fn filter(&self, st: &mut DFState, spec: &[Complex32], mask: &[f32], coefs: &[f32], t: usize, atten_db: f32) -> Vec<f32> {
        let mut enh = spec.to_vec();
        let lim = if atten_db.abs() > 0.0 { Some(10f32.powf(-atten_db.abs() / 20.0)) } else { None };
        for i in 0..t {
            let row = &mut enh[i * FREQS..(i + 1) * FREQS];
            st.apply_mask(row, &mask[i * NB_ERB..(i + 1) * NB_ERB]);
            for f in 0..NB_DF {
                let mut acc = Complex32::new(0.0, 0.0);
                for k in 0..DF_ORDER {
                    let j = i as i64 - (DF_ORDER - 1 - DF_LOOKAHEAD) as i64 + k as i64;
                    if j >= 0 && (j as usize) < t {
                        let o = ((i * NB_DF + f) * DF_ORDER + k) * 2;
                        acc += spec[j as usize * FREQS + f] * Complex32::new(coefs[o], coefs[o + 1]);
                    }
                }
                row[f] = acc;
            }
            if let Some(l) = lim {
                for f in 0..FREQS {
                    row[f] = spec[i * FREQS + f] * l + row[f] * (1.0 - l);
                }
            }
        }
        let mut syn = DFState::new(SR, FFT, HOP, NB_ERB, 2);
        let mut y = vec![0f32; t * HOP];
        for i in 0..t {
            let mut row = enh[i * FREQS..(i + 1) * FREQS].to_vec();
            syn.synthesis(&mut row, &mut y[i * HOP..(i + 1) * HOP]);
        }
        y
    }

    /// df.enhance(model, state, audio, pad=True, atten_lim_db) on one span at 48 kHz (atten 0 = no limit).
    pub fn enhance(&self, audio: &[f32], atten_db: f32, timing: &mut Timing) -> Result<Vec<f32>> {
        let orig = audio.len();
        let mut x = audio.to_vec();
        x.extend(std::iter::repeat(0.0).take(FFT)); // F.pad(audio, (0, n_fft))
        let t = x.len() / HOP;
        let start = Instant::now();
        let (spec, erb, cplx, mut st) = self.features(&x, t);
        timing.features += start.elapsed().as_secs_f64();
        let nets = self.nets(t, timing)?;
        let start = Instant::now();
        let (mask, coefs) = self.run(&nets, &erb, &cplx, t)?;
        timing.network += start.elapsed().as_secs_f64();
        let start = Instant::now();
        let y = self.filter(&mut st, &spec, &mask, &coefs, t, atten_db);
        timing.filter += start.elapsed().as_secs_f64();
        let d = FFT - HOP;
        Ok(y[d..d + orig].to_vec())
    }

    /// As desktop clearcut's denoise: chunks of `core_s` seconds, each one enhance() call over the chunk plus `ctx_s`
    /// seconds on both sides (thrown away after). `workers` chunks run at once; past `deadline` it gives up.
    pub fn enhance_chunked(&self, audio: &[f32], core_s: f32, ctx_s: f32, workers: usize, deadline: Option<Instant>, timing: &mut Timing) -> Result<Vec<f32>> {
        let total = audio.len();
        let core = ((core_s * SR as f32) as usize).max(HOP);
        let ctx = (ctx_s * SR as f32) as usize;
        let starts: Vec<usize> = (0..total).step_by(core).collect();
        let parts: Vec<Mutex<Option<Result<Vec<f32>>>>> = starts.iter().map(|_| Mutex::new(None)).collect();
        let next = AtomicUsize::new(0);
        let stop = AtomicBool::new(false);
        let timings = Mutex::new(Timing::default());
        std::thread::scope(|scope| {
            for _ in 0..workers.max(1).min(starts.len()) {
                scope.spawn(|| {
                    let mut tm = Timing::default();
                    loop {
                        let k = next.fetch_add(1, Ordering::SeqCst);
                        if k >= starts.len() || stop.load(Ordering::SeqCst) {
                            break;
                        }
                        if deadline.is_some_and(|d| Instant::now() > d) {
                            stop.store(true, Ordering::SeqCst);
                            *parts[k].lock().unwrap() = Some(Err(anyhow!("over the time budget")));
                            break;
                        }
                        let cs = starts[k];
                        let ce = total.min(cs + core);
                        let (rs, re) = (cs.saturating_sub(ctx), total.min(ce + ctx));
                        let r = self.enhance(&audio[rs..re], 0.0, &mut tm).map(|y| y[cs - rs..ce - rs].to_vec());
                        if r.is_err() {
                            stop.store(true, Ordering::SeqCst);
                        }
                        *parts[k].lock().unwrap() = Some(r);
                    }
                    timings.lock().unwrap().add(&tm);
                });
            }
        });
        timing.add(&timings.into_inner().unwrap());
        let mut out = Vec::with_capacity(total);
        for p in parts {
            match p.into_inner().unwrap() {
                Some(r) => out.extend(r?),
                None => bail!("over the time budget"),
            }
        }
        Ok(out)
    }

    /// The app's policy: short notes whole, long ones in chunks on two workers.
    pub fn denoise(&self, audio: &[f32], deadline: Option<Instant>, timing: &mut Timing) -> Result<Vec<f32>> {
        if audio.len() as f32 <= WHOLE_MAX_S * SR as f32 {
            return self.enhance(audio, 0.0, timing);
        }
        let workers = std::env::var("PIKODF_WORKERS").ok().and_then(|v| v.parse().ok()).unwrap_or(WORKERS); // benchmarks
        self.enhance_chunked(audio, CHUNK_CORE_S, CHUNK_CTX_S, workers, deadline, timing)
    }
}

/// torchaudio.functional.resample (sinc_interp_hann, lowpass_filter_width 6, rolloff 0.99), the same math and
/// order of operations as VoiceCut.resample in the app (only taps inside the window are summed).
pub fn resample(x: &[f32], orig: usize, target: usize) -> Vec<f32> {
    if orig == target {
        return x.to_vec();
    }
    let gcd = |mut a: usize, mut b: usize| { while b != 0 { let t = a % b; a = b; b = t; } a };
    let g = gcd(orig, target);
    let (o, n) = (orig / g, target / g);
    let lpw = 6i64;
    let base = o.min(n) as f64 * 0.99;
    let width = ((lpw * o as i64) as f64 / base).ceil() as usize;
    let klen = 2 * width + o;
    let scale = base / o as f64;
    let mut kernels = vec![0f32; n * klen];
    for j in 0..n {
        for i in 0..klen {
            let idx = (i as f64 - width as f64) / o as f64;
            let mut t = (-(j as f64) / n as f64 + idx) * base;
            t = t.clamp(-(lpw as f64), lpw as f64);
            let mut window = (t * std::f64::consts::PI / lpw as f64 / 2.0).cos();
            window *= window;
            t *= std::f64::consts::PI;
            let k = if t == 0.0 { 1.0 } else { t.sin() / t };
            kernels[j * klen + i] = (k * window * scale) as f32;
        }
    }
    let len = x.len();
    let pad_len = len + 2 * width + o;
    let mut pad = vec![0f32; pad_len];
    pad[width..width + len].copy_from_slice(x);
    let frames = (pad_len - klen) / o + 1;
    let out_len = ((n as u64 * len as u64) as f64 / o as f64).ceil() as usize;
    let ranges: Vec<(usize, usize)> = (0..n).map(|j| {
        let k = &kernels[j * klen..(j + 1) * klen];
        let a = k.iter().position(|&v| v != 0.0).unwrap_or(klen);
        let b = k.iter().rposition(|&v| v != 0.0).map(|p| p + 1).unwrap_or(a).max(a);
        (a, b)
    }).collect();
    let mut out = vec![0f32; out_len];
    for f in 0..frames {
        let b0 = f * o;
        for j in 0..n {
            let idx = f * n + j;
            if idx >= out_len { break; }
            let (a, b) = ranges[j];
            let k = &kernels[j * klen..(j + 1) * klen];
            let mut s = 0f32;
            for i in a..b {
                s += k[i] * pad[b0 + i];
            }
            out[idx] = s;
        }
    }
    out
}

/// The app's whole step: a note at `rate` -> the same note without its background noise (same rate and length).
pub fn denoise_note(dfn: &Dfn, pcm: &[f32], rate: usize, deadline: Option<Instant>, timing: &mut Timing) -> Result<Vec<f32>> {
    let start = Instant::now();
    let up = resample(pcm, rate, SR);
    let mut rs = start.elapsed().as_secs_f64();
    let y = dfn.denoise(&up, deadline, timing)?;
    let start = Instant::now();
    let mut out = resample(&y, SR, rate);
    out.resize(pcm.len(), 0.0);
    rs += start.elapsed().as_secs_f64();
    timing.resample += rs;
    Ok(out)
}

// region JNI (app.morphe.extension.twitter.patches.voice.DfNative)

#[cfg(feature = "ort")]
mod jni_api {
    use super::*;
    use jni::objects::{JClass, JFloatArray};
    use jni::sys::{jfloatArray, jint, jstring};
    use jni::JNIEnv;
    use std::time::Duration;

    static LAST: Mutex<String> = Mutex::new(String::new());

    #[no_mangle]
    pub extern "system" fn Java_app_morphe_extension_twitter_patches_voice_DfNative_ping(_env: JNIEnv, _cls: JClass) -> jint {
        42
    }

    /// float[] mono at `rate` -> the same note without its background noise (same rate and length), or null
    /// (error, or past `budgetMs`; 0 = no limit).
    #[no_mangle]
    pub extern "system" fn Java_app_morphe_extension_twitter_patches_voice_DfNative_nativeDenoise(env: JNIEnv, _cls: JClass, input: JFloatArray, rate: jint, budget_ms: jint) -> jfloatArray {
        let start = Instant::now();
        let deadline = if budget_ms > 0 { Some(start + Duration::from_millis(budget_ms as u64)) } else { None };
        let run = || -> Result<jfloatArray> {
            let n = env.get_array_length(&input)? as usize;
            let mut x = vec![0f32; n];
            env.get_float_array_region(&input, 0, &mut x)?;
            let dfn = Dfn::new(Backend::Ort); // one session per note: nothing stays in memory between notes
            let mut tm = Timing::default();
            let y = denoise_note(&dfn, &x, rate as usize, deadline, &mut tm)?;
            if let Ok(mut l) = LAST.lock() { *l = format!(
                "{:.1} s in {} ms (resample {:.0}, session {:.0}, features {:.0}, network {:.0}, filter {:.0} ms; summed over workers)",
                n as f64 / rate as f64, start.elapsed().as_millis(), tm.resample * 1e3, tm.load * 1e3, tm.features * 1e3, tm.network * 1e3, tm.filter * 1e3); }
            let out = env.new_float_array(y.len() as i32)?;
            env.set_float_array_region(&out, 0, &y)?;
            Ok(out.into_raw())
        };
        let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(run))
            .unwrap_or_else(|p| Err(anyhow!("panic: {}", p.downcast_ref::<&str>().map(|s| s.to_string())
                .or_else(|| p.downcast_ref::<String>().cloned()).unwrap_or_default())));
        match result {
            Ok(a) => a,
            Err(e) => {
                if let Ok(mut l) = LAST.lock() { *l = format!("failed after {} ms: {e:#}", start.elapsed().as_millis()); }
                std::ptr::null_mut()
            }
        }
    }

    /// What the last denoise call did (timings or the error), for the log.
    #[no_mangle]
    pub extern "system" fn Java_app_morphe_extension_twitter_patches_voice_DfNative_lastInfo(env: JNIEnv, _cls: JClass) -> jstring {
        let s = LAST.lock().map(|s| s.clone()).unwrap_or_default();
        env.new_string(s).map(|s| s.into_raw()).unwrap_or(std::ptr::null_mut())
    }
}

// endregion
