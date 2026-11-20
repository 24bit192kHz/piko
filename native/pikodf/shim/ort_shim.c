/* soniqo's DeepFilterNet3 graph in ONNX Runtime through the plain C API (no C++ runtime needed). */
#include <onnxruntime_c_api.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

static const OrtApi *api(void) {
    static const OrtApi *a = NULL;
    if (!a) a = OrtGetApiBase()->GetApi(ORT_API_VERSION);
    return a;
}

static int fail(OrtStatus *st, char *err, size_t n) {
    if (!st) return 0;
    snprintf(err, n, "%s", api()->GetErrorMessage(st));
    api()->ReleaseStatus(st);
    return -1;
}

typedef struct { OrtEnv *env; OrtSession *session; OrtMemoryInfo *mem; } Ctx;

void pikodf_ort_close(void *p) {
    Ctx *c = (Ctx *) p;
    if (!c) return;
    if (c->session) api()->ReleaseSession(c->session);
    if (c->mem) api()->ReleaseMemoryInfo(c->mem);
    if (c->env) api()->ReleaseEnv(c->env);
    free(c);
}

void *pikodf_ort_open(const void *model, size_t len, int threads, char *err, size_t n) {
    const OrtApi *a = api();
    if (!a) { snprintf(err, n, "ONNX Runtime API %d unavailable", ORT_API_VERSION); return NULL; }
    Ctx *c = (Ctx *) calloc(1, sizeof(Ctx));
    OrtSessionOptions *so = NULL;
    if (fail(a->CreateEnv(ORT_LOGGING_LEVEL_ERROR, "pikodf", &c->env), err, n)) goto bad;
    if (fail(a->CreateSessionOptions(&so), err, n)) goto bad;
    if (fail(a->SetIntraOpNumThreads(so, threads), err, n)) goto bad;
    if (fail(a->SetInterOpNumThreads(so, 1), err, n)) goto bad;
    if (fail(a->SetSessionGraphOptimizationLevel(so, ORT_ENABLE_ALL), err, n)) goto bad;
    /* the memory pattern pre-plans every activation of the whole run: same speed without it, ~30% less memory */
    if (fail(a->DisableMemPattern(so), err, n)) goto bad;
    if (fail(a->AddSessionConfigEntry(so, "session.intra_op.allow_spinning", "0"), err, n)) goto bad;
    if (fail(a->CreateSessionFromArray(c->env, model, len, so, &c->session), err, n)) goto bad;
    if (fail(a->CreateCpuMemoryInfo(OrtArenaAllocator, OrtMemTypeDefault, &c->mem), err, n)) goto bad;
    a->ReleaseSessionOptions(so);
    return c;
bad:
    if (so) a->ReleaseSessionOptions(so);
    pikodf_ort_close(c);
    return NULL;
}

/* erb [t,32], spec [2,t,96] -> mask [t,32], coefs [5,t,96,2] (the graph's own layouts). Thread-safe. */
int pikodf_ort_run(void *p, const float *erb, const float *spec, int64_t t, float *mask, float *coefs, char *err, size_t n) {
    const OrtApi *a = api();
    Ctx *c = (Ctx *) p;
    int64_t se[4] = {1, 1, t, 32}, ss[4] = {1, 2, t, 96};
    OrtValue *in[2] = {NULL, NULL}, *out[2] = {NULL, NULL};
    const char *in_names[2] = {"feat_erb", "feat_spec"}, *out_names[2] = {"erb_mask", "df_coefs"};
    int rc = -1;
    if (fail(a->CreateTensorWithDataAsOrtValue(c->mem, (void *) erb, (size_t) t * 32 * 4, se, 4, ONNX_TENSOR_ELEMENT_DATA_TYPE_FLOAT, &in[0]), err, n)) goto done;
    if (fail(a->CreateTensorWithDataAsOrtValue(c->mem, (void *) spec, (size_t) t * 2 * 96 * 4, ss, 4, ONNX_TENSOR_ELEMENT_DATA_TYPE_FLOAT, &in[1]), err, n)) goto done;
    if (fail(a->Run(c->session, NULL, in_names, (const OrtValue *const *) in, 2, out_names, 2, out), err, n)) goto done;
    float *m = NULL, *k = NULL;
    if (fail(a->GetTensorMutableData(out[0], (void **) &m), err, n)) goto done;
    if (fail(a->GetTensorMutableData(out[1], (void **) &k), err, n)) goto done;
    size_t mc = 0, kc = 0;
    OrtTensorTypeAndShapeInfo *info = NULL;
    if (fail(a->GetTensorTypeAndShape(out[0], &info), err, n)) goto done;
    OrtStatus *cs = a->GetTensorShapeElementCount(info, &mc);
    if (cs) a->ReleaseStatus(cs);
    a->ReleaseTensorTypeAndShapeInfo(info);
    if (fail(a->GetTensorTypeAndShape(out[1], &info), err, n)) goto done;
    cs = a->GetTensorShapeElementCount(info, &kc);
    if (cs) a->ReleaseStatus(cs);
    a->ReleaseTensorTypeAndShapeInfo(info);
    if (mc != (size_t) t * 32 || kc != (size_t) t * 96 * 5 * 2) { snprintf(err, n, "unexpected output sizes %zu %zu", mc, kc); goto done; }
    memcpy(mask, m, mc * 4);
    memcpy(coefs, k, kc * 4);
    rc = 0;
done:
    for (int i = 0; i < 2; i++) { if (in[i]) a->ReleaseValue(in[i]); if (out[i]) a->ReleaseValue(out[i]); }
    return rc;
}
