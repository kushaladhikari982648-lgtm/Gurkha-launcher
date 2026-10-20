/* SPDX-License-Identifier: LGPL-3.0-only
 * Per-context capability cache for the LTW-derived integration.
 * Zero-initialized by Krypton's NewGLState; never shared between GL contexts.
 */
#ifndef PV_CONTEXT_CACHE_H
#define PV_CONTEXT_CACHE_H
struct pv_mdi_cache {
    int initialized;
    int supported;
    void *get_integer;
    void *elements;
    void *arrays;
};
#endif
