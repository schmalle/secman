/**
 * Entry point for `node --import ./test/register.mjs`.
 *
 * Resolver hooks run on a separate thread and must be installed via
 * `module.register`, which needs a module that executes in the main thread first.
 * That is all this file is for; the hook itself lives in resolve-ts.mjs.
 */
import { register } from 'node:module';

register('./resolve-ts.mjs', import.meta.url);
