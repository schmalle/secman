/// <reference types="astro/client" />

/**
 * Build metadata injected by Vite's `define` (see `astro.config.mjs`), as a
 * JSON string. Consume it through `normalizeBuildInfo` in `utils/buildInfo.ts`
 * rather than parsing it directly.
 */
declare const __SECMAN_BUILD_INFO__: string;
