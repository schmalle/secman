/**
 * Node ESM resolver hook for the frontend unit test tier.
 *
 * Production source imports siblings without a file extension (`../utils/auth`),
 * because Vite/Astro resolve those at build time. Node's ESM loader does not — it
 * requires the extension — so before this hook every test that imported a real
 * service module died with ERR_MODULE_NOT_FOUND instead of running. This hook
 * closes that gap so tests can import production modules as written, and no
 * production import has to be rewritten for the test runner's benefit.
 *
 * Only `.ts` is resolved, never `.tsx`. Node's type stripping cannot parse JSX,
 * so importing a component would fail with an opaque syntax error inside the
 * stripped output. When the requested path exists only as `.tsx`, this hook
 * raises a pointed error naming the repo convention instead: extract the pure
 * logic into a sibling `.ts` module (see `productSearchResults.ts`) and test that.
 */
import { existsSync } from 'node:fs';
import { fileURLToPath } from 'node:url';

export async function resolve(specifier, context, nextResolve) {
    try {
        return await nextResolve(specifier, context);
    } catch (error) {
        // Only relative/absolute specifiers can be extension-completed; a failed
        // bare specifier is a genuinely missing package and must keep its own error.
        if (!specifier.startsWith('.') && !specifier.startsWith('/')) throw error;
        if (!context.parentURL) throw error;

        const base = new URL(specifier, context.parentURL);
        for (const candidate of [`${base.href}.ts`, `${base.href}/index.ts`]) {
            if (existsSync(fileURLToPath(candidate))) {
                return { url: candidate, format: 'module-typescript', shortCircuit: true };
            }
        }

        if (existsSync(fileURLToPath(`${base.href}.tsx`))) {
            throw new Error(
                `Cannot import '${specifier}' from ${context.parentURL}: it resolves to a .tsx ` +
                `module, and Node's type stripping cannot parse JSX. Extract the logic under test ` +
                `into a sibling .ts module and import that instead (see productSearchResults.ts), ` +
                `or assert against the component source text with readFileSync (see Sidebar.test.ts).`,
                { cause: error }
            );
        }

        throw error;
    }
}
