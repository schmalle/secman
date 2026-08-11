/**
 * Build-stamp collection — runs ONCE, when the Astro config is evaluated
 * (`astro build`, or the start of `astro dev`).
 *
 * The footer used to shell out to `git log` from component frontmatter. With
 * `output: "server"` that ran on every single request, in whatever directory the
 * Node server happened to be started from, and in the shipped container — which
 * has no `.git` and no `git` binary — it failed every time and fell back to a
 * hardcoded date. Collecting here instead makes the value a real build stamp:
 * computed once, inlined into the bundle, correct wherever the bundle runs.
 *
 * Every value can be overridden from the environment so image builds that have
 * no git context (`docker/frontend/Dockerfile` copies only `src/frontend/`) can
 * still be stamped from the host:
 *
 *   SECMAN_BUILD_TIME        ISO-8601 timestamp of the build
 *   SECMAN_GIT_COMMIT        abbreviated commit hash
 *   SECMAN_GIT_COMMIT_TIME   ISO-8601 committer date of that commit
 */

import { execFileSync } from 'node:child_process';
import { dirname } from 'node:path';
import { fileURLToPath } from 'node:url';

/**
 * Anchor git to this file's directory, not `process.cwd()`. The dev server and
 * the container are started from different places; `git` walking up from a
 * fixed, known directory is the only way to get a stable answer.
 */
const FRONTEND_DIR = dirname(fileURLToPath(import.meta.url));

const GIT_TIMEOUT_MS = 5000;

/**
 * Run git with an argv array (never a shell string) and swallow every failure:
 * not a repository, git not installed, shallow clone, timeout. A missing stamp
 * is rendered as "unknown", which is strictly better than a wrong date.
 */
function git(args) {
    try {
        const output = execFileSync('git', args, {
            cwd: FRONTEND_DIR,
            encoding: 'utf8',
            timeout: GIT_TIMEOUT_MS,
            // stderr discarded: "not a git repository" is an expected outcome here.
            stdio: ['ignore', 'pipe', 'ignore'],
        });
        return trimmedOrNull(output);
    } catch {
        return null;
    }
}

function trimmedOrNull(value) {
    if (typeof value !== 'string') return null;
    const trimmed = value.trim();
    return trimmed.length > 0 ? trimmed : null;
}

/**
 * Collect the raw stamps. Validation and formatting live in
 * `src/utils/buildInfo.ts` so they stay unit testable — this half only gathers.
 *
 * @param {NodeJS.ProcessEnv} env
 * @returns {{buildTime: string|null, commitTime: string|null, commitHash: string|null}}
 */
export function resolveBuildInfo(env = process.env) {
    const buildTime = trimmedOrNull(env.SECMAN_BUILD_TIME) ?? new Date().toISOString();

    // `%h` = abbreviated hash, `%cI` = committer date in strict ISO-8601 (with the
    // committer's UTC offset, which buildInfo.ts normalises to Z).
    const commitHash = trimmedOrNull(env.SECMAN_GIT_COMMIT) ?? git(['log', '-1', '--format=%h']);
    const commitTime = trimmedOrNull(env.SECMAN_GIT_COMMIT_TIME) ?? git(['log', '-1', '--format=%cI']);

    return { buildTime, commitTime, commitHash };
}
